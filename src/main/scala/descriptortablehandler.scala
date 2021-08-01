package protoacc

import Chisel._
import chisel3.{Printable, VecInit}
import freechips.rocketchip.tile._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants


object WIRE_TYPES {
  val WIRE_TYPE_VARINT = 0.U
  val WIRE_TYPE_64bit = 1.U
  val WIRE_TYPE_LEN_DELIM = 2.U
  val WIRE_TYPE_START_GROUP = 3.U
  val WIRE_TYPE_END_GROUP = 4.U
  val WIRE_TYPE_32bit = 5.U
}


object PROTO_TYPES {
  val TYPE_DOUBLE = 1.U
  val TYPE_FLOAT = 2.U
  val TYPE_INT64 = 3.U
  val TYPE_UINT64 = 4.U
  val TYPE_INT32 = 5.U
  val TYPE_FIXED64 = 6.U
  val TYPE_FIXED32 = 7.U
  val TYPE_BOOL = 8.U
  val TYPE_STRING = 9.U
  val TYPE_GROUP = 10.U
  val TYPE_MESSAGE = 11.U

  val TYPE_BYTES = 12.U
  val TYPE_UINT32 = 13.U

  val TYPE_ENUM = 14.U
  val TYPE_SFIXED32 = 15.U
  val TYPE_SFIXED64 = 16.U
  val TYPE_SINT32 = 17.U
  val TYPE_SINT64 = 18.U

  val TYPE_fieldwidth = 5.W



  def detailedTypeToWireType(detailedType: UInt): UInt = {
    val wire_type_lookup = VecInit(
      WIRE_TYPES.WIRE_TYPE_VARINT,
      WIRE_TYPES.WIRE_TYPE_64bit,
      WIRE_TYPES.WIRE_TYPE_32bit,
      WIRE_TYPES.WIRE_TYPE_VARINT,
      WIRE_TYPES.WIRE_TYPE_VARINT,
      WIRE_TYPES.WIRE_TYPE_VARINT,
      WIRE_TYPES.WIRE_TYPE_64bit,
      WIRE_TYPES.WIRE_TYPE_32bit,
      WIRE_TYPES.WIRE_TYPE_VARINT,
      WIRE_TYPES.WIRE_TYPE_LEN_DELIM,
      WIRE_TYPES.WIRE_TYPE_START_GROUP,
      WIRE_TYPES.WIRE_TYPE_LEN_DELIM,
      WIRE_TYPES.WIRE_TYPE_LEN_DELIM,
      WIRE_TYPES.WIRE_TYPE_VARINT,
      WIRE_TYPES.WIRE_TYPE_VARINT,
      WIRE_TYPES.WIRE_TYPE_32bit,
      WIRE_TYPES.WIRE_TYPE_64bit,
      WIRE_TYPES.WIRE_TYPE_VARINT,
      WIRE_TYPES.WIRE_TYPE_VARINT,
    )
    wire_type_lookup(detailedType)
  }

  def detailedTypeToCppSizeLog2(detailedType: UInt): UInt =  {
    val cpp_size = VecInit(
        0.U,
      3.U,
      2.U,
      3.U,
      3.U,
      2.U,
      3.U,
      2.U,
      0.U,
      3.U,
        0.U,
      3.U,
      3.U,
      2.U,
      2.U,
      2.U,
      3.U,
      2.U,
      3.U,
    )
    cpp_size(detailedType)
  }


  def detailedTypeIsVarintSigned(detailedType: UInt): Bool = {
    val varint_is_signed = VecInit(
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      false.B,
      true.B,
      true.B,
    )
    varint_is_signed(detailedType)
  }


  def detailedTypeIsPotentiallyScalar(detailedType: UInt): Bool = {
    val varint_is_signed = VecInit(
      false.B,
      true.B,
      true.B,
      true.B,
      true.B,
      true.B,
      true.B,
      true.B,
      true.B,
      false.B,
      false.B,
      false.B,
      false.B,
      true.B,
      true.B,
      true.B,
      true.B,
      true.B,
      true.B,
    )
    varint_is_signed(detailedType)
  }

 val _lookup = VecInit(

0.U,
0.U,
0.U,
0.U,
0.U,
0.U,
0.U,
0.U,
0.U,
0.U,
0.U,
0.U,
0.U,
0.U,
0.U,
0.U,
0.U,
0.U,
0.U,
    )





}

class ExtraMetaResponse extends Bundle {
  val extra_meta0 = UInt(OUTPUT, 64.W)
  val extra_meta1 = UInt(OUTPUT, 64.W)
}

class DescriptorRequest extends Bundle {
  val proto_addr = UInt(OUTPUT, 64.W)
  val relative_field_no = UInt(OUTPUT, 64.W)
  val base_info_ptr = UInt(OUTPUT, 64.W)
}

class DescriptorResponse extends Bundle {
  val proto_addr = UInt(OUTPUT, 64.W)
  val relative_field_no = UInt(OUTPUT, 64.W)


  val is_repeated = Bool(OUTPUT)
  val proto_field_type = UInt(OUTPUT, PROTO_TYPES.TYPE_fieldwidth)
  val write_addr = UInt(OUTPUT, 64.W)
}

class DescriptorResponseExtra extends Bundle {
  val unpacked_repeated = Bool()
  val is_repeated_ptr_field = Bool()

  val ptr_to_repeated_field = UInt(64.W)

  val ptr_to_repeated_field_sizes = UInt(64.W)
  val ptr_to_repeated_field_elems = UInt(64.W)

  val ptr_to_repeated_ptr_field_sizes = UInt(64.W)
  val ptr_to_repeated_ptr_field_rep = UInt(64.W)

}

class DescriptorTableHandler()(implicit p: Parameters) extends Module
  with MemoryOpConstants {
  val io = IO(new Bundle {
    val field_dest_request = Decoupled(new DescriptorRequest).flip
    val field_dest_response = Decoupled(new DescriptorResponse)
    val extra_meta_response = Decoupled(new ExtraMetaResponse)
    val l1helperUser = new L1MemHelperBundle
  })

  val l1reqQueue = Module(new Queue(new L1ReqInternal, 4))
  io.l1helperUser.req <> l1reqQueue.io.deq

  val FDR_queue = Module(new Queue(new DescriptorRequest, 4))
  FDR_queue.io.enq <> io.field_dest_request


  l1reqQueue.io.enq.bits.cmd := M_XRD

  val fieldDestResponseQueue = Module(new Queue(new DescriptorResponse, 4))
  io.field_dest_response <> fieldDestResponseQueue.io.deq

  val extraMetaResponseQueue = Module(new Queue(new ExtraMetaResponse, 4))
  io.extra_meta_response <> extraMetaResponseQueue.io.deq

  fieldDestResponseQueue.io.enq.bits.proto_addr := FDR_queue.io.deq.bits.proto_addr
  fieldDestResponseQueue.io.enq.bits.relative_field_no := FDR_queue.io.deq.bits.relative_field_no

  fieldDestResponseQueue.io.enq.bits.write_addr := io.l1helperUser.resp.bits.data(57, 0) + FDR_queue.io.deq.bits.proto_addr
  fieldDestResponseQueue.io.enq.bits.proto_field_type := io.l1helperUser.resp.bits.data(62, 58)
  fieldDestResponseQueue.io.enq.bits.is_repeated := io.l1helperUser.resp.bits.data(63)

  l1reqQueue.io.enq.bits.size := log2Ceil(8).U
  l1reqQueue.io.enq.bits.data := Bits(0)

  l1reqQueue.io.enq.bits.addr := FDR_queue.io.deq.bits.base_info_ptr + (FDR_queue.io.deq.bits.relative_field_no << 4) + 32.U

  val request_outstanding = RegInit(Bool(false))
  val no_request_outstanding = !request_outstanding

  val fire_request = DecoupledHelper(
    FDR_queue.io.deq.valid,
    l1reqQueue.io.enq.ready,
    no_request_outstanding
  )

  l1reqQueue.io.enq.valid := fire_request.fire(l1reqQueue.io.enq.ready)

  val fire_response = DecoupledHelper(
    FDR_queue.io.deq.valid,
    io.l1helperUser.resp.valid,
    fieldDestResponseQueue.io.enq.ready
  )

  when (io.field_dest_response.valid) {
    ProtoaccLogger.logInfo("dest resp: %x\n", io.field_dest_response.bits.write_addr)
  }

  FDR_queue.io.deq.ready := fire_response.fire(FDR_queue.io.deq.valid)
  fieldDestResponseQueue.io.enq.valid := fire_response.fire(fieldDestResponseQueue.io.enq.ready)
  io.l1helperUser.resp.ready := fire_response.fire(io.l1helperUser.resp.valid)

  when (fire_request.fire()) {
    request_outstanding := Bool(true)
  }

  val last_descriptor_request = Reg(new DescriptorRequest)

  when (fire_response.fire()) {
    request_outstanding := Bool(false)
  }

  val sKickOffExtraRequests = UInt(0)
  val sGetNextDescriptor = UInt(1)
  val sDescriptorRespWait = UInt(2)
  val sGetVPtr = UInt(3)
  val sVPtrWait = UInt(4)
  val sGetAllocSize = UInt(5)
  val sAllocSizeWait = UInt(6)
  val extraRequestsMode = RegInit(sKickOffExtraRequests)

  extraMetaResponseQueue.io.enq.bits.extra_meta0 := io.l1helperUser.resp.bits.data(63, 0)
  extraMetaResponseQueue.io.enq.bits.extra_meta1 := io.l1helperUser.resp.bits.data >> 64

  extraMetaResponseQueue.io.enq.valid := Bool(false)

  val saved_next_descriptor = RegInit(0.U(64.W))

  switch (extraRequestsMode) {
    is (sKickOffExtraRequests) {
      when (fire_response.fire()) {
        when (fieldDestResponseQueue.io.enq.bits.proto_field_type === PROTO_TYPES.TYPE_MESSAGE) {
          extraRequestsMode := sGetNextDescriptor
          last_descriptor_request := FDR_queue.io.deq.bits
        }
      }
    }
    is (sGetNextDescriptor) {
      l1reqQueue.io.enq.bits.addr := last_descriptor_request.base_info_ptr + 32.U + ((last_descriptor_request.relative_field_no << 4) | UInt(8))
      l1reqQueue.io.enq.valid := Bool(true)
      when (l1reqQueue.io.enq.ready) {
        extraRequestsMode := sDescriptorRespWait
      }
    }
    is (sDescriptorRespWait) {
      when (io.l1helperUser.resp.valid) {
        ProtoaccLogger.logInfo("Got Next Descriptor Table Addr: 0x%x\n", io.l1helperUser.resp.bits.data)
        extraRequestsMode := sGetVPtr
        saved_next_descriptor := io.l1helperUser.resp.bits.data
        extraMetaResponseQueue.io.enq.valid := Bool(true)
      }
      io.l1helperUser.resp.ready := Bool(true)
    }
    is (sGetVPtr) {
      l1reqQueue.io.enq.bits.addr := saved_next_descriptor
      l1reqQueue.io.enq.valid := Bool(true)
      when (l1reqQueue.io.enq.ready) {
        extraRequestsMode := sVPtrWait
      }
      l1reqQueue.io.enq.bits.size := log2Ceil(16).U
    }
    is (sVPtrWait) {
      when (io.l1helperUser.resp.valid) {
        val nested_vptr = io.l1helperUser.resp.bits.data(63, 0)
        val nested_size = io.l1helperUser.resp.bits.data >> 64
        ProtoaccLogger.logInfo("Got Nested VPtr: 0x%x\n", nested_vptr)
        ProtoaccLogger.logInfo("Got Nested Size: 0x%x\n", nested_size)
        extraRequestsMode := sGetAllocSize
        saved_next_descriptor := saved_next_descriptor + UInt(16)
        extraMetaResponseQueue.io.enq.valid := Bool(true)
      }
      io.l1helperUser.resp.ready := Bool(true)
    }
    is (sGetAllocSize) {
      l1reqQueue.io.enq.bits.addr := saved_next_descriptor
      l1reqQueue.io.enq.valid := Bool(true)
      when (l1reqQueue.io.enq.ready) {
        extraRequestsMode := sAllocSizeWait
      }
      l1reqQueue.io.enq.bits.size := log2Ceil(16).U
    }
    is (sAllocSizeWait) {
      when (io.l1helperUser.resp.valid) {
        val hasbits_raw_offset = io.l1helperUser.resp.bits.data(63, 0)
        val min_max_fieldno = io.l1helperUser.resp.bits.data >> 64
        ProtoaccLogger.logInfo("Got Nested hasbits_raw_offset: 0x%x\n", hasbits_raw_offset)
        ProtoaccLogger.logInfo("Got Nested min/max fieldno: 0x%x\n", min_max_fieldno)

        extraRequestsMode := sKickOffExtraRequests
        extraMetaResponseQueue.io.enq.valid := Bool(true)
      }
      io.l1helperUser.resp.ready := Bool(true)
    }
  }
}


