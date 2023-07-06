package protoacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants

class CommandRouter()(implicit p: Parameters) extends Module {



  val FUNCT_SFENCE = UInt(0)
  val FUNCT_PROTO_PARSE_INFO = UInt(1)
  val FUNCT_DO_PROTO_PARSE = UInt(2)
  val FUNCT_MEM_SETUP = UInt(3)
  val FUNCT_CHECK_COMPLETION = UInt(4)

  val io = IO(new Bundle{
    val rocc_in = Decoupled(new RoCCCommand).flip
    val rocc_out = Decoupled(new RoCCResponse)

    val sfence_out = Bool(OUTPUT)
    val proto_parse_info_out = Decoupled(new RoCCCommand)
    val do_proto_parse_out = Decoupled(new RoCCCommand)
    val dmem_status_out = Valid(new RoCCCommand)

    val fixed_alloc_region_addr = Valid(UInt(64.W))
    val array_alloc_region_addr = Valid(UInt(64.W))

    val no_writes_inflight = Input(Bool())
    val completed_toplevel_bufs = Input(UInt(64.W))

  })

  val track_number_dispatched_parse_commands = RegInit(0.U(64.W))
  when (io.rocc_in.fire()) {
    when (io.rocc_in.bits.inst.funct === FUNCT_DO_PROTO_PARSE) {
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

  val proto_parse_info_out_queue = Module(new Queue(new RoCCCommand, 2))
  val do_proto_parse_out_queue = Module(new Queue(new RoCCCommand, 2))

  io.proto_parse_info_out <> proto_parse_info_out_queue.io.deq
  io.do_proto_parse_out <> do_proto_parse_out_queue.io.deq

  val current_funct = io.rocc_in.bits.inst.funct

  val sfence_fire = DecoupledHelper(
    io.rocc_in.valid,
    current_funct === FUNCT_SFENCE
  )
  io.sfence_out := sfence_fire.fire()

  val proto_parse_info_fire = DecoupledHelper(
    io.rocc_in.valid,
    proto_parse_info_out_queue.io.enq.ready,
    current_funct === FUNCT_PROTO_PARSE_INFO
  )

  proto_parse_info_out_queue.io.enq.valid := proto_parse_info_fire.fire(proto_parse_info_out_queue.io.enq.ready)

  val do_proto_parse_fire = DecoupledHelper(
    io.rocc_in.valid,
    do_proto_parse_out_queue.io.enq.ready,
    current_funct === FUNCT_DO_PROTO_PARSE
  )

  do_proto_parse_out_queue.io.enq.valid := do_proto_parse_fire.fire(do_proto_parse_out_queue.io.enq.ready)

  proto_parse_info_out_queue.io.enq.bits <> io.rocc_in.bits
  do_proto_parse_out_queue.io.enq.bits <> io.rocc_in.bits

  val do_alloc_region_addr_fire = DecoupledHelper(
    io.rocc_in.valid,
    current_funct === FUNCT_MEM_SETUP
  )

  io.fixed_alloc_region_addr.bits := io.rocc_in.bits.rs1
  io.fixed_alloc_region_addr.valid := do_alloc_region_addr_fire.fire()

  io.array_alloc_region_addr.bits := io.rocc_in.bits.rs2
  io.array_alloc_region_addr.valid := do_alloc_region_addr_fire.fire()


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


  io.rocc_in.ready := sfence_fire.fire(io.rocc_in.valid) || proto_parse_info_fire.fire(io.rocc_in.valid) || do_proto_parse_fire.fire(io.rocc_in.valid) || do_alloc_region_addr_fire.fire(io.rocc_in.valid) || do_check_completion_fire.fire(io.rocc_in.valid)




}

