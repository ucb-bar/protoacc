package protoacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants

class CommandRouterSerializer()(implicit p: Parameters) extends Module {



  val FUNCT_SFENCE = UInt(0)
  val FUNCT_HASBITS_SETUP_INFO = UInt(1)
  val FUNCT_DO_PROTO_SERIALIZE = UInt(2)


  val FUNCT_MEM_SETUP = UInt(3)
  val FUNCT_CHECK_COMPLETION = UInt(4)

  val io = IO(new Bundle{
    val rocc_in = Decoupled(new RoCCCommand).flip
    val rocc_out = Decoupled(new RoCCResponse)

    val sfence_out = Bool(OUTPUT)


    val serializer_info_bundle_out = Decoupled(new SerializerInfoBundle)
    val dmem_status_out = Valid(new RoCCCommand)

    val stringalloc_region_addr_tail = Valid(UInt(64.W))
    val stringptr_region_addr = Valid(UInt(64.W))

    val no_writes_inflight = Input(Bool())
    val completed_toplevel_bufs = Input(UInt(64.W))

  })

  val track_number_dispatched_parse_commands = RegInit(0.U(64.W))
  when (io.rocc_in.fire()) {
    when (io.rocc_in.bits.inst.funct === FUNCT_DO_PROTO_SERIALIZE) {
      val next_track_number_dispatched_parse_commands = track_number_dispatched_parse_commands + 1.U
      track_number_dispatched_parse_commands := next_track_number_dispatched_parse_commands
      ProtoaccLogger.logInfo("dispatched bufs: current 0x%x, next 0x%x\n",
        track_number_dispatched_parse_commands,
        next_track_number_dispatched_parse_commands)
    }
  }

  when (io.rocc_in.fire()) {
    ProtoaccLogger.logInfo("gotcmd funct %x, rd %x, rs1val %x, rs2val %x\n", io.rocc_in.bits.inst.funct, io.rocc_in.bits.inst.rd, io.rocc_in.bits.rs1, io.rocc_in.bits.rs2)
  }

  io.dmem_status_out.bits <> io.rocc_in.bits
  io.dmem_status_out.valid := io.rocc_in.fire()

  val hasbits_setup_info_out_queue = Module(new Queue(new RoCCCommand, 2))
  val do_proto_serialize_out_queue = Module(new Queue(new RoCCCommand, 2))


  val ser_out_fire = DecoupledHelper(
    hasbits_setup_info_out_queue.io.deq.valid,
    do_proto_serialize_out_queue.io.deq.valid,
    io.serializer_info_bundle_out.ready
  )
  hasbits_setup_info_out_queue.io.deq.ready := ser_out_fire.fire(hasbits_setup_info_out_queue.io.deq.valid)
  do_proto_serialize_out_queue.io.deq.ready := ser_out_fire.fire(do_proto_serialize_out_queue.io.deq.valid)
  io.serializer_info_bundle_out.valid := ser_out_fire.fire(io.serializer_info_bundle_out.ready)

  io.serializer_info_bundle_out.bits.has_bits_base_offset_only := hasbits_setup_info_out_queue.io.deq.bits.rs1
  io.serializer_info_bundle_out.bits.min_fieldno := hasbits_setup_info_out_queue.io.deq.bits.rs2 >> 32
  io.serializer_info_bundle_out.bits.max_fieldno := hasbits_setup_info_out_queue.io.deq.bits.rs2
  io.serializer_info_bundle_out.bits.descriptor_table_addr := do_proto_serialize_out_queue.io.deq.bits.rs1
  io.serializer_info_bundle_out.bits.cpp_obj_addr := do_proto_serialize_out_queue.io.deq.bits.rs2

  val current_funct = io.rocc_in.bits.inst.funct

  val sfence_fire = DecoupledHelper(
    io.rocc_in.valid,
    current_funct === FUNCT_SFENCE
  )
  io.sfence_out := sfence_fire.fire()

  val hasbits_info_fire = DecoupledHelper(
    io.rocc_in.valid,
    hasbits_setup_info_out_queue.io.enq.ready,
    current_funct === FUNCT_HASBITS_SETUP_INFO
  )

  hasbits_setup_info_out_queue.io.enq.valid := hasbits_info_fire.fire(hasbits_setup_info_out_queue.io.enq.ready)

  val do_proto_serialize_fire = DecoupledHelper(
    io.rocc_in.valid,
    do_proto_serialize_out_queue.io.enq.ready,
    current_funct === FUNCT_DO_PROTO_SERIALIZE
  )

  do_proto_serialize_out_queue.io.enq.valid := do_proto_serialize_fire.fire(do_proto_serialize_out_queue.io.enq.ready)

  hasbits_setup_info_out_queue.io.enq.bits <> io.rocc_in.bits
  do_proto_serialize_out_queue.io.enq.bits <> io.rocc_in.bits

  val do_alloc_region_addr_fire = DecoupledHelper(
    io.rocc_in.valid,
    current_funct === FUNCT_MEM_SETUP
  )

  io.stringalloc_region_addr_tail.bits := io.rocc_in.bits.rs1
  io.stringalloc_region_addr_tail.valid := do_alloc_region_addr_fire.fire()

  io.stringptr_region_addr.bits := io.rocc_in.bits.rs2
  io.stringptr_region_addr.valid := do_alloc_region_addr_fire.fire()


  val do_check_completion_fire = DecoupledHelper(
    io.rocc_in.valid,
    current_funct === FUNCT_CHECK_COMPLETION,
    io.no_writes_inflight,
    io.completed_toplevel_bufs === track_number_dispatched_parse_commands,
    io.rocc_out.ready
  )

  when (io.rocc_in.valid && current_funct === FUNCT_CHECK_COMPLETION) {
    ProtoaccLogger.logInfo("[commandrouter] WAITING FOR COMPLETION. no_writes_inflight 0x%d, completed 0x%x, dispatched 0x%x, rocc_out.ready 0x%x\n",
      io.no_writes_inflight, io.completed_toplevel_bufs, track_number_dispatched_parse_commands, io.rocc_out.ready)
  }

  io.rocc_out.valid := do_check_completion_fire.fire(io.rocc_out.ready)
  io.rocc_out.bits.rd := io.rocc_in.bits.inst.rd
  io.rocc_out.bits.data := track_number_dispatched_parse_commands


  io.rocc_in.ready := sfence_fire.fire(io.rocc_in.valid) || hasbits_info_fire.fire(io.rocc_in.valid) || do_proto_serialize_fire.fire(io.rocc_in.valid) || do_alloc_region_addr_fire.fire(io.rocc_in.valid) || do_check_completion_fire.fire(io.rocc_in.valid)




}

