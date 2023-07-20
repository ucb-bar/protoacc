package protoacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._

case object ProtoAccelPrintfEnable extends Field[Boolean](false)

class ProtoAccel(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(
    opcodes = opcodes, nPTWPorts = 4) {
  override lazy val module = new ProtoAccelImp(this)

  val tapeout = true
  val roccTLNode = if (tapeout) atlNode else tlNode


  val mem_descr = LazyModule(new L1MemHelper("[m_descr]", numOutstandingReqs=4))
  roccTLNode := TLBuffer.chainNode(1) := mem_descr.masterNode
  val mem_memloader = LazyModule(new L1MemHelper("[m_memloader]", numOutstandingReqs=64, queueResponses=true))
  roccTLNode := TLBuffer.chainNode(1) := mem_memloader.masterNode
  val mem_hasbits = LazyModule(new L1MemHelper(printInfo="[m_hasbits]", queueRequests=true))
  roccTLNode := TLBuffer.chainNode(1) := mem_hasbits.masterNode
  val mem_fixedwriter = LazyModule(new L1MemHelperWriteFast(printInfo="[m_fixedwriter]", queueRequests=true))
  roccTLNode := TLBuffer.chainNode(1) := mem_fixedwriter.masterNode
}


class ProtoAccelImp(outer: ProtoAccel)(implicit p: Parameters) extends LazyRoCCModuleImp(outer)
with MemoryOpConstants {

  io.interrupt := Bool(false)

  val cmd_router = Module(new CommandRouter)
  cmd_router.io.rocc_in <> io.cmd
  io.resp <> cmd_router.io.rocc_out

  io.mem.req.valid := false.B
  io.mem.s1_kill := false.B
  io.mem.s2_kill := false.B
  io.mem.keep_clock_enabled := true.B

  val memloader = Module(new MemLoader)
  memloader.io.do_proto_parse_cmd <> cmd_router.io.do_proto_parse_out
  memloader.io.proto_parse_info_cmd <> cmd_router.io.proto_parse_info_out

  outer.mem_memloader.module.io.userif <> memloader.io.l1helperUser
  outer.mem_memloader.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_memloader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_memloader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(0) <> outer.mem_memloader.module.io.ptw

  val field_handler = Module(new FieldHandler)

  outer.mem_descr.module.io.userif <> field_handler.io.l1helperUser
  outer.mem_descr.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_descr.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_descr.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(1) <> outer.mem_descr.module.io.ptw

  field_handler.io.consumer <> memloader.io.consumer
  field_handler.io.fixed_alloc_region_addr <> cmd_router.io.fixed_alloc_region_addr
  field_handler.io.array_alloc_region_addr <> cmd_router.io.array_alloc_region_addr

  cmd_router.io.completed_toplevel_bufs := field_handler.io.completed_toplevel_bufs

  val fixed_writer = Module(new FixedWriter)
  outer.mem_fixedwriter.module.io.userif <>  fixed_writer.io.l1helperUser
  outer.mem_fixedwriter.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_fixedwriter.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_fixedwriter.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(2) <> outer.mem_fixedwriter.module.io.ptw

  fixed_writer.io.fixed_writer_request <> field_handler.io.fixed_writer_request

  outer.mem_hasbits.module.io.userif <>  field_handler.io.l1helperUser2
  outer.mem_hasbits.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_hasbits.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_hasbits.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(3) <> outer.mem_hasbits.module.io.ptw

  cmd_router.io.no_writes_inflight := fixed_writer.io.no_writes_inflight && outer.mem_hasbits.module.io.userif.no_memops_inflight

  io.busy := Bool(false)
}

class WithProtoAccel extends Config ((site, here, up) => {
  case ProtoTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val protoacc = LazyModule.apply(new ProtoAccel(OpcodeSet.custom2)(p))
      protoacc
    },
    (p: Parameters) => {
      val protoaccser = LazyModule.apply(new ProtoAccelSerializer(OpcodeSet.custom3)(p))
      protoaccser
    }
  )
})

class WithProtoAccelSerOnly extends Config ((site, here, up) => {
  case ProtoTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val protoaccser = LazyModule.apply(new ProtoAccelSerializer(OpcodeSet.custom3)(p))
      protoaccser
    }
  )
})

class WithProtoAccelDeserOnly extends Config ((site, here, up) => {
  case ProtoTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val protoacc = LazyModule.apply(new ProtoAccel(OpcodeSet.custom2)(p))
      protoacc
    }
  )
})

class WithProtoAccelPrintf extends Config((site, here, up) => {
  case ProtoAccelPrintfEnable => true
})

