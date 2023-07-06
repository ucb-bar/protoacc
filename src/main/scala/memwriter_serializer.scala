package protoacc

import Chisel._
import chisel3.{Printable, VecInit}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._


class SerMemwriter()(implicit p: Parameters) extends Module
  with MemoryOpConstants {

  val io = IO(new Bundle {
    val memwrites_in = Decoupled(new WriterBundle).flip

    val stringobj_output_addr = Valid(UInt(64.W)).flip
    val string_ptr_output_addr = Valid(UInt(64.W)).flip

    val l2io = new L1MemHelperBundle

    val messages_completed = Output(UInt(64.W))
    val mem_work_outstanding = Output(Bool())
  })

  val writes_input_IF_Q = Module(new Queue(new WriterBundle, 4))
  writes_input_IF_Q.io.enq <> io.memwrites_in

  val write_inject_Q = Module(new Queue(new WriterBundle, 4))

  val depth = RegInit(0.U(ProtoaccParams.MAX_NESTED_LEVELS_WIDTH.W))

  val size_stack = RegInit(VecInit(Seq.fill(ProtoaccParams.MAX_NESTED_LEVELS) { 0.U(64.W) }))


  write_inject_Q.io.enq.bits := writes_input_IF_Q.io.deq.bits

  val varint_encoder = Module(new CombinationalVarintEncode)
  varint_encoder.io.inputData := size_stack(depth)

  write_inject_Q.io.enq.valid := writes_input_IF_Q.io.deq.valid
  writes_input_IF_Q.io.deq.ready := write_inject_Q.io.enq.ready

  when (writes_input_IF_Q.io.deq.bits.depth > depth) {
      when (writes_input_IF_Q.io.deq.bits.end_of_message) {
        when (writes_input_IF_Q.io.deq.bits.last_for_arbitration_round) {
          val empty_submessage_size = 1.U + writes_input_IF_Q.io.deq.bits.validbytes
          when (write_inject_Q.io.enq.fire()) {
            val parent_depth = writes_input_IF_Q.io.deq.bits.depth - 1.U
            size_stack(parent_depth) := size_stack(parent_depth) + empty_submessage_size
            depth := parent_depth
          }
        } .otherwise {
          write_inject_Q.io.enq.bits.data := 0.U
          write_inject_Q.io.enq.bits.validbytes := 1.U
        }
      } .otherwise {
        when (write_inject_Q.io.enq.fire()) {
          depth := writes_input_IF_Q.io.deq.bits.depth
          size_stack(writes_input_IF_Q.io.deq.bits.depth) := writes_input_IF_Q.io.deq.bits.validbytes
        }
      }
  } .elsewhen (writes_input_IF_Q.io.deq.bits.depth === depth) {

      when (writes_input_IF_Q.io.deq.bits.end_of_message) {
        when (writes_input_IF_Q.io.deq.bits.last_for_arbitration_round) {
          val depth_minus_one = depth - 1.U
          when (write_inject_Q.io.enq.fire()) {
            depth := depth_minus_one
            size_stack(depth_minus_one) := size_stack(depth_minus_one) + size_stack(depth) + writes_input_IF_Q.io.deq.bits.validbytes
            size_stack(depth) := 0.U
          }
        } .otherwise {
          val enc_len = varint_encoder.io.outputData
          val enc_len_bytes_write = varint_encoder.io.outputBytes
          write_inject_Q.io.enq.bits.data := enc_len
          write_inject_Q.io.enq.bits.validbytes := enc_len_bytes_write
          when (write_inject_Q.io.enq.fire()) {
            size_stack(depth) := size_stack(depth) + enc_len_bytes_write
          }
        }
      } .otherwise {
        when (write_inject_Q.io.enq.fire()) {
          size_stack(depth) := size_stack(depth) + writes_input_IF_Q.io.deq.bits.validbytes
        }
      }
  } .elsewhen (write_inject_Q.io.enq.fire()) {
    assert(false.B, "FAIL, should never have input depth === depth-1\n")
  }

  val backend_stringobj_output_addr_tail = RegInit(0.U(64.W))
  val frontend_stringobj_output_addr_tail = RegInit(0.U(64.W))

  val backend_string_ptr_output_addr = RegInit(0.U(64.W))

  when (io.stringobj_output_addr.valid) {
    ProtoaccLogger.logInfo("[config-memwriter] got stringobj ptr tail addr: 0x%x\n",
      io.stringobj_output_addr.bits)
    backend_stringobj_output_addr_tail := io.stringobj_output_addr.bits
    frontend_stringobj_output_addr_tail := io.stringobj_output_addr.bits
  }
  when (io.string_ptr_output_addr.valid) {
    ProtoaccLogger.logInfo("[config-memwriter] got string ptr output addr: 0x%x\n",
      io.string_ptr_output_addr.bits)
    backend_string_ptr_output_addr := io.string_ptr_output_addr.bits
  }


  val write_ptrs_Q = Module(new Queue(UInt(64.W), 10))
  when (write_ptrs_Q.io.enq.fire()) {
    ProtoaccLogger.logInfo("[memwriter-serializer] enqueued ptr: 0x%x\n", write_ptrs_Q.io.enq.bits)
  }





  when (write_inject_Q.io.deq.fire()) {
    ProtoaccLogger.logInfo("[memwriter-serializer] dat: 0x%x, last: 0x%x, bytes: 0x%x, depth: %d, EOM: %d\n",
      write_inject_Q.io.deq.bits.data,
      write_inject_Q.io.deq.bits.last_for_arbitration_round,
      write_inject_Q.io.deq.bits.validbytes,
      write_inject_Q.io.deq.bits.depth,
      write_inject_Q.io.deq.bits.end_of_message
      )
  }

  val NUM_QUEUES = 16
  val QUEUE_DEPTHS = 16
  val write_start_index = RegInit(UInt(0, log2Up(NUM_QUEUES+1).W))
  val mem_resp_queues = Vec.fill(NUM_QUEUES)(Module(new Queue(UInt(8.W), QUEUE_DEPTHS)).io)

  val len_to_write = write_inject_Q.io.deq.bits.validbytes

  for ( queueno <- 0 until NUM_QUEUES ) {
    mem_resp_queues((write_start_index +& UInt(queueno)) % UInt(NUM_QUEUES)).enq.bits := write_inject_Q.io.deq.bits.data >> ((len_to_write - (queueno+1).U) << 3)
  }


  val wrap_len_index_wide = write_start_index +& len_to_write
  val wrap_len_index_end = wrap_len_index_wide % UInt(NUM_QUEUES)
  val wrapped = wrap_len_index_wide >= UInt(NUM_QUEUES)

  val all_queues_ready = mem_resp_queues.map(_.enq.ready).reduce(_ && _)


  val input_is_toplevel = write_inject_Q.io.deq.bits.depth === 1.U
  val end_of_toplevel = write_inject_Q.io.deq.bits.end_of_message && input_is_toplevel
  val account_for_output_queue = (!end_of_toplevel) || (end_of_toplevel && write_ptrs_Q.io.enq.ready)

  val input_fire_allqueues = DecoupledHelper(
    write_inject_Q.io.deq.valid,
    all_queues_ready,
    account_for_output_queue
  )

  write_ptrs_Q.io.enq.valid := input_fire_allqueues.fire(account_for_output_queue) && end_of_toplevel
  write_ptrs_Q.io.enq.bits := frontend_stringobj_output_addr_tail

  write_inject_Q.io.deq.ready := input_fire_allqueues.fire(write_inject_Q.io.deq.valid)

  when (input_fire_allqueues.fire() && !end_of_toplevel) {
    write_start_index := wrap_len_index_end

    frontend_stringobj_output_addr_tail := frontend_stringobj_output_addr_tail - len_to_write
  }


  for ( queueno <- 0 until NUM_QUEUES ) {
    val use_this_queue = Mux(wrapped,
                             (UInt(queueno) >= write_start_index) || (UInt(queueno) < wrap_len_index_end),
                             (UInt(queueno) >= write_start_index) && (UInt(queueno) < wrap_len_index_end)
                            )
    mem_resp_queues(queueno).enq.valid := input_fire_allqueues.fire() && use_this_queue && !end_of_toplevel
  }

  for ( queueno <- 0 until NUM_QUEUES ) {
    when (mem_resp_queues(queueno).deq.valid) {
      ProtoaccLogger.logInfo("qi%d,0x%x\n", UInt(queueno), mem_resp_queues(queueno).deq.bits)
    }
  }




  val read_start_index = RegInit(UInt(0, log2Up(NUM_QUEUES+1).W))
  val len_already_consumed = RegInit(UInt(0, 64.W))

  val remapVecData = Wire(Vec(NUM_QUEUES, UInt(8.W)))
  val remapVecValids = Wire(Vec(NUM_QUEUES, Bool()))
  val remapVecReadys = Wire(Vec(NUM_QUEUES, Bool()))


  for (queueno <- 0 until NUM_QUEUES) {
    val remapindex = (UInt(queueno) +& read_start_index) % UInt(NUM_QUEUES)
    remapVecData(queueno) := mem_resp_queues(remapindex).deq.bits
    remapVecValids(queueno) := mem_resp_queues(remapindex).deq.valid
    mem_resp_queues(remapindex).deq.ready := remapVecReadys(queueno)
  }

  val count_valids = remapVecValids.map(_.asUInt).reduce(_ +& _)





  val ptr_align_max_bytes_writeable = Mux(backend_stringobj_output_addr_tail(0), 1.U,
                                        Mux(backend_stringobj_output_addr_tail(1), 2.U,
                                          Mux(backend_stringobj_output_addr_tail(2), 4.U,
                                            Mux(backend_stringobj_output_addr_tail(3), 8.U,
                                                                                 16.U))))

  val ptr_align_max_bytes_writeable_log2 = Mux(backend_stringobj_output_addr_tail(0), 0.U,
                                            Mux(backend_stringobj_output_addr_tail(1), 1.U,
                                              Mux(backend_stringobj_output_addr_tail(2), 2.U,
                                                Mux(backend_stringobj_output_addr_tail(3), 3.U,
                                                                                     4.U))))

  val count_valids_largest_aligned = Mux(count_valids(4), 16.U,
                                      Mux(count_valids(3), 8.U,
                                        Mux(count_valids(2), 4.U,
                                          Mux(count_valids(1), 2.U,
                                            Mux(count_valids(0), 1.U,
                                                                  0.U)))))

  val count_valids_largest_aligned_log2 = Mux(count_valids(4), 4.U,
                                            Mux(count_valids(3), 3.U,
                                              Mux(count_valids(2), 2.U,
                                                Mux(count_valids(1), 1.U,
                                                  Mux(count_valids(0), 0.U,
                                                                         0.U)))))



  val bytes_to_write = Mux(
    ptr_align_max_bytes_writeable < count_valids_largest_aligned,
    ptr_align_max_bytes_writeable,
    count_valids_largest_aligned
  )
  val remapped_write_data = Cat(remapVecData) >> ((NUM_QUEUES.U - bytes_to_write) << 3)

  val enough_data = bytes_to_write =/= 0.U

  val bytes_to_write_log2 = Mux(
    ptr_align_max_bytes_writeable_log2 < count_valids_largest_aligned_log2,
    ptr_align_max_bytes_writeable_log2,
    count_valids_largest_aligned_log2
  )

  val write_ptr_override = write_ptrs_Q.io.deq.valid && (write_ptrs_Q.io.deq.bits >= backend_stringobj_output_addr_tail)

  val mem_write_fire = DecoupledHelper(
    io.l2io.req.ready,
    enough_data,
    !write_ptr_override
  )

  for (queueno <- 0 until NUM_QUEUES) {
    remapVecReadys(queueno) := (UInt(queueno) < bytes_to_write) && mem_write_fire.fire()
  }

  when (mem_write_fire.fire()) {
    read_start_index := (read_start_index +& bytes_to_write) % UInt(NUM_QUEUES)
    backend_stringobj_output_addr_tail := backend_stringobj_output_addr_tail - bytes_to_write
    len_already_consumed := len_already_consumed + bytes_to_write
    ProtoaccLogger.logInfo("[memwriter-serializer] writefire: addr: 0x%x, data 0x%x, size %d\n",
      io.l2io.req.bits.addr,
      io.l2io.req.bits.data,
      io.l2io.req.bits.size
    )
  }

  io.l2io.req.valid := mem_write_fire.fire(io.l2io.req.ready) || write_ptr_override
  io.l2io.req.bits.size := Mux(write_ptr_override, 3.U, bytes_to_write_log2)
  io.l2io.req.bits.addr := Mux(write_ptr_override, backend_string_ptr_output_addr, backend_stringobj_output_addr_tail - bytes_to_write)
  io.l2io.req.bits.data := Mux(write_ptr_override, write_ptrs_Q.io.deq.bits, remapped_write_data)
  io.l2io.req.bits.cmd := M_XWR


  write_ptrs_Q.io.deq.ready := (write_ptrs_Q.io.deq.bits >= backend_stringobj_output_addr_tail) && io.l2io.req.ready

  val messages_completed = RegInit(0.U(64.W))
  io.messages_completed := messages_completed

  io.l2io.resp.ready := true.B

  io.mem_work_outstanding := !io.l2io.no_memops_inflight

  when (write_ptr_override && io.l2io.req.ready) {
    backend_string_ptr_output_addr := backend_string_ptr_output_addr + 8.U
    messages_completed := messages_completed + 1.U
  }

  when (write_ptrs_Q.io.deq.fire()) {
    ProtoaccLogger.logInfo("[memwriter-serializer] write ptr addr: 0x%x, write ptr val 0x%x\n", backend_string_ptr_output_addr, write_ptrs_Q.io.deq.bits)
  }

  when (count_valids =/= 0.U) {
    ProtoaccLogger.logInfo("[memwriter-serializer] write_start_index %d, len_already_consumed %d, count_valids %d, ptr_align_max_bytes_writeable %d, bytes_to_write %d, bytes_to_write_log2 %d\n",
      read_start_index,
      len_already_consumed,
      count_valids,
      ptr_align_max_bytes_writeable,
      bytes_to_write,
      bytes_to_write_log2
    )
  }



}


