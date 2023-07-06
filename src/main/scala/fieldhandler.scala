package protoacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.rocket.{RAS}



class FieldHandler()(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {

    val consumer = Flipped(new MemLoaderConsumerBundle)

    val l1helperUser = new L1MemHelperBundle

    val l1helperUser2 = new L1MemHelperBundle

    val fixed_writer_request = Decoupled(new FixedWriterRequest)

    val fixed_alloc_region_addr = Flipped(Valid(UInt(64.W)))
    val array_alloc_region_addr = Flipped(Valid(UInt(64.W)))

    val completed_toplevel_bufs = Output(UInt(64.W))
  })


  val completed_toplevel_bufs_reg = RegInit(0.U(64.W))
  io.completed_toplevel_bufs := completed_toplevel_bufs_reg

  val just_completed_buffer = RegInit(Bool(false))
  val last_consumer_transaction = (io.consumer.available_output_bytes === io.consumer.user_consumed_bytes) && io.consumer.output_last_chunk

  when (io.consumer.output_ready && io.consumer.output_valid) {
    when (last_consumer_transaction) {
      just_completed_buffer := Bool(true)
      val next_completed_toplevel_bufs_reg = completed_toplevel_bufs_reg + 1.U
      completed_toplevel_bufs_reg := next_completed_toplevel_bufs_reg
      ProtoaccLogger.logInfo("completed bufs: current 0x%x, next 0x%x\n",
        completed_toplevel_bufs_reg, next_completed_toplevel_bufs_reg)
    }
  }


  val processed_len_total = RegInit(0.U(64.W))

  val stacks_index = RegInit(0.U(ProtoaccParams.MAX_NESTED_LEVELS_WIDTH.W))
  assert(stacks_index < ProtoaccParams.MAX_NESTED_LEVELS.U, "FAIL. TOO MANY NESTED LEVELS")

  val out_addr_stack = Reg(Vec(ProtoaccParams.MAX_NESTED_LEVELS, UInt(64.W)))
  val hasbits_offset_stack = Reg(Vec(ProtoaccParams.MAX_NESTED_LEVELS, UInt(64.W)))
  val descr_table_stack = Reg(Vec(ProtoaccParams.MAX_NESTED_LEVELS, UInt(64.W)))
  val lens_table_stack = RegInit(Vec(Seq.fill(ProtoaccParams.MAX_NESTED_LEVELS)(0.U(64.W))))
  val min_field_no_stack = RegInit(Vec(Seq.fill(ProtoaccParams.MAX_NESTED_LEVELS)(0.U(32.W))))


  val default_hasbits_val = (0x10).U(64.W)

  val current_out_addr = Mux(stacks_index === 0.U,
    io.consumer.output_decoded_dest_base_addr,
    out_addr_stack(stacks_index))
  val current_hasbits_offset = Mux(stacks_index === 0.U,
    default_hasbits_val,
    hasbits_offset_stack(stacks_index))
  val current_descr_table = Mux(stacks_index === 0.U,
    io.consumer.output_ADT_addr,
    descr_table_stack(stacks_index))
  val current_min_field_no = Mux(stacks_index === 0.U,
    io.consumer.output_min_field_no,
    min_field_no_stack(stacks_index))
  val current_len = lens_table_stack(stacks_index)



  when (io.consumer.output_ready && io.consumer.output_valid) {
    when (last_consumer_transaction) {
      ProtoaccLogger.logInfo("NStack: Clearing\n")
      stacks_index := 0.U
      processed_len_total := 0.U
    } .otherwise {

      val next_processed_len_total = processed_len_total + io.consumer.user_consumed_bytes
      when (next_processed_len_total === current_len) {
        ProtoaccLogger.logInfo("NStack: Removing an entry.\n")
        stacks_index := stacks_index - 1.U
      }
      processed_len_total := next_processed_len_total
    }
  }


  val descriptor_table_address_user = current_descr_table
  val output_address_user = current_out_addr


  val combo_varint_module = Module(new CombinationalVarint)
  combo_varint_module.io.inputRawData := io.consumer.output_data
  val varintlen = Wire(UInt())
  varintlen := combo_varint_module.io.consumedLenBytes
  val varintresult = Wire(UInt())
  varintresult := combo_varint_module.io.outputData


  when (io.consumer.output_ready && io.consumer.output_valid) {
    ProtoaccLogger.logInfo("RAW DATA: %x, OUTPUT BYTES AVAIL: %x, DATA AS VARINT %x, VARINT LEN %x, BYTES CONSUMED %x\n",
      io.consumer.output_data,
      io.consumer.available_output_bytes,
      varintresult,
      varintlen,
      io.consumer.user_consumed_bytes)
  }


  val NUM_BITS_FOR_STATES = 4
  val sHandleVarint = 0.U(NUM_BITS_FOR_STATES.W)
  val sHandle64bit  = 1.U(NUM_BITS_FOR_STATES.W)
  val sHandleLengthDelim  = 2.U(NUM_BITS_FOR_STATES.W)
  val sHandleStartGroup  = 3.U(NUM_BITS_FOR_STATES.W)
  val sHandleEndGroup  = 4.U(NUM_BITS_FOR_STATES.W)
  val sHandle32bit  = 5.U(NUM_BITS_FOR_STATES.W)

  val sReadKey = 6.U(NUM_BITS_FOR_STATES.W)
  val sPrepState = 7.U(NUM_BITS_FOR_STATES.W)
  val sManageRepeatedAlloc = 8.U(NUM_BITS_FOR_STATES.W)
  val sEndBufCloseurcArray = 9.U(NUM_BITS_FOR_STATES.W)


  val fieldState = RegInit(sReadKey)
  val wireTypeReg = RegInit(0.U(NUM_BITS_FOR_STATES.W))
  val descriptor_responseReg = Reg(new DescriptorResponse)
  val descriptor_responseReg_extra = Reg(new DescriptorResponseExtra)

  io.consumer.user_consumed_bytes := UInt(0)
  io.consumer.output_ready := Bool(false)

  val field_no_reg = RegInit(UInt(0, 32.W))

  val descriptor_table_handler = Module(new DescriptorTableHandler)

  descriptor_table_handler.io.extra_meta_response.ready := Bool(false)

  io.l1helperUser <> descriptor_table_handler.io.l1helperUser

  val wire_type = varintresult & UInt(0x7)
  val field_no = varintresult >> 3

  descriptor_table_handler.io.field_dest_request.bits.proto_addr := output_address_user
  descriptor_table_handler.io.field_dest_request.bits.relative_field_no := field_no - current_min_field_no
  descriptor_table_handler.io.field_dest_request.bits.base_info_ptr := descriptor_table_address_user
  descriptor_table_handler.io.field_dest_request.valid := Bool(false)
  descriptor_table_handler.io.field_dest_response.ready := Bool(false)

  io.fixed_writer_request.bits.write_addr := descriptor_responseReg.write_addr

  io.fixed_writer_request.bits.write_width := UInt(0)
  io.fixed_writer_request.bits.write_data := UInt(0)

  val type_info =   descriptor_responseReg.proto_field_type
  val is_repeated = descriptor_responseReg.is_repeated

  val type_varint64 = (type_info === PROTO_TYPES.TYPE_INT64) || (type_info === PROTO_TYPES.TYPE_UINT64) || (type_info === PROTO_TYPES.TYPE_SINT64)
  val type_bool = (type_info === PROTO_TYPES.TYPE_BOOL)
  val type_need_zigzag64 = (type_info === PROTO_TYPES.TYPE_SINT64)
  val type_need_zigzag32 = (type_info === PROTO_TYPES.TYPE_SINT32)

  io.fixed_writer_request.valid := Bool(false)


  val varint_zigzag32_result = (varintresult(31, 0) >> 1) ^ ((~(varintresult(31, 0) & 1.U)) + 1.U)
  val varint_zigzag64_result = (varintresult >> 1) ^ ((~(varintresult & 1.U)) + 1.U)

  val fixed_alloc_region_next = RegInit(0.U(64.W))
  when (io.fixed_alloc_region_addr.valid) {
    fixed_alloc_region_next := io.fixed_alloc_region_addr.bits
  }
  assert((fixed_alloc_region_next & UInt(0x7)) === UInt(0), "Fixed alloc region ptr must be 8-byte aligned\n")

  val array_alloc_region_next = RegInit(0.U(64.W))
  when (io.array_alloc_region_addr.valid) {
    array_alloc_region_next := io.array_alloc_region_addr.bits
  }
  assert((array_alloc_region_next & UInt(0x7)) === UInt(0), "Array alloc region ptr must be 8-byte aligned\n")




  val sStringWait = UInt(0)
  val sStringReadLength = UInt(1)
  val sStringWriteHeader0 = UInt(2)
  val sStringWriteHeader1 = UInt(3)
  val sStringMoveData = UInt(4)
  val sStringDone = UInt(5)
  val stringFieldState = RegInit(sStringWait)

  val sPackedRepeatedWait = UInt(0)
  val sPackedRepeatedReadByteLength = UInt(1)
  val sPackedRepeatedMoveData = UInt(2)
  val sPackedRepeatedWriteHeader = UInt(3)
  val packedRepeatedFieldState = RegInit(sPackedRepeatedWait)

  val sNestedMessageWait = UInt(0)
  val sGetDescrTableAddr = UInt(1)
  val sLoadVPtr = UInt(2)
  val sObjLenStackManagement = UInt(3)
  val switchNestedMessageSetupState = RegInit(sNestedMessageWait)


  val urc_valid = RegInit(Bool(false))

  val urc_ptr_to_repeated_field = RegInit(0.U(64.W))
  val urc_is_repeated_ptr_field = RegInit(0.B)

  val urc_ptr_to_inobjsizes = RegInit(0.U(64.W))

  val urc_ptr_to_repobjsizes = RegInit(0.U(64.W))

  val urc_next_write_addr = RegInit(0.U(64.W))
  val urc_elems_written = RegInit(0.U(64.W))

  val urc_alloc_stage = RegInit(0.U(2.W))
  val urc_teardown_stage = RegInit(0.U(2.W))


  val hasbitswriter = Module(new HasBitsWriter)
  io.l1helperUser2 <> hasbitswriter.io.l1helperUser
  hasbitswriter.io.requestin.valid := false.B
  hasbitswriter.io.requestin.bits.flushonly := false.B

  val fire_sReadKey = DecoupledHelper(
    descriptor_table_handler.io.field_dest_request.ready,
    io.consumer.output_valid,
    hasbitswriter.io.requestin.ready
  )

  switch (fieldState) {
    is (sReadKey) {
      io.consumer.user_consumed_bytes := varintlen
      io.consumer.output_ready := fire_sReadKey.fire(io.consumer.output_valid)
      descriptor_table_handler.io.field_dest_request.valid := fire_sReadKey.fire(descriptor_table_handler.io.field_dest_request.ready)
      hasbitswriter.io.requestin.valid := fire_sReadKey.fire(hasbitswriter.io.requestin.ready)

      hasbitswriter.io.requestin.bits.hasbits_base_addr := current_out_addr + current_hasbits_offset
      hasbitswriter.io.requestin.bits.relative_fieldno := field_no - current_min_field_no
      hasbitswriter.io.requestin.bits.flushonly := false.B

      when (fire_sReadKey.fire()) {
        ProtoaccLogger.logInfo("Read Key. fieldno: %d, wire_type: %d\n", field_no, wire_type)
        fieldState := sPrepState
        field_no_reg := field_no
        wireTypeReg := wire_type
        just_completed_buffer := Bool(false)
      } .elsewhen (just_completed_buffer) {
        hasbitswriter.io.requestin.valid := true.B
        hasbitswriter.io.requestin.bits.flushonly := true.B
        when (hasbitswriter.io.requestin.ready) {
          just_completed_buffer := Bool(false)
        }
      }
    }
    is (sPrepState) {
      descriptor_table_handler.io.field_dest_response.ready := Bool(true)
      when (descriptor_table_handler.io.field_dest_response.valid) {
        val descr_resp_bits = descriptor_table_handler.io.field_dest_response.bits
        descriptor_responseReg := descr_resp_bits


        val unpacked_repeated = descr_resp_bits.is_repeated && (
          (!(wireTypeReg === sHandleLengthDelim)) || (
            descr_resp_bits.proto_field_type === PROTO_TYPES.TYPE_STRING ||
            descr_resp_bits.proto_field_type === PROTO_TYPES.TYPE_BYTES ||
            descr_resp_bits.proto_field_type === PROTO_TYPES.TYPE_MESSAGE))

        descriptor_responseReg_extra.unpacked_repeated := unpacked_repeated
        descriptor_responseReg_extra.is_repeated_ptr_field :=
            descr_resp_bits.is_repeated && (
            descr_resp_bits.proto_field_type === PROTO_TYPES.TYPE_STRING ||
            descr_resp_bits.proto_field_type === PROTO_TYPES.TYPE_BYTES ||
            descr_resp_bits.proto_field_type === PROTO_TYPES.TYPE_MESSAGE)

        descriptor_responseReg_extra.ptr_to_repeated_field := descr_resp_bits.write_addr - 8.U

        descriptor_responseReg_extra.ptr_to_repeated_field_sizes := descr_resp_bits.write_addr - 8.U
        descriptor_responseReg_extra.ptr_to_repeated_field_elems := descr_resp_bits.write_addr
        descriptor_responseReg_extra.ptr_to_repeated_ptr_field_sizes := descr_resp_bits.write_addr
        descriptor_responseReg_extra.ptr_to_repeated_ptr_field_rep := descr_resp_bits.write_addr + 8.U

        when (unpacked_repeated) {
          fieldState := sManageRepeatedAlloc
        } .otherwise {
          fieldState := wireTypeReg
        }
      }
    }
    is (sManageRepeatedAlloc) {
      when (urc_valid) {
        when (urc_ptr_to_repeated_field ===
                descriptor_responseReg_extra.ptr_to_repeated_field) {
          descriptor_responseReg.write_addr := urc_next_write_addr
          fieldState := wireTypeReg
          ProtoaccLogger.logInfo("[unpacked repeat] continuing. waddr will be: 0x%x, elems written: 0x%x\n",
            urc_next_write_addr,
            urc_elems_written)
        } .otherwise {

          when (urc_is_repeated_ptr_field) {
            when (urc_teardown_stage === 0.U) {
              io.fixed_writer_request.bits.write_data := ((urc_elems_written << 32) | urc_elems_written(31, 0))(63, 0)
              io.fixed_writer_request.valid := Bool(true)
              io.fixed_writer_request.bits.write_addr := urc_ptr_to_inobjsizes
              io.fixed_writer_request.bits.write_width := 3.U

              when (io.fixed_writer_request.ready) {
                ProtoaccLogger.logInfo("[unpacked repptrfield] closeout s0\n")
                urc_teardown_stage := 1.U
              }
            } .otherwise {
              io.fixed_writer_request.bits.write_data := urc_elems_written
              io.fixed_writer_request.valid := Bool(true)
              io.fixed_writer_request.bits.write_addr := urc_ptr_to_repobjsizes
              io.fixed_writer_request.bits.write_width := 3.U

              when (io.fixed_writer_request.ready) {
                ProtoaccLogger.logInfo("[unpacked repptrfield] closeout s1\n")
                urc_valid := Bool(false)
                array_alloc_region_next := ((urc_next_write_addr + 7.U) >> 3.U) << 3.U
                urc_teardown_stage := 0.U
              }
            }
          } .otherwise {
            io.fixed_writer_request.bits.write_data := ((urc_elems_written << 32) | urc_elems_written(31, 0))(63, 0)
            io.fixed_writer_request.valid := Bool(true)
            io.fixed_writer_request.bits.write_addr := urc_ptr_to_inobjsizes
            io.fixed_writer_request.bits.write_width := 3.U

            when (io.fixed_writer_request.ready) {
              ProtoaccLogger.logInfo("[unpacked repfield] closeout\n")

              urc_valid := Bool(false)
              array_alloc_region_next := ((urc_next_write_addr + 7.U) >> 3.U) << 3.U
            }
          }
        }
      } .otherwise {

          when (descriptor_responseReg_extra.is_repeated_ptr_field) {
            when (urc_alloc_stage === 0.U) {
              io.fixed_writer_request.valid := Bool(true)
              io.fixed_writer_request.bits.write_addr := descriptor_responseReg_extra.ptr_to_repeated_ptr_field_rep
              io.fixed_writer_request.bits.write_width := 3.U
              io.fixed_writer_request.bits.write_data := array_alloc_region_next

              when (io.fixed_writer_request.ready) {
                urc_next_write_addr := array_alloc_region_next + 8.U
                urc_valid := Bool(true)

                descriptor_responseReg.write_addr := array_alloc_region_next + 8.U

                urc_alloc_stage := 1.U
                ProtoaccLogger.logInfo("[unpacked repptrfield] starting s0. rep_ obj will be at 0x%x\n",
                  array_alloc_region_next)

                urc_ptr_to_repeated_field := descriptor_responseReg_extra.ptr_to_repeated_field
                urc_is_repeated_ptr_field := Bool(true)
                urc_ptr_to_inobjsizes := descriptor_responseReg_extra.ptr_to_repeated_ptr_field_sizes
                urc_ptr_to_repobjsizes := array_alloc_region_next

                urc_elems_written := 0.U
                urc_alloc_stage := 0.U
                urc_teardown_stage := 0.U

                fieldState := wireTypeReg


              }
            }
          } .otherwise {
            io.fixed_writer_request.valid := Bool(true)
            io.fixed_writer_request.bits.write_addr := descriptor_responseReg_extra.ptr_to_repeated_field_elems
            io.fixed_writer_request.bits.write_width := 3.U
            io.fixed_writer_request.bits.write_data := array_alloc_region_next

            when (io.fixed_writer_request.ready) {

              descriptor_responseReg.write_addr := array_alloc_region_next

              urc_valid := Bool(true)
              urc_ptr_to_repeated_field := descriptor_responseReg_extra.ptr_to_repeated_field
              urc_is_repeated_ptr_field := Bool(false)
              urc_ptr_to_inobjsizes := descriptor_responseReg_extra.ptr_to_repeated_field_sizes
              urc_ptr_to_repobjsizes := 0.U

              urc_next_write_addr := array_alloc_region_next
              urc_elems_written := 0.U
              urc_alloc_stage := 0.U
              urc_teardown_stage := 0.U
              ProtoaccLogger.logInfo("[unpacked repfield] starting. waddr will be 0x%x\n",
                array_alloc_region_next)

              fieldState := wireTypeReg
            }
          }

      }
    }


    is (sHandleVarint) {
      io.consumer.user_consumed_bytes := varintlen
      io.consumer.output_ready := io.fixed_writer_request.ready
      io.fixed_writer_request.valid := io.consumer.output_valid

      io.fixed_writer_request.bits.write_width := Mux(type_varint64,
                                                    UInt(3),
                                                    Mux(type_bool,
                                                      UInt(0),
                                                      UInt(2)))

      when (type_need_zigzag64) {
        io.fixed_writer_request.bits.write_data := varint_zigzag64_result
      } .elsewhen (type_need_zigzag32) {
        io.fixed_writer_request.bits.write_data := varint_zigzag32_result
      } .otherwise {
        io.fixed_writer_request.bits.write_data := varintresult
      }

      when (io.consumer.output_valid && io.fixed_writer_request.ready) {
        when (descriptor_responseReg.is_repeated) {
          urc_next_write_addr := urc_next_write_addr + (1.U(64.W) << io.fixed_writer_request.bits.write_width)
          urc_elems_written := urc_elems_written + 1.U
        }
        ProtoaccLogger.logInfo("Handle Varint. fieldno: %d, value: 0x%x\n", field_no_reg, varintresult)
        ProtoaccLogger.logInfo("Handle Varint. is_repeated: %d, type: %d, waddr: 0x%x\n",
          is_repeated, type_info, io.fixed_writer_request.bits.write_addr)
        when (urc_valid && last_consumer_transaction) {
          fieldState := sEndBufCloseurcArray
        } .otherwise {
          fieldState := sReadKey
        }
      }
    }
    is (sHandle64bit) {
      io.consumer.user_consumed_bytes := UInt(8)
      io.consumer.output_ready := io.fixed_writer_request.ready
      io.fixed_writer_request.bits.write_width := UInt(3)


      val result64 = io.consumer.output_data(63, 0)
      io.fixed_writer_request.bits.write_data := result64
      io.fixed_writer_request.valid := io.consumer.output_valid

      when (io.consumer.output_valid && io.fixed_writer_request.ready) {
        when (descriptor_responseReg.is_repeated) {
          urc_next_write_addr := urc_next_write_addr + (1.U(64.W) << io.fixed_writer_request.bits.write_width)
          urc_elems_written := urc_elems_written + 1.U
        }

        ProtoaccLogger.logInfo("Handle 64bit. fieldno: %d, value: 0x%x\n", field_no_reg, result64)
        ProtoaccLogger.logInfo("Handle 64bit. is_repeated: %d, type: %d, waddr: 0x%x\n",
          is_repeated, type_info, io.fixed_writer_request.bits.write_addr)

        when (urc_valid && last_consumer_transaction) {
          fieldState := sEndBufCloseurcArray
        } .otherwise {
          fieldState := sReadKey
        }

      }
    }

    is (sHandle32bit) {
      io.consumer.user_consumed_bytes := UInt(4)
      io.consumer.output_ready := io.fixed_writer_request.ready
      io.fixed_writer_request.bits.write_width := UInt(2)
      val result32 = io.consumer.output_data(31, 0)
      io.fixed_writer_request.bits.write_data := result32
      io.fixed_writer_request.valid := io.consumer.output_valid

      when (io.consumer.output_valid && io.fixed_writer_request.ready) {
        when (descriptor_responseReg.is_repeated) {
          urc_next_write_addr := urc_next_write_addr + (1.U(64.W) << io.fixed_writer_request.bits.write_width)
          urc_elems_written := urc_elems_written + 1.U
        }

        ProtoaccLogger.logInfo("Handle 32bit. fieldno: %d, value: 0x%x\n", field_no_reg, result32)
        ProtoaccLogger.logInfo("Handle 32bit. is_repeated: %d, type: %d, waddr: 0x%x\n",
          is_repeated, type_info, io.fixed_writer_request.bits.write_addr)

        when (urc_valid && last_consumer_transaction) {
          fieldState := sEndBufCloseurcArray
        } .otherwise {
          fieldState := sReadKey
        }

      }
    }

    is (sHandleLengthDelim) {

      val nested_message = type_info === PROTO_TYPES.TYPE_MESSAGE
      val string_or_bytes = ((type_info === PROTO_TYPES.TYPE_STRING) || (type_info === PROTO_TYPES.TYPE_BYTES))
      val packed_repeated = !nested_message && !string_or_bytes





      val nestedobj_encodedlen = RegInit(0.U(64.W))
      val newobjwriteaddr = RegInit(0.U(64.W))
      val newobj_descriptor = RegInit(0.U(64.W))
      val newobj_vptr = RegInit(0.U(64.W))

      switch (switchNestedMessageSetupState) {
        is (sNestedMessageWait) {

          when (nested_message) {
            io.fixed_writer_request.bits.write_data := fixed_alloc_region_next
            io.fixed_writer_request.bits.write_width := UInt(3)
            io.consumer.output_ready := io.fixed_writer_request.ready
            io.fixed_writer_request.valid := io.consumer.output_valid
            io.consumer.user_consumed_bytes := varintlen

          }

          when (io.consumer.output_valid && io.fixed_writer_request.ready && nested_message) {
            when (descriptor_responseReg.is_repeated) {
              urc_next_write_addr := urc_next_write_addr + (1.U(64.W) << io.fixed_writer_request.bits.write_width)
              urc_elems_written := urc_elems_written + 1.U
            }

            ProtoaccLogger.logInfo("NESTED MESSAGE. s1. is_repeated: %d, type: %d, ptr_waddr: 0x%x, ptr_value: 0x%x, packedfieldlen: %d bytes\n",
              is_repeated, type_info, io.fixed_writer_request.bits.write_addr,
              fixed_alloc_region_next, varintresult)

            nestedobj_encodedlen := varintresult
            switchNestedMessageSetupState := sGetDescrTableAddr

            newobjwriteaddr := fixed_alloc_region_next
          }

        }

        is (sGetDescrTableAddr) {
          descriptor_table_handler.io.extra_meta_response.ready := Bool(true)
          when (descriptor_table_handler.io.extra_meta_response.valid) {
            newobj_descriptor := descriptor_table_handler.io.extra_meta_response.bits.extra_meta0
            switchNestedMessageSetupState := sLoadVPtr
          }
        }
        is (sLoadVPtr) {
          descriptor_table_handler.io.extra_meta_response.ready := io.fixed_writer_request.ready
          io.fixed_writer_request.valid := descriptor_table_handler.io.extra_meta_response.valid

          val obtained_vptr = descriptor_table_handler.io.extra_meta_response.bits.extra_meta0
          io.fixed_writer_request.bits.write_addr := newobjwriteaddr
          io.fixed_writer_request.bits.write_data := obtained_vptr
          io.fixed_writer_request.bits.write_width := UInt(3)

          when (descriptor_table_handler.io.extra_meta_response.valid && io.fixed_writer_request.ready) {
            newobj_vptr := obtained_vptr
            switchNestedMessageSetupState := sObjLenStackManagement

            val newobj_cpp_len = descriptor_table_handler.io.extra_meta_response.bits.extra_meta1
            val newobj_cpp_len_align8 = ((newobj_cpp_len + 7.U) >> 3.U) << 3.U
            fixed_alloc_region_next := fixed_alloc_region_next + newobj_cpp_len_align8

          }

        }
        is (sObjLenStackManagement) {
          descriptor_table_handler.io.extra_meta_response.ready := Bool(true)
          when (descriptor_table_handler.io.extra_meta_response.valid) {
            switchNestedMessageSetupState := sNestedMessageWait

            when (urc_valid && just_completed_buffer) {
              fieldState := sEndBufCloseurcArray
            } .otherwise {
              fieldState := sReadKey
            }

            val min_max_field_nos = descriptor_table_handler.io.extra_meta_response.bits.extra_meta1
            val obtained_hasbits_offset = descriptor_table_handler.io.extra_meta_response.bits.extra_meta0
            val min_field_no = min_max_field_nos >> 32
            val max_field_no = min_max_field_nos(31, 0)
            ProtoaccLogger.logInfo("MinFieldNo: %d, MaxFieldNo: %d", min_field_no, max_field_no)

            val compare_encoded_lens = processed_len_total + nestedobj_encodedlen
            when (compare_encoded_lens === current_len) {
              ProtoaccLogger.logInfo("NStack: Replacing top entry\n")
              out_addr_stack(stacks_index) := newobjwriteaddr
              hasbits_offset_stack(stacks_index) := obtained_hasbits_offset
              descr_table_stack(stacks_index) := newobj_descriptor
              min_field_no_stack(stacks_index) := min_field_no
            } .otherwise {
              ProtoaccLogger.logInfo("NStack: Adding entry\n")
              val next_stack_ind = stacks_index + 1.U
              out_addr_stack(next_stack_ind) := newobjwriteaddr
              hasbits_offset_stack(next_stack_ind) := obtained_hasbits_offset
              descr_table_stack(next_stack_ind) := newobj_descriptor
              lens_table_stack(next_stack_ind) := compare_encoded_lens
              min_field_no_stack(next_stack_ind) := min_field_no
              stacks_index := next_stack_ind
            }
          }
        }
      }

      val repLenBytesLeft = RegInit(0.U(64.W))
      val type_tracker = RegInit(0.U(64.W))
      val repeatedfield_obj_addr = RegInit(0.U(64.W))
      val current_packed_write_ptr = RegInit(0.U(64.W))
      val elements_written = RegInit(0.U(64.W))

      switch (packedRepeatedFieldState) {
        is (sPackedRepeatedWait) {

          when (packed_repeated) {
            io.fixed_writer_request.bits.write_data := fixed_alloc_region_next
            io.fixed_writer_request.bits.write_width := UInt(3)
            io.consumer.output_ready := io.fixed_writer_request.ready
            io.fixed_writer_request.valid := io.consumer.output_valid
            io.consumer.user_consumed_bytes := varintlen

          }

          when (io.fixed_writer_request.ready && io.consumer.output_valid && packed_repeated) {
            type_tracker := type_info
            repeatedfield_obj_addr := descriptor_responseReg.write_addr

            repLenBytesLeft := varintresult
            elements_written := 0.U

            packedRepeatedFieldState := sPackedRepeatedMoveData

            ProtoaccLogger.logInfo("PACKED_REPEATED. s1. is_repeated: %d, type: %d, ptr_waddr: 0x%x, ptr_value: 0x%x, packedfieldlen: %d bytes\n",
              is_repeated, type_info, io.fixed_writer_request.bits.write_addr,
              fixed_alloc_region_next, varintresult)

            current_packed_write_ptr := fixed_alloc_region_next
          }

        }
        is (sPackedRepeatedMoveData) {
          io.fixed_writer_request.bits.write_addr := current_packed_write_ptr

          val consume_varint = type_tracker === PROTO_TYPES.TYPE_INT64 ||
                              type_tracker === PROTO_TYPES.TYPE_UINT64 ||
                              type_tracker === PROTO_TYPES.TYPE_INT32 ||
                              type_tracker === PROTO_TYPES.TYPE_BOOL ||
                              type_tracker === PROTO_TYPES.TYPE_UINT32 ||
                              type_tracker === PROTO_TYPES.TYPE_ENUM ||
                              type_tracker === PROTO_TYPES.TYPE_SINT32 ||
                              type_tracker === PROTO_TYPES.TYPE_SINT64
          val consume_8bytes = type_tracker === PROTO_TYPES.TYPE_DOUBLE ||
                               type_tracker === PROTO_TYPES.TYPE_FIXED64
          val consume_4bytes = type_tracker === PROTO_TYPES.TYPE_FLOAT ||
                               type_tracker === PROTO_TYPES.TYPE_FIXED32

          val consume_width = Wire(0.U(4.W))
          when (consume_varint) {
            io.consumer.user_consumed_bytes := varintlen
            consume_width := varintlen
          } .elsewhen (consume_8bytes) {
            io.consumer.user_consumed_bytes := 8.U
            consume_width := 8.U
          } .elsewhen (consume_4bytes) {
            io.consumer.user_consumed_bytes := 4.U
            consume_width := 4.U
          } .otherwise {
            assert(Bool(false), "ERROR")
          }

          val write_8bytes = type_tracker === PROTO_TYPES.TYPE_DOUBLE ||
                             type_tracker === PROTO_TYPES.TYPE_FIXED64 ||
                             type_tracker === PROTO_TYPES.TYPE_INT64 ||
                             type_tracker === PROTO_TYPES.TYPE_UINT64 ||
                             type_tracker === PROTO_TYPES.TYPE_SINT64
          val write_4bytes = type_tracker === PROTO_TYPES.TYPE_FLOAT ||
                             type_tracker === PROTO_TYPES.TYPE_FIXED32 ||
                             type_tracker === PROTO_TYPES.TYPE_INT32 ||
                             type_tracker === PROTO_TYPES.TYPE_UINT32 ||
                             type_tracker === PROTO_TYPES.TYPE_SINT32 ||
                             type_tracker === PROTO_TYPES.TYPE_ENUM
          val write_1bytes = type_tracker === PROTO_TYPES.TYPE_BOOL


          val write_width = Wire(0.U(4.W))
          when (write_8bytes) {
            io.fixed_writer_request.bits.write_width := UInt(3)
            write_width := 8.U
          } .elsewhen (write_4bytes) {
            io.fixed_writer_request.bits.write_width := UInt(2)
            write_width := 4.U
          } .elsewhen (write_1bytes) {
            io.fixed_writer_request.bits.write_width := UInt(0)
            write_width := 1.U
          } .otherwise {
            assert(Bool(false), "ERROR")
          }


          val output_signed_varint64 = type_tracker === PROTO_TYPES.TYPE_SINT64
          val output_signed_varint32 = type_tracker === PROTO_TYPES.TYPE_SINT32
          val output_regular_varint = type_tracker === PROTO_TYPES.TYPE_INT64 ||
                                      type_tracker === PROTO_TYPES.TYPE_UINT32 ||
                                      type_tracker === PROTO_TYPES.TYPE_UINT64 ||
                                      type_tracker === PROTO_TYPES.TYPE_INT32 ||
                                      type_tracker === PROTO_TYPES.TYPE_BOOL ||
                                      type_tracker === PROTO_TYPES.TYPE_ENUM
          val output_raw = type_tracker === PROTO_TYPES.TYPE_DOUBLE ||
                           type_tracker === PROTO_TYPES.TYPE_FLOAT ||
                           type_tracker === PROTO_TYPES.TYPE_FIXED64 ||
                           type_tracker === PROTO_TYPES.TYPE_FIXED32

          when (output_signed_varint64) {
            io.fixed_writer_request.bits.write_data := varint_zigzag64_result
          } .elsewhen (output_signed_varint32) {
            io.fixed_writer_request.bits.write_data := varint_zigzag32_result
          } .elsewhen (output_regular_varint) {
            io.fixed_writer_request.bits.write_data := varintresult
          } .elsewhen (output_raw) {
            io.fixed_writer_request.bits.write_data := io.consumer.output_data(63, 0)
          } .otherwise {
            assert(Bool(false), "ERROR")
          }

          io.fixed_writer_request.valid := io.consumer.output_valid
          io.consumer.output_ready := io.fixed_writer_request.ready
          when (io.fixed_writer_request.ready && io.consumer.output_valid) {
            ProtoaccLogger.logInfo("PACKED_REPEATED. s2. write_width %d, consumed_bytes %d\n", write_width, consume_width)

            repLenBytesLeft := repLenBytesLeft - consume_width
            current_packed_write_ptr := current_packed_write_ptr + write_width
            elements_written := elements_written + 1.U

            when (repLenBytesLeft === consume_width) {
              ProtoaccLogger.logInfo("PACKED_REPEATED. s3. write_width %d, consumed_bytes %d\n", write_width, consume_width)
              repeatedfield_obj_addr := repeatedfield_obj_addr - 8.U
              fixed_alloc_region_next := ((current_packed_write_ptr + write_width + 7.U) >> 3.U) << 3.U
              packedRepeatedFieldState := sPackedRepeatedWriteHeader
            }
          }
        }
        is (sPackedRepeatedWriteHeader) {
          io.fixed_writer_request.valid := Bool(true)
          io.fixed_writer_request.bits.write_width := UInt(3)

          io.fixed_writer_request.bits.write_data := ((elements_written << 32) | elements_written(31, 0))(63, 0)
          io.fixed_writer_request.bits.write_addr := repeatedfield_obj_addr

          when(io.fixed_writer_request.ready) {
              ProtoaccLogger.logInfo("PACKED_REPEATED. s4. sizes 0x%x, addr 0x%x\n",
              io.fixed_writer_request.bits.write_data, io.fixed_writer_request.bits.write_addr)
            packedRepeatedFieldState := sPackedRepeatedWait

            when (urc_valid && just_completed_buffer) {
              fieldState := sEndBufCloseurcArray
            } .otherwise {
              fieldState := sReadKey
            }


          }
        }
      }



      val stringLenNoNull = RegInit(0.U(64.W))
      val stringLenWithNull = RegInit(0.U(64.W))
      val stringLenWithNullPadded = RegInit(0.U(64.W))
      val data_write_ptr = RegInit(0.U(64.W))

      val obj_header_write_ptr = RegInit(0.U(64.W))
      val handling_bytes = RegInit(Bool(false))

      switch (stringFieldState) {
        is (sStringWait) {


          val fixed_alloc_region_next_16B_aligned = (fixed_alloc_region_next + UInt(15)) & ~(UInt(15, 64.W))


          when (string_or_bytes) {
            io.fixed_writer_request.bits.write_data := fixed_alloc_region_next_16B_aligned
            io.fixed_writer_request.bits.write_width := UInt(3)
            io.consumer.output_ready := io.fixed_writer_request.ready
            io.fixed_writer_request.valid := io.consumer.output_valid
            io.consumer.user_consumed_bytes := varintlen
          }

          when (io.fixed_writer_request.ready && io.consumer.output_valid && string_or_bytes) {

            when (descriptor_responseReg.is_repeated) {
              urc_next_write_addr := urc_next_write_addr + (8.U)
              urc_elems_written := urc_elems_written + 1.U
            }

            obj_header_write_ptr := fixed_alloc_region_next_16B_aligned
            fixed_alloc_region_next := fixed_alloc_region_next_16B_aligned + (32.U)

            handling_bytes := type_info === PROTO_TYPES.TYPE_BYTES

            stringLenNoNull := varintresult
            stringLenWithNull := varintresult + UInt(1)

            stringFieldState := sStringWriteHeader0

            ProtoaccLogger.logInfo("Handle String. is_repeated: %d, type: %d, ptr_waddr: 0x%x, ptr_value: 0x%x, stringlen: %d bytes\n",
              is_repeated, type_info, io.fixed_writer_request.bits.write_addr,
              fixed_alloc_region_next, varintresult)


          }
        }
        is (sStringWriteHeader0) {
          val header0_val = Mux(stringLenWithNull <= 16.U(64.W),
            obj_header_write_ptr + 16.U(64.W),
            fixed_alloc_region_next)
          data_write_ptr := header0_val

          io.fixed_writer_request.valid := Bool(true)

          io.fixed_writer_request.bits.write_width := UInt(3)
          io.fixed_writer_request.bits.write_data := header0_val
          io.fixed_writer_request.bits.write_addr := obj_header_write_ptr

          when(io.fixed_writer_request.ready) {
            stringFieldState := sStringWriteHeader1
            obj_header_write_ptr := obj_header_write_ptr + 8.U

            val stringLenWithNullPadded_calc = (stringLenWithNull + UInt(15)) & ~(UInt(15, 64.W))
            stringLenWithNullPadded := stringLenWithNullPadded_calc

            val fixed_alloc_region_increment = Mux(stringLenWithNull <= 16.U(64.W),
                UInt(0),
                stringLenWithNullPadded_calc
              )

            fixed_alloc_region_next := fixed_alloc_region_next + fixed_alloc_region_increment
            ProtoaccLogger.logInfo("Current alloc base: 0x%x, Next alloc base: 0x%x\n", fixed_alloc_region_next, fixed_alloc_region_next + fixed_alloc_region_increment)
          }
        }
        is (sStringWriteHeader1) {
          io.fixed_writer_request.valid := Bool(true)

          io.fixed_writer_request.bits.write_width := UInt(3)
          io.fixed_writer_request.bits.write_data := stringLenNoNull
          io.fixed_writer_request.bits.write_addr := obj_header_write_ptr

          when(io.fixed_writer_request.ready) {
            stringFieldState := sStringMoveData
          }
        }
        is (sStringMoveData) {
          ProtoaccLogger.logInfo("sStringMoveData State\n")
          val inc_amt_log2 = UInt(4)
          io.fixed_writer_request.bits.write_width := inc_amt_log2
          io.fixed_writer_request.bits.write_addr := data_write_ptr


          when (io.fixed_writer_request.ready && !io.consumer.output_valid) {
            ProtoaccLogger.logInfo("fixed_writer ready but not consumer output\n")
          } .elsewhen (!io.fixed_writer_request.ready && io.consumer.output_valid) {
            ProtoaccLogger.logInfo("consumer output valid but not fixed_writer ready\n")
          }



          when (stringLenWithNullPadded > 16.U(64.W)) {
            val inc_amt = UInt(16)
            io.consumer.user_consumed_bytes := inc_amt

            val result128 = io.consumer.output_data(127, 0)
            io.fixed_writer_request.bits.write_data := result128

            io.fixed_writer_request.valid := io.consumer.output_valid
            io.consumer.output_ready := io.fixed_writer_request.ready
            when (io.fixed_writer_request.ready && io.consumer.output_valid) {
              ProtoaccLogger.logInfo("---stringLenWithNullPadded: %d\n", stringLenWithNullPadded)
              ProtoaccLogger.logInfo("---stringLenWithNull: %d\n", stringLenWithNull)
              ProtoaccLogger.logInfo("---stringLenNoNull: %d\n", stringLenNoNull)

              data_write_ptr := data_write_ptr + inc_amt
              stringLenWithNullPadded := stringLenWithNullPadded - inc_amt
              stringLenWithNull := stringLenWithNull - inc_amt
              stringLenNoNull := stringLenNoNull - inc_amt
            }
          } .elsewhen (stringLenWithNullPadded === 16.U(64.W) && stringLenNoNull =/= 0.U(64.W)) {
            val inc_amt = UInt(16)
            io.consumer.user_consumed_bytes := stringLenNoNull

            val result128 = io.consumer.output_data(127, 0)
            val stringLenNoNullShamt = stringLenNoNull(3, 0) << 3
            io.fixed_writer_request.bits.write_data := result128 & ((1.U(128.W) << (stringLenNoNullShamt)) - UInt(1))

            io.fixed_writer_request.valid := io.consumer.output_valid
            io.consumer.output_ready := io.fixed_writer_request.ready
            when (io.fixed_writer_request.ready && io.consumer.output_valid) {
              ProtoaccLogger.logInfo("---stringLenWithNullPadded: %d\n", stringLenWithNullPadded)
              ProtoaccLogger.logInfo("---stringLenWithNull: %d\n", stringLenWithNull)
              ProtoaccLogger.logInfo("---stringLenNoNull: %d\n", stringLenNoNull)

              data_write_ptr := data_write_ptr + inc_amt
              stringLenWithNullPadded := stringLenWithNullPadded - inc_amt
              stringLenWithNull := stringLenWithNull - inc_amt
              stringLenNoNull := stringLenNoNull - inc_amt

              ProtoaccLogger.logInfo("---DONE STRING!\n")

              when (urc_valid && last_consumer_transaction) {
                fieldState := sEndBufCloseurcArray
              } .otherwise {
                fieldState := sReadKey
              }

              stringFieldState := sStringWait
            }
          } .elsewhen (stringLenWithNullPadded === 16.U(64.W) && stringLenNoNull === 0.U(64.W)) {
            when (io.fixed_writer_request.ready) {
              ProtoaccLogger.logInfo("---DONE STRING!\n")

              when (urc_valid && just_completed_buffer) {
                fieldState := sEndBufCloseurcArray
              } .otherwise {
                fieldState := sReadKey
              }

              stringFieldState := sStringWait
            }
            io.fixed_writer_request.bits.write_data := UInt(0)
            io.fixed_writer_request.valid := Bool(true)
            io.consumer.output_ready := Bool(false)
            io.consumer.user_consumed_bytes := UInt(0)
          } .otherwise {
            assert(Bool(false), "should be unreachable\n")
          }

        }
      }

    }
    is (sHandleStartGroup) {
      assert(fieldState =/= sHandleStartGroup, "Start Group not yet implemented")
    }
    is (sHandleEndGroup) {
      assert(fieldState =/= sHandleEndGroup, "End Group not yet implemented")
    }
    is (sEndBufCloseurcArray) {

      when (urc_is_repeated_ptr_field) {
        when (urc_teardown_stage === 0.U) {
          io.fixed_writer_request.bits.write_data := ((urc_elems_written << 32) | urc_elems_written(31, 0))(63, 0)
          io.fixed_writer_request.valid := Bool(true)
          io.fixed_writer_request.bits.write_addr := urc_ptr_to_inobjsizes
          io.fixed_writer_request.bits.write_width := 3.U

          when (io.fixed_writer_request.ready) {
            ProtoaccLogger.logInfo("[unpacked repptrfield] closeout end of buf s0\n")
            urc_teardown_stage := 1.U
          }
        } .otherwise {
          io.fixed_writer_request.bits.write_data := urc_elems_written
          io.fixed_writer_request.valid := Bool(true)
          io.fixed_writer_request.bits.write_addr := urc_ptr_to_repobjsizes
          io.fixed_writer_request.bits.write_width := 3.U

          when (io.fixed_writer_request.ready) {
            ProtoaccLogger.logInfo("[unpacked repptrfield] closeout end of buf s1\n")
            urc_valid := Bool(false)
            array_alloc_region_next := ((urc_next_write_addr + 7.U) >> 3.U) << 3.U
            urc_teardown_stage := 0.U
            fieldState := sReadKey

          }
        }
      } .otherwise {
        io.fixed_writer_request.bits.write_data := ((urc_elems_written << 32) | urc_elems_written(31, 0))(63, 0)
        io.fixed_writer_request.valid := Bool(true)
        io.fixed_writer_request.bits.write_addr := urc_ptr_to_inobjsizes
        io.fixed_writer_request.bits.write_width := 3.U

        when (io.fixed_writer_request.ready) {
          ProtoaccLogger.logInfo("[unpacked repfield] closeout end of buf\n")

          urc_valid := Bool(false)
          array_alloc_region_next := ((urc_next_write_addr + 7.U) >> 3.U) << 3.U
          fieldState := sReadKey

        }
      }

    }

  }

}


