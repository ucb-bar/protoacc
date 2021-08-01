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

class ProtoAccelSerializer(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(
    opcodes = opcodes, nPTWPorts = 9) {
  override lazy val module = new ProtoAccelSerializerImp(this)

  val mem_descr1 = LazyModule(new L1MemHelper(printInfo="[m_serdescr1]", queueRequests=true, queueResponses=true))
  tlNode := mem_descr1.masterNode
  val mem_descr2 = LazyModule(new L1MemHelper(printInfo="[m_serdescr2]", queueRequests=true))
  tlNode := mem_descr2.masterNode

  val mem_serfieldhandler1 = LazyModule(new L1MemHelper(printInfo="[m_serfieldhandler1]", queueRequests=true, queueResponses=true))
  tlNode := mem_serfieldhandler1.masterNode
  val mem_serfieldhandler2 = LazyModule(new L1MemHelper(printInfo="[m_serfieldhandler2]", queueRequests=true, queueResponses=true))
  tlNode := mem_serfieldhandler2.masterNode
  val mem_serfieldhandler3 = LazyModule(new L1MemHelper(printInfo="[m_serfieldhandler3]", queueRequests=true, queueResponses=true))
  tlNode := mem_serfieldhandler3.masterNode
  val mem_serfieldhandler4 = LazyModule(new L1MemHelper(printInfo="[m_serfieldhandler4]", queueRequests=true, queueResponses=true))
  tlNode := mem_serfieldhandler4.masterNode
  val mem_serfieldhandler5 = LazyModule(new L1MemHelper(printInfo="[m_serfieldhandler5]", queueRequests=true, queueResponses=true))
  tlNode := mem_serfieldhandler5.masterNode
  val mem_serfieldhandler6 = LazyModule(new L1MemHelper(printInfo="[m_serfieldhandler6]", queueRequests=true, queueResponses=true))
  tlNode := mem_serfieldhandler6.masterNode

  val mem_serwriter = LazyModule(new L1MemHelperWriteFast(printInfo="[m_serwriter]", queueRequests=true))
  tlNode := mem_serwriter.masterNode
}

class ProtoAccelSerializerImp(outer: ProtoAccelSerializer)(implicit p: Parameters) extends LazyRoCCModuleImp(outer)
with MemoryOpConstants {

  io.interrupt := Bool(false)

  val cmd_router = Module(new CommandRouterSerializer)
  cmd_router.io.rocc_in <> io.cmd
  io.resp <> cmd_router.io.rocc_out

  io.mem.req.valid := false.B
  io.mem.s1_kill := false.B
  io.mem.s2_kill := false.B
  io.mem.keep_clock_enabled := true.B

  val ser_descr_tab = Module(new SerDescriptorTableHandler)
  outer.mem_descr1.module.io.userif <> ser_descr_tab.io.l2helperUser1
  outer.mem_descr1.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_descr1.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_descr1.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(0) <> outer.mem_descr1.module.io.ptw

  outer.mem_descr2.module.io.userif <> ser_descr_tab.io.l2helperUser2
  outer.mem_descr2.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_descr2.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_descr2.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(1) <> outer.mem_descr2.module.io.ptw

  ser_descr_tab.io.serializer_cmd_in <> cmd_router.io.serializer_info_bundle_out

  val descr_to_fieldhandler_router = Module(new FieldDispatchRouter(6))
  descr_to_fieldhandler_router.io.fields_req_in <> ser_descr_tab.io.ser_field_handler_output
  val fieldhandler_to_memwriter_arbiter = Module(new MemWriteArbiter(6))

  val ser_field_handler1 = Module(new SerFieldHandler("[serfieldhandler1]"))
  ser_field_handler1.io.ops_in <> descr_to_fieldhandler_router.io.to_fieldhandlers(0)
  outer.mem_serfieldhandler1.module.io.userif <> ser_field_handler1.io.memread
  outer.mem_serfieldhandler1.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_serfieldhandler1.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_serfieldhandler1.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(2) <> outer.mem_serfieldhandler1.module.io.ptw
  fieldhandler_to_memwriter_arbiter.io.from_fieldhandlers(0) <> ser_field_handler1.io.writer_output

  val ser_field_handler2 = Module(new SerFieldHandler("[serfieldhandler2]"))
  ser_field_handler2.io.ops_in <> descr_to_fieldhandler_router.io.to_fieldhandlers(1)
  outer.mem_serfieldhandler2.module.io.userif <> ser_field_handler2.io.memread
  outer.mem_serfieldhandler2.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_serfieldhandler2.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_serfieldhandler2.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(4) <> outer.mem_serfieldhandler2.module.io.ptw
  fieldhandler_to_memwriter_arbiter.io.from_fieldhandlers(1) <> ser_field_handler2.io.writer_output

  val ser_field_handler3 = Module(new SerFieldHandler("[serfieldhandler3]"))
  ser_field_handler3.io.ops_in <> descr_to_fieldhandler_router.io.to_fieldhandlers(3-1)
  outer.mem_serfieldhandler3.module.io.userif <> ser_field_handler3.io.memread
  outer.mem_serfieldhandler3.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_serfieldhandler3.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_serfieldhandler3.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(5) <> outer.mem_serfieldhandler3.module.io.ptw
  fieldhandler_to_memwriter_arbiter.io.from_fieldhandlers(3-1) <> ser_field_handler3.io.writer_output

  val ser_field_handler4 = Module(new SerFieldHandler("[serfieldhandler4]"))
  ser_field_handler4.io.ops_in <> descr_to_fieldhandler_router.io.to_fieldhandlers(4-1)
  outer.mem_serfieldhandler4.module.io.userif <> ser_field_handler4.io.memread
  outer.mem_serfieldhandler4.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_serfieldhandler4.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_serfieldhandler4.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(6) <> outer.mem_serfieldhandler4.module.io.ptw
  fieldhandler_to_memwriter_arbiter.io.from_fieldhandlers(4-1) <> ser_field_handler4.io.writer_output

  val ser_field_handler5 = Module(new SerFieldHandler("[serfieldhandler5]"))
  ser_field_handler5.io.ops_in <> descr_to_fieldhandler_router.io.to_fieldhandlers(5-1)
  outer.mem_serfieldhandler5.module.io.userif <> ser_field_handler5.io.memread
  outer.mem_serfieldhandler5.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_serfieldhandler5.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_serfieldhandler5.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(7) <> outer.mem_serfieldhandler5.module.io.ptw
  fieldhandler_to_memwriter_arbiter.io.from_fieldhandlers(5-1) <> ser_field_handler5.io.writer_output

  val ser_field_handler6 = Module(new SerFieldHandler("[serfieldhandler6]"))
  ser_field_handler6.io.ops_in <> descr_to_fieldhandler_router.io.to_fieldhandlers(6-1)
  outer.mem_serfieldhandler6.module.io.userif <> ser_field_handler6.io.memread
  outer.mem_serfieldhandler6.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_serfieldhandler6.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_serfieldhandler6.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(8) <> outer.mem_serfieldhandler6.module.io.ptw
  fieldhandler_to_memwriter_arbiter.io.from_fieldhandlers(6-1) <> ser_field_handler6.io.writer_output

  val ser_memwriter = Module(new SerMemwriter)
  ser_memwriter.io.stringobj_output_addr <> cmd_router.io.stringalloc_region_addr_tail
  ser_memwriter.io.string_ptr_output_addr <> cmd_router.io.stringptr_region_addr
  ser_memwriter.io.memwrites_in <> fieldhandler_to_memwriter_arbiter.io.write_reqs_out
  ser_memwriter.io.l2io.resp <> outer.mem_serwriter.module.io.userif.resp
  ser_memwriter.io.l2io.no_memops_inflight := outer.mem_serwriter.module.io.userif.no_memops_inflight
  outer.mem_serwriter.module.io.userif.req <> ser_memwriter.io.l2io.req
  outer.mem_serwriter.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_serwriter.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_serwriter.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(3) <> outer.mem_serwriter.module.io.ptw

  cmd_router.io.no_writes_inflight := !(ser_memwriter.io.mem_work_outstanding)
  cmd_router.io.completed_toplevel_bufs := ser_memwriter.io.messages_completed
  io.busy := Bool(false)
}
