package protoacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._


class WriterBundle extends Bundle {
  val data = UInt(128.W)
  val last_for_arbitration_round = Bool()
  val validbytes = UInt(6.W)
  val depth = UInt(ProtoaccParams.MAX_NESTED_LEVELS_WIDTH.W)
  val end_of_message = Bool()
}

class SerFieldHandler(logPrefix: String)(implicit p: Parameters) extends Module
  with MemoryOpConstants {


  val io = IO(new Bundle {
    val ops_in = Decoupled(new DescrToHandlerBundle).flip
    val memread = new L1MemHelperBundle

    val writer_output = Decoupled(new WriterBundle)
  })

  val outputQ = Module(new Queue(new WriterBundle, 4))
  io.writer_output <> outputQ.io.deq
  outputQ.io.enq.valid := false.B

  io.memread.req.valid := false.B
  io.memread.req.bits.cmd := M_XRD
  io.memread.resp.ready := false.B
  io.ops_in.ready := false.B

  val is_varint_signed = PROTO_TYPES.detailedTypeIsVarintSigned(io.ops_in.bits.src_data_type)
  val is_int32 = io.ops_in.bits.src_data_type === PROTO_TYPES.TYPE_INT32
  val cpp_size_log2 =  PROTO_TYPES.detailedTypeToCppSizeLog2(io.ops_in.bits.src_data_type)
  val wire_type = PROTO_TYPES.detailedTypeToWireType(io.ops_in.bits.src_data_type)
  val detailedTypeIsPotentiallyScalar = PROTO_TYPES.detailedTypeIsPotentiallyScalar(io.ops_in.bits.src_data_type)
  val is_bytes_or_string = (io.ops_in.bits.src_data_type === PROTO_TYPES.TYPE_STRING) || (io.ops_in.bits.src_data_type === PROTO_TYPES.TYPE_BYTES)
  val is_repeated = io.ops_in.bits.is_repeated
  val is_packed = false.B

  val is_varint_signed_reg = RegInit(false.B)
  val is_int32_reg = RegInit(false.B)
  val cpp_size_log2_reg = RegInit(UInt(0, 3.W))
  val cpp_size_nonlog2_fromreg = 1.U << cpp_size_log2_reg
  val cpp_size_nonlog2_numbits_fromreg = cpp_size_nonlog2_fromreg << 3
  val wire_type_reg = RegInit(UInt(0, log2Up(5).W))
  val detailedTypeIsPotentiallyScalar_reg = RegInit(false.B)

  val src_data_addr_reg = RegInit(0.U(64.W))

  val unencoded_key = Cat(io.ops_in.bits.field_number, wire_type(2, 0))
  val key_encoder = Module(new CombinationalVarintEncode)
  key_encoder.io.inputData := unencoded_key
  val encoded_key_reg = Reg(UInt())
  val encoded_key_bytes_reg = Reg(UInt())

  val varintDataUnsigned = Wire(Bool())
  val varintData64bit = Wire(Bool())
  varintDataUnsigned := !is_varint_signed_reg
  varintData64bit := cpp_size_log2_reg === 3.U

  val read_mask = Wire(UInt(128.W))
  read_mask := ((1.U << (cpp_size_nonlog2_numbits_fromreg)) - 1.U)


  val ORMASK = ((1.U(128.W) << 32) - 1.U) << 32
  val mem_resp_masked = io.memread.resp.bits.data & read_mask
  val maybe_extended_int32val = Mux(mem_resp_masked(31),
    ORMASK | mem_resp_masked,
    mem_resp_masked)
  val mem_resp_raw = Mux(is_int32_reg,
    maybe_extended_int32val,
    mem_resp_masked)
  val mem_resp_zigzag32 = (mem_resp_raw << 1) ^ Mux(mem_resp_raw(31), ~(0.U(32.W)), 0.U(32.W))
  val mem_resp_zigzag64 = (mem_resp_raw << 1) ^ Mux(mem_resp_raw(63), ~(0.U(64.W)), 0.U(64.W))

  val data_encoder = Module(new CombinationalVarintEncode)
  data_encoder.io.inputData := Mux(varintDataUnsigned,
                                          mem_resp_raw,
                                          Mux(varintData64bit,
                                            mem_resp_zigzag64,
                                            mem_resp_zigzag32)
  )

  val string_obj_ptr_reg = RegInit(0.U(64.W))
  val string_data_ptr_reg = RegInit(0.U(64.W))
  val string_length_no_null_term = RegInit(0.U(64.W))

  val encoded_string_length_no_null_term_reg = Reg(UInt())
  val encoded_string_length_no_null_term_bytes_reg = Reg(UInt())

  val base_addr_bytes_aligned_reg = RegInit(0.U(64.W))
  val words_to_load_reg = RegInit(0.U(64.W))
  val words_to_load_minus_one_reg = RegInit(0.U(64.W))
  val words_to_load_minus_one_reg_fixed = RegInit(0.U(64.W))
  val base_addr_start_index_reg = RegInit(0.U(log2Up(16+1).W))
  val base_addr_end_index_inclusive_reg = RegInit(0.U(log2Up(16+1).W))
  val base_addr_end_index_reg = RegInit(0.U(log2Up(16+1).W))

  val string_load_respcounter = RegInit(0.U(64.W))


  val repeated_elems_headptr = RegInit(0.U(64.W))


  val S_WAIT_CMD = 0.U
  val S_SCALAR_DISPATCH_REQ = 1.U
  val S_SCALAR_OUTPUT_DATA = 2.U
  val S_WRITE_KEY = 3.U

  val S_STRING_GETPTR = 4.U
  val S_STRING_GETHEADER1 = 5.U
  val S_STRING_GETHEADER2 = 6.U
  val S_STRING_RECVHEADER1 = 7.U
  val S_STRING_RECVHEADER2 = 8.U
  val S_STRING_LOADDATA = 9.U
  val S_STRING_WRITEKEY = 10.U


  val S_UNPACKED_REP_GETPTR = 11.U
  val S_UNPACKED_REP_GETSIZE = 12.U
  val S_UNPACKED_REP_RECVPTR = 13.U
  val S_UNPACKED_REP_RECVSIZE = 14.U


  val handlerState = RegInit(S_WAIT_CMD)

  switch (handlerState) {
    is (S_WAIT_CMD) {
      when (io.ops_in.bits.end_of_message === true.B) {

        outputQ.io.enq.valid := io.ops_in.valid

        outputQ.io.enq.bits.data := 0.U
        outputQ.io.enq.bits.validbytes := 0.U
        outputQ.io.enq.bits.depth := io.ops_in.bits.depth
        outputQ.io.enq.bits.end_of_message := true.B

        when (io.ops_in.bits.depth =/= 1.U) {
          outputQ.io.enq.bits.last_for_arbitration_round := false.B
          when (io.ops_in.valid && outputQ.io.enq.ready) {
            ProtoaccLogger.logInfo(logPrefix + " S_WAIT_CMD: EOM zerofield passthrough for submessage.\n")
            handlerState := S_WRITE_KEY

            encoded_key_reg := key_encoder.io.outputData
            encoded_key_bytes_reg := key_encoder.io.outputBytes
          }
        } .otherwise {
          io.ops_in.ready := outputQ.io.enq.ready
          outputQ.io.enq.bits.last_for_arbitration_round := true.B
          when (io.ops_in.valid && outputQ.io.enq.ready) {
            ProtoaccLogger.logInfo(logPrefix + " S_WAIT_CMD: EOM zerofield passthrough for top-level message.\n")
          }
        }

      } .elsewhen (io.ops_in.valid) {

        wire_type_reg := wire_type
        cpp_size_log2_reg := cpp_size_log2
        is_varint_signed_reg := is_varint_signed
        is_int32_reg := is_int32
        detailedTypeIsPotentiallyScalar_reg := detailedTypeIsPotentiallyScalar

        encoded_key_reg := key_encoder.io.outputData
        encoded_key_bytes_reg := key_encoder.io.outputBytes

        src_data_addr_reg := io.ops_in.bits.src_data_addr

        ProtoaccLogger.logInfo(logPrefix + " S_WAIT_CMD: accept op: src_data_addr 0x%x, src_data_type %d, is_repeated 0x%x, is_packed 0x%x, field_number %d, wire_type %d, cpp_size_log2 %d, is_varint_signed %d\n",
          Wire(io.ops_in.bits.src_data_addr),
          Wire(io.ops_in.bits.src_data_type),
          Wire(is_repeated),
          Wire(is_packed),
          Wire(io.ops_in.bits.field_number),
          Wire(wire_type),
          Wire(cpp_size_log2),
          Wire(is_varint_signed)
        )


        when (detailedTypeIsPotentiallyScalar && !is_repeated) {
          ProtoaccLogger.logInfo(logPrefix + " S_WAIT_CMD: moving to handle scalar\n")
          handlerState := S_SCALAR_DISPATCH_REQ
        } .elsewhen (is_bytes_or_string && !is_repeated) {
          ProtoaccLogger.logInfo(logPrefix + " S_WAIT_CMD: moving to handle string/bytes\n")
          handlerState := S_STRING_GETPTR
        } .elsewhen ((detailedTypeIsPotentiallyScalar || is_bytes_or_string) && is_repeated) {
          ProtoaccLogger.logInfo(logPrefix + " S_WAIT_CMD: moving to setup unpacked repeated\n")
          handlerState := S_UNPACKED_REP_GETPTR
        } .otherwise {
          assert(false.B, "not yet implemented")
        }

      }
    }

    is (S_SCALAR_DISPATCH_REQ) {
      ProtoaccLogger.logInfo(logPrefix + " S_SCALAR_DISPATCH_REQ: loading scalar\n")

      io.memread.req.bits.addr := src_data_addr_reg
      io.memread.req.bits.size := cpp_size_log2_reg
      io.memread.req.valid := true.B
      when (io.memread.req.ready) {
        ProtoaccLogger.logInfo(logPrefix + " S_SCALAR_DISPATCH_REQ: dispatched scalarload req: addr 0x%x, size 0x%x\n",
          io.memread.req.bits.addr,
          io.memread.req.bits.size)

        handlerState := S_SCALAR_OUTPUT_DATA
      }
    }

    is (S_SCALAR_OUTPUT_DATA) {
      io.memread.resp.ready := outputQ.io.enq.ready
      outputQ.io.enq.valid := io.memread.resp.valid

      outputQ.io.enq.bits.data := mem_resp_raw
      outputQ.io.enq.bits.last_for_arbitration_round := false.B
      outputQ.io.enq.bits.validbytes := cpp_size_nonlog2_fromreg
      outputQ.io.enq.bits.depth := io.ops_in.bits.depth
      outputQ.io.enq.bits.end_of_message := false.B

      when (wire_type_reg === WIRE_TYPES.WIRE_TYPE_VARINT) {
        outputQ.io.enq.bits.data := data_encoder.io.outputData
        outputQ.io.enq.bits.validbytes := data_encoder.io.outputBytes
      }

      when (io.memread.resp.valid && outputQ.io.enq.ready) {
        ProtoaccLogger.logInfo(logPrefix + " S_SCALAR_OUTPUT_DATA: loaded_val 0x%x, read_mask 0x%x, encoded_val 0x%x, output size bytes %d, last 0x%x\n",
          mem_resp_raw,
          read_mask,
          outputQ.io.enq.bits.data,
          outputQ.io.enq.bits.validbytes,
          outputQ.io.enq.bits.last_for_arbitration_round)

        when (!(is_repeated && is_packed)) {
          ProtoaccLogger.logInfo(logPrefix + " S_SCALAR_OUTPUT_DATA: nonrepeated or unpacked repeated\n")
          handlerState := S_WRITE_KEY
        } .otherwise {
          when (src_data_addr_reg === repeated_elems_headptr) {
            ProtoaccLogger.logInfo(logPrefix + " S_SCALAR_OUTPUT_DATA: packed repeated lastelem\n")

            repeated_elems_headptr := 0.U
            handlerState := S_WRITE_KEY
          } .otherwise {
            val nextptr = src_data_addr_reg - cpp_size_nonlog2_fromreg
            ProtoaccLogger.logInfo(logPrefix + " S_SCALAR_OUTPUT_DATA: packed repeated continue, nextptr: 0x%x\n",
              nextptr)
            src_data_addr_reg := nextptr
            handlerState := S_SCALAR_DISPATCH_REQ
          }
        }
      }
    }

    is (S_WRITE_KEY) {
      ProtoaccLogger.logInfo(logPrefix + " S_WRITE_KEY\n")


      outputQ.io.enq.bits.data := encoded_key_reg
      outputQ.io.enq.bits.validbytes := encoded_key_bytes_reg
      outputQ.io.enq.bits.depth := io.ops_in.bits.depth
      outputQ.io.enq.bits.end_of_message := io.ops_in.bits.end_of_message


      outputQ.io.enq.valid := true.B


      val is_unpacked_repeated = is_repeated && !is_packed
      when (outputQ.io.enq.ready) {
        when (!is_unpacked_repeated) {
          ProtoaccLogger.logInfo(logPrefix + " S_WRITE_KEY: nonrepeated\n")
          outputQ.io.enq.bits.last_for_arbitration_round := true.B
          handlerState := S_WAIT_CMD
          io.ops_in.ready := true.B
        } .otherwise {
          when (src_data_addr_reg === repeated_elems_headptr) {
            ProtoaccLogger.logInfo(logPrefix + " S_WRITE_KEY: unpacked repeated lastelem\n")

            repeated_elems_headptr := 0.U
            outputQ.io.enq.bits.last_for_arbitration_round := true.B
            handlerState := S_WAIT_CMD
            io.ops_in.ready := true.B
          } .otherwise {
            val nextptr = src_data_addr_reg - cpp_size_nonlog2_fromreg
            ProtoaccLogger.logInfo(logPrefix + " S_WRITE_KEY: unpacked repeated continue, nextptr: 0x%x\n",
              nextptr)
            src_data_addr_reg := nextptr
            outputQ.io.enq.bits.last_for_arbitration_round := false.B
            handlerState := S_SCALAR_DISPATCH_REQ
            io.ops_in.ready := false.B
          }
        }

        ProtoaccLogger.logInfo(logPrefix + " S_WRITE_KEY: encoded key 0x%x, key size %d, last 0x%x\n",
          outputQ.io.enq.bits.data,
          outputQ.io.enq.bits.validbytes,
          outputQ.io.enq.bits.last_for_arbitration_round)

      }
    }
    is (S_STRING_GETPTR) {
      ProtoaccLogger.logInfo(logPrefix + " S_STRING_GETPTR: loading stringptr\n")

      io.memread.req.bits.addr := src_data_addr_reg
      io.memread.req.bits.size := cpp_size_log2_reg
      io.memread.req.valid := true.B
      when (io.memread.req.ready) {
        ProtoaccLogger.logInfo(logPrefix + " S_STRING_GETPTR: getting string ptr from: addr 0x%x, size 0x%x\n",
          io.memread.req.bits.addr,
          io.memread.req.bits.size)

        handlerState := S_STRING_GETHEADER1
      }
    }
    is (S_STRING_GETHEADER1) {
      ProtoaccLogger.logInfo(logPrefix + " S_STRING_GETHEADER1: stringptr resp, header read1\n")

      io.memread.resp.ready := io.memread.req.ready
      io.memread.req.valid := io.memread.resp.valid

      io.memread.req.bits.addr := io.memread.resp.bits.data
      io.memread.req.bits.size := 3.U

      when (io.memread.resp.valid && io.memread.req.ready) {
        ProtoaccLogger.logInfo(logPrefix + " S_STRING_GETHEADER1: getting string header from: addr 0x%x, size 0x%x\n",
          io.memread.req.bits.addr,
          io.memread.req.bits.size)


        string_obj_ptr_reg := io.memread.resp.bits.data
        handlerState := S_STRING_GETHEADER2
      }
    }
    is (S_STRING_GETHEADER2) {
      ProtoaccLogger.logInfo(logPrefix + " S_STRING_GETHEADER2: get header pt 2\n")
      io.memread.req.valid := true.B
      io.memread.req.bits.addr := string_obj_ptr_reg + 8.U
      io.memread.req.bits.size := 3.U
      when (io.memread.req.ready) {
        ProtoaccLogger.logInfo(logPrefix + " S_STRING_GETHEADER2: getting string header pt.2 from: addr 0x%x, size 0x%x\n",
          io.memread.req.bits.addr,
          io.memread.req.bits.size)
        handlerState := S_STRING_RECVHEADER1
      }
    }
    is (S_STRING_RECVHEADER1) {
      ProtoaccLogger.logInfo(logPrefix + " S_STRING_RECVHEADER1: recv header pt 1\n")

      io.memread.resp.ready := true.B
      when (io.memread.resp.valid) {
        ProtoaccLogger.logInfo(logPrefix + " S_STRING_RECVHEADER1: got string header pt1 value: 0x%x\n",
          io.memread.resp.bits.data)

        string_data_ptr_reg := io.memread.resp.bits.data
        handlerState := S_STRING_RECVHEADER2
      }
    }
    is (S_STRING_RECVHEADER2) {
      io.memread.resp.ready := true.B
      ProtoaccLogger.logInfo(logPrefix + " S_STRING_RECVHEADER2: recv header pt 2\n")



      val base_addr_bytes = string_data_ptr_reg
      val base_len = io.memread.resp.bits.data
      val base_addr_start_index = base_addr_bytes & UInt(0xF)
      val aligned_loadlen = base_len + base_addr_start_index
      val base_addr_end_index = aligned_loadlen & UInt(0xF)
      val base_addr_end_index_inclusive = (aligned_loadlen - 1.U) & UInt(0xF)
      val extra_word = ((aligned_loadlen & UInt(0xF)) =/= UInt(0)).asUInt

      val base_addr_bytes_aligned = (base_addr_bytes >> 4) << 4
      val words_to_load = (aligned_loadlen >> 4) + extra_word
      val words_to_load_minus_one = words_to_load - 1.U

      val len_encoder = Module(new CombinationalVarintEncode)
      len_encoder.io.inputData := base_len

      when (io.memread.resp.valid) {
        ProtoaccLogger.logInfo(logPrefix + " S_STRING_RECVHEADER2: got string header pt2 value: 0x%x\n",
          io.memread.resp.bits.data)
        ProtoaccLogger.logInfo(logPrefix + "S_STRING_RECVHEADER2: base_addr_bytes: %x\n", base_addr_bytes)
        ProtoaccLogger.logInfo(logPrefix + "S_STRING_RECVHEADER2: base_len: %x\n", base_len)
        ProtoaccLogger.logInfo(logPrefix + "S_STRING_RECVHEADER2: base_addr_start_index: %x\n", base_addr_start_index)
        ProtoaccLogger.logInfo(logPrefix + "S_STRING_RECVHEADER2: aligned_loadlen: %x\n", aligned_loadlen)
        ProtoaccLogger.logInfo(logPrefix + "S_STRING_RECVHEADER2: base_addr_end_index: %x\n", base_addr_end_index)
        ProtoaccLogger.logInfo(logPrefix + "S_STRING_RECVHEADER2: base_addr_end_index_inclusive: %x\n", base_addr_end_index_inclusive)
        ProtoaccLogger.logInfo(logPrefix + "S_STRING_RECVHEADER2: extra_word: %x\n", extra_word)
        ProtoaccLogger.logInfo(logPrefix + "S_STRING_RECVHEADER2: base_addr_bytes_aligned: %x\n", base_addr_bytes_aligned)
        ProtoaccLogger.logInfo(logPrefix + "S_STRING_RECVHEADER2: words_to_load: %x\n", words_to_load)
        ProtoaccLogger.logInfo(logPrefix + "S_STRING_RECVHEADER2: words_to_load_minus_one: %x\n", words_to_load_minus_one)




        string_length_no_null_term := base_len
        encoded_string_length_no_null_term_reg := len_encoder.io.outputData
        encoded_string_length_no_null_term_bytes_reg := len_encoder.io.outputBytes


        base_addr_bytes_aligned_reg := base_addr_bytes_aligned
        words_to_load_reg := words_to_load
        words_to_load_minus_one_reg := words_to_load_minus_one
        words_to_load_minus_one_reg_fixed := words_to_load_minus_one

        base_addr_start_index_reg := base_addr_start_index
        base_addr_end_index_inclusive_reg := base_addr_end_index_inclusive
        base_addr_end_index_reg := base_addr_end_index


        handlerState := S_STRING_LOADDATA
      }

    }

    is (S_STRING_LOADDATA) {
      ProtoaccLogger.logInfo(logPrefix + " S_STRING_LOADDATA\n")

      io.memread.req.bits.addr := base_addr_bytes_aligned_reg + (words_to_load_minus_one_reg << 4)
      io.memread.req.valid := words_to_load_reg =/= 0.U
      io.memread.req.bits.size := 4.U
      when (words_to_load_reg =/= 0.U && io.memread.req.ready) {
        ProtoaccLogger.logInfo(logPrefix + " S_STRING_LOADDATA. doing load. addr 0x%x, size %d\n",
          io.memread.req.bits.addr, io.memread.req.bits.size)
        words_to_load_reg := words_to_load_reg - 1.U
        words_to_load_minus_one_reg := words_to_load_minus_one_reg - 1.U
      }

      val handlingtail = string_load_respcounter === 0.U
      val handlingfront = string_load_respcounter === words_to_load_minus_one_reg_fixed

      outputQ.io.enq.bits.data := Mux(handlingfront,
        io.memread.resp.bits.data >> (base_addr_start_index_reg << 3),
        io.memread.resp.bits.data)
      outputQ.io.enq.bits.last_for_arbitration_round := false.B
      when (handlingfront && handlingtail) {
        outputQ.io.enq.bits.validbytes := (base_addr_end_index_inclusive_reg - base_addr_start_index_reg) +& 1.U
      } .elsewhen (handlingtail) {
        outputQ.io.enq.bits.validbytes := (base_addr_end_index_inclusive_reg +& 1.U)
      } .elsewhen (handlingfront) {
        outputQ.io.enq.bits.validbytes := 16.U - base_addr_start_index_reg
      } .otherwise {
        outputQ.io.enq.bits.validbytes := 16.U
      }

      outputQ.io.enq.bits.depth := io.ops_in.bits.depth
      outputQ.io.enq.bits.end_of_message := false.B

      outputQ.io.enq.valid := io.memread.resp.valid
      io.memread.resp.ready := outputQ.io.enq.ready
      when (outputQ.io.enq.ready && io.memread.resp.valid) {
        ProtoaccLogger.logInfo(logPrefix + " S_STRING_LOADDATA. got resp. string_load_respcounter 0x%x, raw resp: 0x%x\n",
          string_load_respcounter, io.memread.resp.bits.data)
        ProtoaccLogger.logInfo(logPrefix + " S_STRING_LOADDATA. enq out. data 0x%x, last_for_arb 0x%x, validbytes %d, depth %d, end_of_message %d\n",
          outputQ.io.enq.bits.data,
          outputQ.io.enq.bits.last_for_arbitration_round,
          outputQ.io.enq.bits.validbytes,
          outputQ.io.enq.bits.depth,
          outputQ.io.enq.bits.end_of_message)

        when (handlingfront) {
          handlerState := S_STRING_WRITEKEY
          string_load_respcounter := 0.U
        } .otherwise {
          string_load_respcounter := string_load_respcounter + 1.U
        }
      }
    }
    is (S_STRING_WRITEKEY) {


      outputQ.io.enq.valid := true.B

      ProtoaccLogger.logInfo(logPrefix + " S_STRING_WRITEKEY encoded_key 0x%x, encoded_key_bytes 0x%x\n",
        encoded_key_reg,
        encoded_key_bytes_reg)
      ProtoaccLogger.logInfo(logPrefix + " S_STRING_WRITEKEY encoded_len 0x%x, encoded_len_bytes 0x%x\n",
        encoded_string_length_no_null_term_reg,
        encoded_string_length_no_null_term_bytes_reg)

      outputQ.io.enq.bits.data := encoded_key_reg | (encoded_string_length_no_null_term_reg << (encoded_key_bytes_reg << 3))
      outputQ.io.enq.bits.last_for_arbitration_round := true.B
      outputQ.io.enq.bits.validbytes := encoded_key_bytes_reg +& encoded_string_length_no_null_term_bytes_reg
      outputQ.io.enq.bits.depth := io.ops_in.bits.depth
      outputQ.io.enq.bits.end_of_message := false.B

      val is_unpacked_repeated = is_repeated && !is_packed
      when (outputQ.io.enq.ready) {
        ProtoaccLogger.logInfo(logPrefix + " S_STRING_WRITEKEY enq out. data 0x%x, last_for_arb 0x%x, validbytes %d, depth %d, end_of_message %d\n",
          outputQ.io.enq.bits.data,
          outputQ.io.enq.bits.last_for_arbitration_round,
          outputQ.io.enq.bits.validbytes,
          outputQ.io.enq.bits.depth,
          outputQ.io.enq.bits.end_of_message)

        when (!is_unpacked_repeated) {
          io.ops_in.ready := true.B
          handlerState := S_WAIT_CMD
        } .otherwise {
          when (src_data_addr_reg === repeated_elems_headptr) {
            ProtoaccLogger.logInfo(logPrefix + " S_STRING_WRITEKEY: unpacked repeated lastelem\n")

            repeated_elems_headptr := 0.U
            outputQ.io.enq.bits.last_for_arbitration_round := true.B
            handlerState := S_WAIT_CMD
            io.ops_in.ready := true.B
          } .otherwise {
            val nextptr = src_data_addr_reg - cpp_size_nonlog2_fromreg
            ProtoaccLogger.logInfo(logPrefix + " S_STRING_WRITEKEY: unpacked repeated continue, nextptr: 0x%x\n",
              nextptr)
            src_data_addr_reg := nextptr
            outputQ.io.enq.bits.last_for_arbitration_round := false.B
            handlerState := S_STRING_GETPTR
            io.ops_in.ready := false.B
          }
        }
      }

    }

    is (S_UNPACKED_REP_GETPTR) {
      ProtoaccLogger.logInfo(logPrefix + " S_UNPACKED_REP_GETPTR: req ptr to unpacked elems\n")

      io.memread.req.valid := true.B

      when (is_bytes_or_string) {
        io.memread.req.bits.addr := src_data_addr_reg + 8.U
      } .otherwise {
        io.memread.req.bits.addr := src_data_addr_reg
      }

      io.memread.req.bits.size := 3.U

      when (io.memread.req.ready) {
        ProtoaccLogger.logInfo(logPrefix + " S_UNPACKED_REP_GETPTR: req ptr to unpacked elems from: addr 0x%x, read size 0x%x\n",
          io.memread.req.bits.addr,
          io.memread.req.bits.size)
        handlerState := S_UNPACKED_REP_GETSIZE
      }
    }
    is (S_UNPACKED_REP_GETSIZE) {
      ProtoaccLogger.logInfo(logPrefix + " S_UNPACKED_REP_GETSIZE: req size of unpacked\n")

      io.memread.req.valid := true.B

      when (is_bytes_or_string) {
        io.memread.req.bits.addr := src_data_addr_reg
      } .otherwise {
        io.memread.req.bits.addr := src_data_addr_reg - 8.U
      }
      io.memread.req.bits.size := 3.U

      when (io.memread.req.ready) {
        ProtoaccLogger.logInfo(logPrefix + " S_UNPACKED_REP_GETSIZE: req size of unpacked from: addr 0x%x, read size 0x%x\n",
          io.memread.req.bits.addr,
          io.memread.req.bits.size)
        handlerState := S_UNPACKED_REP_RECVPTR
      }
    }

    is (S_UNPACKED_REP_RECVPTR) {
      ProtoaccLogger.logInfo(logPrefix + " S_UNPACKED_REP_RECVPTR: recv ptr to unpacked\n")

      io.memread.resp.ready := true.B
      when (io.memread.resp.valid) {
        ProtoaccLogger.logInfo(logPrefix + " S_UNPACKED_REP_RECVPTR: recv ptr to unpacked. got addr 0x%x\n",
          io.memread.resp.bits.data)
        when (is_bytes_or_string) {
          repeated_elems_headptr := io.memread.resp.bits.data + 8.U
          ProtoaccLogger.logInfo(logPrefix + " S_UNPACKED_REP_RECVPTR: ADJUSTED PTR FOR REPPTR FIELD. got addr 0x%x\n",
            io.memread.resp.bits.data + 8.U)
        } .otherwise {
          repeated_elems_headptr := io.memread.resp.bits.data
        }
        handlerState := S_UNPACKED_REP_RECVSIZE
      }
    }
    is (S_UNPACKED_REP_RECVSIZE) {

      ProtoaccLogger.logInfo(logPrefix + " S_UNPACKED_REP_RECVSIZE: recv size of unpacked\n")

      io.memread.resp.ready := true.B
      when (io.memread.resp.valid) {
        val num_elems = io.memread.resp.bits.data(31, 0)
        val ptr_to_last_elem = repeated_elems_headptr + ((num_elems - 1.U) << cpp_size_log2_reg)

        ProtoaccLogger.logInfo(logPrefix + " S_UNPACKED_REP_RECVSIZE: recv size of unpacked. got size (elems) %d, ptr to last elem is 0x%x\n",
          num_elems,
          ptr_to_last_elem)

        src_data_addr_reg := ptr_to_last_elem
        when (is_bytes_or_string) {
          handlerState := S_STRING_GETPTR
        } .otherwise {
          handlerState := S_SCALAR_DISPATCH_REQ
        }
      }
    }

  }

}
