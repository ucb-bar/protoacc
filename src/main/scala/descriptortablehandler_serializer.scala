package protoacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheReq, HellaCacheResp, HellaCacheArbiter, HellaCacheIO}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._

class SerializerInfoBundle extends Bundle {
  val descriptor_table_addr = UInt(64.W)
  val cpp_obj_addr = UInt(64.W)

  val has_bits_base_offset_only = UInt(64.W)

  val min_fieldno = UInt(32.W)
  val max_fieldno = UInt(32.W)
}


class HasBitsRequestMetaBundle extends Bundle {
  val descriptor_table_addr = UInt(64.W)
  val cpp_obj_addr = UInt(64.W)
  val has_bits_max_bitoffset = UInt(32.W)
  val min_fieldno = UInt(32.W)
  val parent_fieldnum = UInt(32.W)

  val depth = UInt(ProtoaccParams.MAX_NESTED_LEVELS_WIDTH.W)
}


class DescrRequestUopBundle extends Bundle {
  val descriptor_table_addr = UInt(64.W)
  val cpp_obj_addr = UInt(64.W)
  val relative_fieldno = UInt(32.W)
  val min_fieldno = UInt(32.W)
  val is_submessage = Bool()

  val parent_fieldnum = UInt(32.W)
  val depth = UInt(ProtoaccParams.MAX_NESTED_LEVELS_WIDTH.W)
}



class DescrToHandlerBundle extends Bundle {
  val src_data_addr = UInt(64.W)
  val src_data_type = UInt(5.W)
  val is_repeated = Bool()

  val field_number = UInt(32.W)

  val end_of_message = Bool()

  val depth = UInt(ProtoaccParams.MAX_NESTED_LEVELS_WIDTH.W)
}


class SerDescriptorTableHandler()(implicit p: Parameters) extends Module
  with MemoryOpConstants {

  val io = IO(new Bundle {
    val serializer_cmd_in = Decoupled(new SerializerInfoBundle).flip
    val ser_field_handler_output = Decoupled(new DescrToHandlerBundle)

    val l2helperUser1 = new L1MemHelperBundle
    val l2helperUser2 = new L1MemHelperBundle
  })

  io.l2helperUser1.req.valid := false.B
  io.l2helperUser1.resp.ready := false.B
  io.l2helperUser1.req.bits.cmd := M_XRD
  io.l2helperUser2.req.valid := false.B
  io.l2helperUser2.resp.ready := false.B
  io.l2helperUser2.req.bits.cmd := M_XRD

  val depth = RegInit(0.U(ProtoaccParams.MAX_NESTED_LEVELS_WIDTH.W))
  assert(depth < ProtoaccParams.MAX_NESTED_LEVELS.U, "FAIL. TOO MANY NESTED LEVELS")

  val depth_plus_one = depth + 1.U
  val depth_minus_one = depth - 1.U

  val busy_toplevel = depth =/= 0.U
  val not_busy_toplevel = !busy_toplevel


  val stack_descr_table = Reg(Vec(ProtoaccParams.MAX_NESTED_LEVELS, UInt(64.W)))
  val stack_cpp_obj = Reg(Vec(ProtoaccParams.MAX_NESTED_LEVELS, UInt(64.W)))
  val stack_has_bits_base_offset_only = Reg(Vec(ProtoaccParams.MAX_NESTED_LEVELS, UInt(64.W)))

  val stack_has_bits_next_bitoffset = Reg(Vec(ProtoaccParams.MAX_NESTED_LEVELS, UInt(64.W)))
  val stack_min_fieldno = Reg(Vec(ProtoaccParams.MAX_NESTED_LEVELS, UInt(32.W)))
  val stack_is_submessage_base_addr = Reg(Vec(ProtoaccParams.MAX_NESTED_LEVELS, UInt(64.W)))

  val accept_cpu_command = DecoupledHelper(
    io.serializer_cmd_in.valid,
    not_busy_toplevel
  )

  io.serializer_cmd_in.ready := accept_cpu_command.fire(io.serializer_cmd_in.valid)
  when (accept_cpu_command.fire()) {
    ProtoaccLogger.logInfo("[serdescriptor] acc message: depth %d, descr_addr 0x%x, cpp_obj 0x%x, has_bits_base_offset_only 0x%x, min_fieldno %d, max_fieldno %d\n",
      depth,
      io.serializer_cmd_in.bits.descriptor_table_addr,
      io.serializer_cmd_in.bits.cpp_obj_addr,
      io.serializer_cmd_in.bits.has_bits_base_offset_only,
      io.serializer_cmd_in.bits.min_fieldno,
      io.serializer_cmd_in.bits.max_fieldno
    )
    stack_descr_table(depth_plus_one) := io.serializer_cmd_in.bits.descriptor_table_addr
    stack_cpp_obj(depth_plus_one) := io.serializer_cmd_in.bits.cpp_obj_addr
    stack_has_bits_base_offset_only(depth_plus_one) := io.serializer_cmd_in.bits.has_bits_base_offset_only
    stack_has_bits_next_bitoffset(depth_plus_one) := (io.serializer_cmd_in.bits.max_fieldno - io.serializer_cmd_in.bits.min_fieldno) + 1.U
    stack_min_fieldno(depth_plus_one) := io.serializer_cmd_in.bits.min_fieldno
    stack_is_submessage_base_addr(depth_plus_one) := (((io.serializer_cmd_in.bits.max_fieldno - io.serializer_cmd_in.bits.min_fieldno) + 1.U) << 4) + 32.U + io.serializer_cmd_in.bits.descriptor_table_addr
    depth := depth_plus_one
  }


  val current_descr_table = stack_descr_table(depth)
  val current_cpp_obj = stack_cpp_obj(depth)
  val current_has_bits_base_offset_only = stack_has_bits_base_offset_only(depth)
  val current_has_bits_next_bitoffset = stack_has_bits_next_bitoffset(depth)
  val current_min_fieldno = stack_min_fieldno(depth)
  val current_is_submessage_base_addr = stack_is_submessage_base_addr(depth)

  val current_parent_fieldnum = stack_has_bits_next_bitoffset(depth_minus_one) + stack_min_fieldno(depth_minus_one)


  val N_STATE_BITS = 2
  val s_hasBitsLoader_IsSubmessageLoad = 1.U(N_STATE_BITS.W)
  val s_hasBitsLoader_HasBitsLoad = 2.U(N_STATE_BITS.W)
  val s_hasBitsLoader_WaitToAdvance = 3.U(N_STATE_BITS.W)
  val hasBitsLoaderState = RegInit(s_hasBitsLoader_IsSubmessageLoad)

  val hasbits_base_address = current_has_bits_base_offset_only + current_cpp_obj
  val is_submessage_base_address = current_is_submessage_base_addr
  val hasbits_request_meta_Q = Module(new Queue(new HasBitsRequestMetaBundle, 4))

  hasbits_request_meta_Q.io.enq.valid := false.B
  io.l2helperUser1.req.valid := false.B

  hasbits_request_meta_Q.io.enq.bits.descriptor_table_addr := current_descr_table
  hasbits_request_meta_Q.io.enq.bits.cpp_obj_addr := current_cpp_obj
  hasbits_request_meta_Q.io.enq.bits.has_bits_max_bitoffset := current_has_bits_next_bitoffset
  hasbits_request_meta_Q.io.enq.bits.min_fieldno := current_min_fieldno
  hasbits_request_meta_Q.io.enq.bits.parent_fieldnum := current_parent_fieldnum
  hasbits_request_meta_Q.io.enq.bits.depth := depth

  val hasbits_array_index = current_has_bits_next_bitoffset >> 5
  val hasbits_request_address = (hasbits_array_index << 2) + hasbits_base_address
  val is_submessage_request_address = (hasbits_array_index << 2) + is_submessage_base_address

  io.l2helperUser1.req.bits.size := 2.U

  val ADVANCE_OK = Wire(Bool())
  ADVANCE_OK := false.B


  switch (hasBitsLoaderState) {
    is (s_hasBitsLoader_IsSubmessageLoad) {
      io.l2helperUser1.req.bits.addr := is_submessage_request_address

      hasbits_request_meta_Q.io.enq.valid := busy_toplevel && io.l2helperUser1.req.ready
      io.l2helperUser1.req.valid := busy_toplevel && hasbits_request_meta_Q.io.enq.ready
      when (hasbits_request_meta_Q.io.enq.ready && busy_toplevel && io.l2helperUser1.req.ready) {
        hasBitsLoaderState := s_hasBitsLoader_HasBitsLoad
        ProtoaccLogger.logInfo("[serdescriptor] dispatch is_submessage load, relfieldno %d, arrayindex %d, reqaddr 0x%x\n",
          Wire(current_has_bits_next_bitoffset),
          hasbits_array_index,
          is_submessage_request_address)
      }
    }
    is (s_hasBitsLoader_HasBitsLoad) {
      io.l2helperUser1.req.bits.addr := hasbits_request_address

      io.l2helperUser1.req.valid := true.B
      when (io.l2helperUser1.req.ready) {
        hasBitsLoaderState := s_hasBitsLoader_WaitToAdvance
        ProtoaccLogger.logInfo("[serdescriptor] dispatch hasbits load, relfieldno %d, arrayindex %d, reqaddr 0x%x\n",
          Wire(current_has_bits_next_bitoffset),
          hasbits_array_index,
          hasbits_request_address)
      }

    }
    is (s_hasBitsLoader_WaitToAdvance) {
      ProtoaccLogger.logInfo("[serdescriptor] waiting for ADVANCE_OK\n")

      when (ADVANCE_OK) {
        ProtoaccLogger.logInfo("[serdescriptor] ADVANCE_OK granted.\n")

        hasBitsLoaderState := s_hasBitsLoader_IsSubmessageLoad
        when (current_has_bits_next_bitoffset <= 31.U) {
          when (depth === 1.U) {
            depth := depth - 1.U
          } .otherwise {
            depth := depth - 1.U
          }
        } .otherwise {
          val next_next_field_offset = (current_has_bits_next_bitoffset % 32.U) + 1.U
          current_has_bits_next_bitoffset := current_has_bits_next_bitoffset - next_next_field_offset
        }

      }
    }
  }





  val N_STATE_BITS_V2 = 2
  val s_hasBitsCONSUMER_AcceptIsSubmessage = 1.U(N_STATE_BITS_V2.W)
  val s_hasBitsCONSUMER_AcceptHasBitsAndIterate = 2.U(N_STATE_BITS_V2.W)
  val hasBitsCONSUMERState = RegInit(s_hasBitsCONSUMER_AcceptIsSubmessage)

  val is_submessage_current_value = RegInit(0.U(32.W))
  val already_issued_ADVANCE_OK = RegInit(false.B)

  io.l2helperUser1.resp.ready := false.B
  hasbits_request_meta_Q.io.deq.ready := false.B

  val fieldno_offset_from_tail = RegInit(0.U(32.W))
  val num_fields_this_hasbits = (hasbits_request_meta_Q.io.deq.bits.has_bits_max_bitoffset % 32.U) + 1.U
  val num_fields_this_hasbits_minus_one = num_fields_this_hasbits - 1.U
  val hasbits_resp_fieldno = hasbits_request_meta_Q.io.deq.bits.has_bits_max_bitoffset - fieldno_offset_from_tail


  val descr_request_Q = Module(new Queue(new DescrRequestUopBundle, 4))
  descr_request_Q.io.enq.bits.descriptor_table_addr := hasbits_request_meta_Q.io.deq.bits.descriptor_table_addr
  descr_request_Q.io.enq.bits.cpp_obj_addr := hasbits_request_meta_Q.io.deq.bits.cpp_obj_addr
  descr_request_Q.io.enq.bits.relative_fieldno := hasbits_resp_fieldno
  descr_request_Q.io.enq.bits.min_fieldno := hasbits_request_meta_Q.io.deq.bits.min_fieldno
  descr_request_Q.io.enq.bits.parent_fieldnum := hasbits_request_meta_Q.io.deq.bits.parent_fieldnum
  descr_request_Q.io.enq.bits.depth := hasbits_request_meta_Q.io.deq.bits.depth

  descr_request_Q.io.enq.valid := false.B


  val do_hasbits_to_descr = DecoupledHelper(
    io.l2helperUser1.resp.valid,
    hasbits_request_meta_Q.io.deq.valid,
    descr_request_Q.io.enq.ready
  )

  val hasbits_chunk_end = fieldno_offset_from_tail === num_fields_this_hasbits_minus_one
  val hasbit_for_current_fieldno = (io.l2helperUser1.resp.bits.data(hasbits_resp_fieldno % 32.U) === 1.U) || (hasbits_resp_fieldno === 0.U)

  val is_submessage_bit_for_current_fieldno = is_submessage_current_value(hasbits_resp_fieldno % 32.U) === 1.U

  val current_field_is_present_and_submessage = hasbit_for_current_fieldno && is_submessage_bit_for_current_fieldno

  val hasbits_done_chunk = hasbits_chunk_end || current_field_is_present_and_submessage

  descr_request_Q.io.enq.bits.is_submessage := is_submessage_bit_for_current_fieldno

  switch (hasBitsCONSUMERState) {
    is (s_hasBitsCONSUMER_AcceptIsSubmessage) {
      io.l2helperUser1.resp.ready := (hasBitsLoaderState === s_hasBitsLoader_WaitToAdvance) && hasbits_request_meta_Q.io.deq.valid
      when (io.l2helperUser1.resp.fire()) {
        val internal_max_index = hasbits_request_meta_Q.io.deq.bits.has_bits_max_bitoffset % 32.U
        val truncate_shamt = 31.U - internal_max_index
        val is_submessage_value_resp_partial = Wire(UInt(width=32.W))
        is_submessage_value_resp_partial := (io.l2helperUser1.resp.bits.data << truncate_shamt)(31, 0)
        val is_submessage_value_resp = (is_submessage_value_resp_partial >> truncate_shamt) | 0.U(32.W)
        ProtoaccLogger.logInfo("[serdescriptor] to release frontend to advance, considering bits (max:%d, min:%d), shamt was: %d, observed bitfield: 0x%x\n",
          internal_max_index, 0.U, truncate_shamt, is_submessage_value_resp)

        when (is_submessage_value_resp === 0.U) {
          ProtoaccLogger.logInfo("[serdescriptor] Releasing frontend to advance.\n")
          ADVANCE_OK := true.B
          already_issued_ADVANCE_OK := true.B
        } .otherwise {
          ProtoaccLogger.logInfo("[serdescriptor] Not releasing frontend to advance due to potential submessage presence.\n")
        }
        is_submessage_current_value := is_submessage_value_resp
        hasBitsCONSUMERState := s_hasBitsCONSUMER_AcceptHasBitsAndIterate
      }
    }

    is (s_hasBitsCONSUMER_AcceptHasBitsAndIterate) {
      io.l2helperUser1.resp.ready := do_hasbits_to_descr.fire(io.l2helperUser1.resp.valid) && hasbits_done_chunk
      hasbits_request_meta_Q.io.deq.ready := do_hasbits_to_descr.fire(hasbits_request_meta_Q.io.deq.valid) && hasbits_done_chunk
      descr_request_Q.io.enq.valid := do_hasbits_to_descr.fire(descr_request_Q.io.enq.ready) && hasbit_for_current_fieldno

      when (do_hasbits_to_descr.fire()) {
        val no_present_submessages = (is_submessage_current_value & io.l2helperUser1.resp.bits.data) === 0.U
        when (no_present_submessages && !already_issued_ADVANCE_OK) {
          ProtoaccLogger.logInfo("[serdescriptor] Releasing frontend to advance: chunk of message contains submessages, but none are present.\n")
          ADVANCE_OK := true.B
          when (!hasbits_chunk_end) {
            already_issued_ADVANCE_OK := true.B
          }
        }

        ProtoaccLogger.logInfo("[serdescriptor] got hasbits value, relative_fieldno %d, this_hasbit %d, full_hasbitsfield 0x%x, this_submessagebit %d, full_submessagebitsfield 0x%x\n",
          descr_request_Q.io.enq.bits.relative_fieldno,
          hasbit_for_current_fieldno,
          io.l2helperUser1.resp.bits.data,
          is_submessage_bit_for_current_fieldno,
          is_submessage_current_value)

        when (hasbits_done_chunk) {
          fieldno_offset_from_tail := 0.U
          hasBitsCONSUMERState := s_hasBitsCONSUMER_AcceptIsSubmessage
          already_issued_ADVANCE_OK := false.B
        } .otherwise {
          fieldno_offset_from_tail := fieldno_offset_from_tail + 1.U
        }
      }

    }
  }



  val regular_response_path = Module(new Queue(Bool(), 10))
  regular_response_path.io.enq.bits := false.B

  val descriptor_req_meta_Q = Module(new Queue(new DescrRequestUopBundle, 4))
  descriptor_req_meta_Q.io.enq.bits := descr_request_Q.io.deq.bits


  val sWaitForRequest = 0.U(6.W)
  val sWaitForSubmADT = 1.U(6.W)
  val sIssueCPPObjAddrReq = 2.U(6.W)
  val sIssueADTHeaderReq = 3.U(6.W)
  val sAcceptCPPObjAddr = 4.U(6.W)
  val sAcceptADTHeaderReq = 5.U(6.W)
  val deserLoaderState = RegInit(sWaitForRequest)



  val des_load_des_addr = descr_request_Q.io.deq.bits.descriptor_table_addr
  val des_load_fieldno = descr_request_Q.io.deq.bits.relative_fieldno
  val descr_addr = des_load_des_addr + 16.U + (des_load_fieldno << 4)

  io.l2helperUser2.req.bits.addr := 0.U
  io.l2helperUser2.req.bits.size := 0.U
  io.l2helperUser2.req.valid := false.B
  descriptor_req_meta_Q.io.enq.valid := false.B
  descr_request_Q.io.deq.ready := false.B


  val all_resp_states = (deserLoaderState === sWaitForSubmADT) ||
                        (deserLoaderState === sAcceptCPPObjAddr) ||
                        (deserLoaderState === sAcceptADTHeaderReq)

  val all_req_states = (deserLoaderState === sWaitForRequest) ||
                       (deserLoaderState === sIssueCPPObjAddrReq) ||
                       (deserLoaderState === sIssueADTHeaderReq)

  val do_descr_request_regular = DecoupledHelper(
    io.l2helperUser2.req.ready,
    descriptor_req_meta_Q.io.enq.ready,
    descr_request_Q.io.deq.valid,
    regular_response_path.io.enq.ready,
    !descr_request_Q.io.deq.bits.is_submessage
  )

  val do_descr_request_frontside = DecoupledHelper(
    io.l2helperUser2.req.ready,
    descr_request_Q.io.deq.valid,
    regular_response_path.io.enq.ready,
    all_req_states,
    descr_request_Q.io.deq.bits.is_submessage
  )

  val do_descr_resp_frontside = DecoupledHelper(
    io.l2helperUser2.resp.valid,
    descr_request_Q.io.deq.valid,
    regular_response_path.io.deq.valid,
    !regular_response_path.io.deq.bits,
    all_resp_states
  )


  regular_response_path.io.enq.bits := !descr_request_Q.io.deq.bits.is_submessage
  io.l2helperUser2.req.valid :=
    do_descr_request_frontside.fire(io.l2helperUser2.req.ready) ||
    do_descr_request_regular.fire(io.l2helperUser2.req.ready)
  regular_response_path.io.enq.valid :=
    do_descr_request_frontside.fire(regular_response_path.io.enq.ready) ||
    do_descr_request_regular.fire(regular_response_path.io.enq.ready)

  descriptor_req_meta_Q.io.enq.valid := do_descr_request_regular.fire(descriptor_req_meta_Q.io.enq.ready)

  descr_request_Q.io.deq.ready := do_descr_request_regular.fire(descr_request_Q.io.deq.valid) ||
        (
          do_descr_resp_frontside.fire(descr_request_Q.io.deq.valid) &&
          (deserLoaderState === sAcceptADTHeaderReq)
        )

  val descr_result_offset = io.l2helperUser2.resp.bits.data(57, 0)
  val descr_result_typeinfo = io.l2helperUser2.resp.bits.data(62, 58)
  val descr_result_is_nested = descr_result_typeinfo === PROTO_TYPES.TYPE_MESSAGE
  val descr_result_is_repeated = io.l2helperUser2.resp.bits.data(63)
  val descr_result_nested_descriptor_ptr = io.l2helperUser2.resp.bits.data(127, 64)


  val saved_ADT_offset = RegInit(0.U(64.W))
  val saved_ADT_typeinfo = RegInit(0.U(5.W))
  val saved_ADT_is_repeated = RegInit(0.U(1.W))
  val saved_ADT_nested_descriptor_ptr = RegInit(0.U(64.W))
  val saved_next_CPP_object_base = RegInit(0.U(64.W))


  switch (deserLoaderState) {
    is (sWaitForRequest) {
      io.l2helperUser2.req.bits.addr := descr_addr
      when (descr_request_Q.io.deq.bits.is_submessage) {
        io.l2helperUser2.req.bits.size := 4.U
        when (do_descr_request_frontside.fire()) {
          deserLoaderState := sWaitForSubmADT
          ProtoaccLogger.logInfo("[serdescriptor] loading submessage descr, relative_fieldno %d, descraddr 0x%x, descrbase 0x%x, loadsize %d\n",
            des_load_fieldno,
            descr_addr,
            des_load_des_addr,
            io.l2helperUser2.req.bits.size)
        }
      } .otherwise {
        io.l2helperUser2.req.bits.size := 3.U
        when (do_descr_request_regular.fire()) {
          ProtoaccLogger.logInfo("[serdescriptor] loading non-submessage descr, relative_fieldno %d, descraddr 0x%x, descrbase 0x%x, loadsize %d\n",
            des_load_fieldno,
            descr_addr,
            des_load_des_addr,
            io.l2helperUser2.req.bits.size)
        }
      }
    }

    is (sWaitForSubmADT) {
      when (do_descr_resp_frontside.fire()) {
        ProtoaccLogger.logInfo("[serdescriptor] got-submessage-field ADT entry: 0x%x. cpp_offset: 0x%x, typeinfo: 0x%x, is_repeated: 0x%x, nested_descr_ptr: 0x%x\n",
          io.l2helperUser2.resp.bits.data,
          descr_result_offset,
          descr_result_typeinfo,
          descr_result_is_repeated,
          descr_result_nested_descriptor_ptr)

        saved_ADT_offset := descr_result_offset
        saved_ADT_typeinfo := descr_result_typeinfo
        saved_ADT_is_repeated := descr_result_is_repeated
        saved_ADT_nested_descriptor_ptr := descr_result_nested_descriptor_ptr

        deserLoaderState := sIssueCPPObjAddrReq
      }
    }
    is (sIssueCPPObjAddrReq) {
      io.l2helperUser2.req.bits.addr := saved_ADT_offset + descr_request_Q.io.deq.bits.cpp_obj_addr
      io.l2helperUser2.req.bits.size := 3.U
      when (do_descr_request_frontside.fire()) {
        ProtoaccLogger.logInfo("[serdescriptor] loading submessage cpp obj addr from addr 0x%x\n", io.l2helperUser2.req.bits.addr)
        deserLoaderState := sIssueADTHeaderReq
      }
    }
    is (sIssueADTHeaderReq) {
      io.l2helperUser2.req.bits.addr := saved_ADT_nested_descriptor_ptr + 16.U
      io.l2helperUser2.req.bits.size := 4.U
      when (do_descr_request_frontside.fire()) {
        ProtoaccLogger.logInfo("[serdescriptor] loading submessage ADT header chunk from addr 0x%x\n", io.l2helperUser2.req.bits.addr)
        deserLoaderState := sAcceptCPPObjAddr
      }
    }
    is (sAcceptCPPObjAddr) {
      when (do_descr_resp_frontside.fire()) {
        saved_next_CPP_object_base := io.l2helperUser2.resp.bits.data
        deserLoaderState := sAcceptADTHeaderReq
        ProtoaccLogger.logInfo("[serdescriptor] got submessage cpp obj addr: 0x%x\n", io.l2helperUser2.resp.bits.data)
      }
    }
    is (sAcceptADTHeaderReq) {
      when (do_descr_resp_frontside.fire()) {
        ProtoaccLogger.logInfo("[serdescriptor] got ADT header data: 0x%x\n", io.l2helperUser2.resp.bits.data)

        val new_hasbits_offset = io.l2helperUser2.resp.bits.data(63, 0)
        val new_min_max_fieldnos = io.l2helperUser2.resp.bits.data(127, 64)
        val new_min_fieldno = new_min_max_fieldnos(63, 32)
        val new_max_fieldno = new_min_max_fieldnos(31, 0)

        hasBitsLoaderState := s_hasBitsLoader_IsSubmessageLoad
        deserLoaderState := sWaitForRequest
        val next_depth = depth + 1.U
        depth := next_depth
        stack_descr_table(next_depth) := saved_ADT_nested_descriptor_ptr
        stack_cpp_obj(next_depth) := saved_next_CPP_object_base
        stack_has_bits_base_offset_only(next_depth) := new_hasbits_offset
        val current_update_bitoffset = descr_request_Q.io.deq.bits.relative_fieldno - 1.U
        stack_has_bits_next_bitoffset(depth) := current_update_bitoffset
        val new_next_bitoffset = (new_max_fieldno - new_min_fieldno) + 1.U
        stack_has_bits_next_bitoffset(next_depth) := new_next_bitoffset
        stack_min_fieldno(next_depth) := new_min_fieldno
        val new_is_submessage_base_addr = (((new_max_fieldno - new_min_fieldno) + 1.U) << 4) + 32.U + saved_ADT_nested_descriptor_ptr
        stack_is_submessage_base_addr(next_depth) := new_is_submessage_base_addr

        ProtoaccLogger.logInfo("[serdescriptor] UPDATING STACKS: current_depth %d, new_depth %d, new_descr_table 0x%x, new_cpp_obj 0x%x, new_hasbits_offset 0x%x, current_update_next_bitoffset %d, new_next_bitoffset %d, new_min_fieldno %d, new_is_submessage_base_addr 0x%x\n",
          depth,
          next_depth,
          saved_ADT_nested_descriptor_ptr,
          saved_next_CPP_object_base,
          new_hasbits_offset,
          current_update_bitoffset,
          new_next_bitoffset,
          new_min_fieldno,
          new_is_submessage_base_addr
        )

      }
    }

  }



  val serFieldHandlerOutput_Q = Module(new Queue(new DescrToHandlerBundle, 4))
  io.ser_field_handler_output <> serFieldHandlerOutput_Q.io.deq


  val do_descr_response = DecoupledHelper(
    io.l2helperUser2.resp.valid,
    descriptor_req_meta_Q.io.deq.valid,
    serFieldHandlerOutput_Q.io.enq.ready,
    regular_response_path.io.deq.valid,
    regular_response_path.io.deq.bits
  )
  descriptor_req_meta_Q.io.deq.ready := do_descr_response.fire(descriptor_req_meta_Q.io.deq.valid)
  serFieldHandlerOutput_Q.io.enq.valid := do_descr_response.fire(serFieldHandlerOutput_Q.io.enq.ready)

  regular_response_path.io.deq.ready := do_descr_response.fire(regular_response_path.io.deq.valid) || do_descr_resp_frontside.fire(regular_response_path.io.deq.valid)
  io.l2helperUser2.resp.ready := do_descr_response.fire(io.l2helperUser2.resp.valid) || do_descr_resp_frontside.fire(io.l2helperUser2.resp.valid)

  val des_res_current_cpp_obj = descriptor_req_meta_Q.io.deq.bits.cpp_obj_addr

  val use_depth = descriptor_req_meta_Q.io.deq.bits.depth

  when (descriptor_req_meta_Q.io.deq.bits.relative_fieldno =/= 0.U) {
    serFieldHandlerOutput_Q.io.enq.bits.src_data_addr := des_res_current_cpp_obj + descr_result_offset
    serFieldHandlerOutput_Q.io.enq.bits.src_data_type := descr_result_typeinfo
    serFieldHandlerOutput_Q.io.enq.bits.is_repeated := descr_result_is_repeated
    val real_fieldnumber = (descriptor_req_meta_Q.io.deq.bits.relative_fieldno + descriptor_req_meta_Q.io.deq.bits.min_fieldno) - 1.U
    serFieldHandlerOutput_Q.io.enq.bits.field_number := real_fieldnumber
    serFieldHandlerOutput_Q.io.enq.bits.depth := use_depth
    serFieldHandlerOutput_Q.io.enq.bits.end_of_message := false.B
  } .otherwise {
    serFieldHandlerOutput_Q.io.enq.bits.src_data_addr := 0.U
    serFieldHandlerOutput_Q.io.enq.bits.src_data_type := PROTO_TYPES.TYPE_MESSAGE
    serFieldHandlerOutput_Q.io.enq.bits.is_repeated := 0.U
    val use_parent_fieldnum = descriptor_req_meta_Q.io.deq.bits.parent_fieldnum
    serFieldHandlerOutput_Q.io.enq.bits.field_number := use_parent_fieldnum


    serFieldHandlerOutput_Q.io.enq.bits.depth := use_depth
    serFieldHandlerOutput_Q.io.enq.bits.end_of_message := true.B

    when (do_descr_response.fire()) {
      ProtoaccLogger.logInfo("[serdescriptor] EOM command. dispatched to fieldhandler: field_num of ending message: %d, depth %d\n",
        serFieldHandlerOutput_Q.io.enq.bits.field_number,
        serFieldHandlerOutput_Q.io.enq.bits.depth
      )
    }
  }

  when (do_descr_response.fire()) {
    ProtoaccLogger.logInfo("[serdescriptor] got descr entry. dispatched to fieldhandler: typeinfo %d, offset 0x%x, is_nested %d, is_repeated %d, nested_descriptor_ptr 0x%x\n",
      descr_result_typeinfo,
      descr_result_offset,
      descr_result_is_nested,
      descr_result_is_repeated,
      descr_result_nested_descriptor_ptr)
  }

}

