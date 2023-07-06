package protoacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants

class BufInfoBundle extends Bundle {
  val len_bytes = UInt(32.W)
  val ADT_addr = UInt(64.W)
  val decoded_dest_base_addr = UInt(64.W)
  val min_field_no = UInt(32.W)
}

class LoadInfoBundle extends Bundle {
  val start_byte = UInt(4.W)
  val end_byte = UInt(4.W)
}


class MemLoaderConsumerBundle extends Bundle {
  val user_consumed_bytes = UInt(INPUT, log2Up(16+1).W)
  val available_output_bytes = UInt(OUTPUT, log2Up(16+1).W)
  val output_valid = Bool(OUTPUT)
  val output_ready = Bool(INPUT)
  val output_data = UInt(OUTPUT, (16*8).W)
  val output_ADT_addr = UInt(OUTPUT, 64.W)
  val output_min_field_no = UInt(OUTPUT, 32.W)
  val output_decoded_dest_base_addr = UInt(OUTPUT, 64.W)
  val output_last_chunk = Bool(OUTPUT)
}

class MemLoader()(implicit p: Parameters) extends Module
     with MemoryOpConstants {

  val io = IO(new Bundle {
    val l1helperUser = new L1MemHelperBundle

    val do_proto_parse_cmd = Decoupled(new RoCCCommand).flip
    val proto_parse_info_cmd = Decoupled(new RoCCCommand).flip

    val consumer = new MemLoaderConsumerBundle

  })

  assert(!io.do_proto_parse_cmd.valid || (io.do_proto_parse_cmd.valid &&
    io.proto_parse_info_cmd.valid),
    "got do proto parse command without valid proto parse info command!\n")

  val buf_info_queue = Module(new Queue(new BufInfoBundle, 16))

  val load_info_queue = Module(new Queue(new LoadInfoBundle, 256))

  val base_addr_bytes = io.do_proto_parse_cmd.bits.rs1
  val base_len = io.do_proto_parse_cmd.bits.rs2(31, 0)
  val min_field_no = io.do_proto_parse_cmd.bits.rs2 >> 32
  val base_addr_start_index = io.do_proto_parse_cmd.bits.rs1 & UInt(0xF)
  val aligned_loadlen =  base_len + base_addr_start_index
  val base_addr_end_index = (base_len + base_addr_start_index) & UInt(0xF)
  val base_addr_end_index_inclusive = (base_len + base_addr_start_index - UInt(1)) & UInt(0xF)
  val extra_word = ((aligned_loadlen & UInt(0xF)) =/= UInt(0)).asUInt

  val base_addr_bytes_aligned = (base_addr_bytes >> UInt(4)) << UInt(4)
  val words_to_load = (aligned_loadlen >> UInt(4)) + extra_word
  val words_to_load_minus_one = words_to_load - UInt(1)

  val ADT_addr = io.proto_parse_info_cmd.bits.rs1

  when (io.do_proto_parse_cmd.valid) {
    ProtoaccLogger.logInfo("base_addr_bytes: %x\n", base_addr_bytes)
    ProtoaccLogger.logInfo("base_len: %x\n", base_len)
    ProtoaccLogger.logInfo("base_addr_start_index: %x\n", base_addr_start_index)
    ProtoaccLogger.logInfo("aligned_loadlen: %x\n", aligned_loadlen)
    ProtoaccLogger.logInfo("base_addr_end_index: %x\n", base_addr_end_index)
    ProtoaccLogger.logInfo("base_addr_end_index_inclusive: %x\n", base_addr_end_index_inclusive)
    ProtoaccLogger.logInfo("extra_word: %x\n", extra_word)
    ProtoaccLogger.logInfo("base_addr_bytes_aligned: %x\n", base_addr_bytes_aligned)
    ProtoaccLogger.logInfo("words_to_load: %x\n", words_to_load)
    ProtoaccLogger.logInfo("words_to_load_minus_one: %x\n", words_to_load_minus_one)
    ProtoaccLogger.logInfo("ADT_addr: %x\n", ADT_addr)
    ProtoaccLogger.logInfo("min_field_no: %x\n", min_field_no)
    ProtoaccLogger.logInfo("decoded_dest_base_addr: %x\n", io.proto_parse_info_cmd.bits.rs2)
  }

  val request_fire = DecoupledHelper(
    io.l1helperUser.req.ready,
    io.do_proto_parse_cmd.valid,
    io.proto_parse_info_cmd.valid,
    buf_info_queue.io.enq.ready,
    load_info_queue.io.enq.ready
  )

  io.l1helperUser.req.bits.cmd := M_XRD
  io.l1helperUser.req.bits.size := log2Ceil(16).U
  io.l1helperUser.req.bits.data := Bits(0)

  val addrinc = RegInit(UInt(0, 64.W))

  load_info_queue.io.enq.bits.start_byte := Mux(addrinc === UInt(0), base_addr_start_index, UInt(0))
  load_info_queue.io.enq.bits.end_byte := Mux(addrinc === words_to_load_minus_one, base_addr_end_index_inclusive, UInt(15))


  when (request_fire.fire() && (addrinc === words_to_load_minus_one)) {
    addrinc := UInt(0)
  } .elsewhen (request_fire.fire()) {
    addrinc := addrinc + UInt(1)
  }

  when (io.do_proto_parse_cmd.fire()) {
    ProtoaccLogger.logInfo("DO PROTO PARSE FIRE\n")
  }


  io.do_proto_parse_cmd.ready := request_fire.fire(io.do_proto_parse_cmd.valid,
                                            addrinc === words_to_load_minus_one)
  io.proto_parse_info_cmd.ready := request_fire.fire(io.proto_parse_info_cmd.valid,
                                            addrinc === words_to_load_minus_one)

  buf_info_queue.io.enq.valid := request_fire.fire(buf_info_queue.io.enq.ready,
                                            addrinc === UInt(0))
  load_info_queue.io.enq.valid := request_fire.fire(load_info_queue.io.enq.ready)

  buf_info_queue.io.enq.bits.len_bytes := base_len
  buf_info_queue.io.enq.bits.ADT_addr := ADT_addr
  buf_info_queue.io.enq.bits.min_field_no := min_field_no
  buf_info_queue.io.enq.bits.decoded_dest_base_addr := io.proto_parse_info_cmd.bits.rs2

  io.l1helperUser.req.bits.addr := (base_addr_bytes_aligned) + (addrinc << 4)
  io.l1helperUser.req.valid := request_fire.fire(io.l1helperUser.req.ready)





  val NUM_QUEUES = 16
  val QUEUE_DEPTHS = 16 * 4
  val write_start_index = RegInit(UInt(0, log2Up(NUM_QUEUES+1).W))
  val mem_resp_queues = Vec.fill(NUM_QUEUES)(Module(new Queue(UInt(8.W), QUEUE_DEPTHS)).io)



  val align_shamt = (load_info_queue.io.deq.bits.start_byte << 3)
  val memresp_bits_shifted = io.l1helperUser.resp.bits.data >> align_shamt

  for ( queueno <- 0 until NUM_QUEUES ) {
    mem_resp_queues((write_start_index +& UInt(queueno)) % UInt(NUM_QUEUES)).enq.bits := memresp_bits_shifted >> (queueno * 8)
  }

  val len_to_write = (load_info_queue.io.deq.bits.end_byte - load_info_queue.io.deq.bits.start_byte) +& UInt(1)

  val wrap_len_index_wide = write_start_index +& len_to_write
  val wrap_len_index_end = wrap_len_index_wide % UInt(NUM_QUEUES)
  val wrapped = wrap_len_index_wide >= UInt(NUM_QUEUES)

  when (load_info_queue.io.deq.valid) {
    ProtoaccLogger.logInfo("memloader start %x, end %x\n", load_info_queue.io.deq.bits.start_byte,
      load_info_queue.io.deq.bits.end_byte)
  }

  val resp_fire_noqueues = DecoupledHelper(
    io.l1helperUser.resp.valid,
    load_info_queue.io.deq.valid
  )
  val all_queues_ready = mem_resp_queues.map(_.enq.ready).reduce(_ && _)

  load_info_queue.io.deq.ready := resp_fire_noqueues.fire(load_info_queue.io.deq.valid, all_queues_ready)
  io.l1helperUser.resp.ready := resp_fire_noqueues.fire(io.l1helperUser.resp.valid, all_queues_ready)

  val resp_fire_allqueues = resp_fire_noqueues.fire() && all_queues_ready
  when (resp_fire_allqueues) {
    write_start_index := wrap_len_index_end
  }

  for ( queueno <- 0 until NUM_QUEUES ) {
    val use_this_queue = Mux(wrapped,
                             (UInt(queueno) >= write_start_index) || (UInt(queueno) < wrap_len_index_end),
                             (UInt(queueno) >= write_start_index) && (UInt(queueno) < wrap_len_index_end)
                            )
    mem_resp_queues(queueno).enq.valid := resp_fire_noqueues.fire() && use_this_queue && all_queues_ready
  }

  for ( queueno <- 0 until NUM_QUEUES ) {
    when (mem_resp_queues(queueno).deq.valid) {
      ProtoaccLogger.logInfo("queueind %d, val %x\n", UInt(queueno), mem_resp_queues(queueno).deq.bits)
    }
  }









  val read_start_index = RegInit(UInt(0, log2Up(NUM_QUEUES+1).W))


  val len_already_consumed = RegInit(UInt(0, 32.W))

  val remapVecData = Wire(Vec(NUM_QUEUES, UInt(8.W)))
  val remapVecValids = Wire(Vec(NUM_QUEUES, Bool()))
  val remapVecReadys = Wire(Vec(NUM_QUEUES, Bool()))


  for (queueno <- 0 until NUM_QUEUES) {
    val remapindex = (UInt(queueno) +& read_start_index) % UInt(NUM_QUEUES)
    remapVecData(queueno) := mem_resp_queues(remapindex).deq.bits
    remapVecValids(queueno) := mem_resp_queues(remapindex).deq.valid
    mem_resp_queues(remapindex).deq.ready := remapVecReadys(queueno)
  }
  io.consumer.output_data := Cat(remapVecData.reverse)


  val buf_last = (len_already_consumed + io.consumer.user_consumed_bytes) === buf_info_queue.io.deq.bits.len_bytes
  val count_valids = remapVecValids.map(_.asUInt).reduce(_ +& _)
  val unconsumed_bytes_so_far = buf_info_queue.io.deq.bits.len_bytes - len_already_consumed

  val enough_data = Mux(unconsumed_bytes_so_far >= UInt(NUM_QUEUES),
                        count_valids === UInt(NUM_QUEUES),
                        count_valids >= unconsumed_bytes_so_far)

  io.consumer.available_output_bytes := Mux(unconsumed_bytes_so_far >= UInt(NUM_QUEUES),
                                    UInt(NUM_QUEUES),
                                    unconsumed_bytes_so_far)

  io.consumer.output_last_chunk := (unconsumed_bytes_so_far <= UInt(NUM_QUEUES))

  val read_fire = DecoupledHelper(
    io.consumer.output_ready,
    buf_info_queue.io.deq.valid,
    enough_data
  )

  when (read_fire.fire()) {
    ProtoaccLogger.logInfo("MEMLOADER READ: bytesread %d\n", io.consumer.user_consumed_bytes)

  }

  io.consumer.output_valid := read_fire.fire(io.consumer.output_ready)

  for (queueno <- 0 until NUM_QUEUES) {
    remapVecReadys(queueno) := (UInt(queueno) < io.consumer.user_consumed_bytes) && read_fire.fire()
  }

  when (read_fire.fire()) {
    read_start_index := (read_start_index +& io.consumer.user_consumed_bytes) % UInt(NUM_QUEUES)
  }

  buf_info_queue.io.deq.ready := read_fire.fire(buf_info_queue.io.deq.valid) && buf_last
  io.consumer.output_ADT_addr := buf_info_queue.io.deq.bits.ADT_addr
  io.consumer.output_min_field_no := buf_info_queue.io.deq.bits.min_field_no
  io.consumer.output_decoded_dest_base_addr := buf_info_queue.io.deq.bits.decoded_dest_base_addr

  when (read_fire.fire()) {
    when (buf_last) {
      len_already_consumed := UInt(0)
    } .otherwise {
      len_already_consumed := len_already_consumed + io.consumer.user_consumed_bytes
    }
  }

}


