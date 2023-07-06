package protoacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, TLBPTWIO, TLB, MStatus, PRV}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.rocket.{RAS}
import freechips.rocketchip.tilelink._

class L1MemHelperWriteFast(printInfo: String = "", numOutstandingReqs: Int = 64, queueRequests: Boolean = false)(implicit p: Parameters) extends LazyModule {
  val numOutstandingRequestsAllowed = numOutstandingReqs
  val tlTagBits = log2Ceil(numOutstandingRequestsAllowed)

  lazy val module = new L1MemHelperWriteFastModule(this, printInfo, queueRequests)
  val masterNode = TLClientNode(Seq(TLClientPortParameters(
    Seq(TLClientParameters(name = printInfo, sourceId = IdRange(0,
      numOutstandingRequestsAllowed)))
  )))
}

class L1MemHelperWriteFastModule(outer: L1MemHelperWriteFast, printInfo: String = "", queueRequests: Boolean = false)(implicit p: Parameters) extends LazyModuleImp(outer)
  with HasCoreParameters
  with MemoryOpConstants {

  val io = IO(new Bundle {
    val userif = Flipped(new L1MemHelperBundle)

    val sfence = Bool(INPUT)
    val ptw = new TLBPTWIO
    val status = Valid(new MStatus).flip
  })

  val (dmem, edge) = outer.masterNode.out.head

  val request_input = Wire(Decoupled(new L1ReqInternal))
  if (!queueRequests) {
    request_input <> io.userif.req
  } else {
    val requestQueue = Module(new Queue(new L1ReqInternal, 4))
    request_input <> requestQueue.io.deq
    requestQueue.io.enq <> io.userif.req
  }

  val status = Reg(new MStatus)
  when (io.status.valid) {
    ProtoaccLogger.logInfo(printInfo + " setting status.dprv to: %x compare %x\n", io.status.bits.dprv, UInt(PRV.M))
    status := io.status.bits
  }

  val tlb = Module(new TLB(false, log2Ceil(coreDataBytes), p(ProtoTLB).get)(edge, p))
  tlb.io.req.valid := request_input.valid
  tlb.io.req.bits.vaddr := request_input.bits.addr
  tlb.io.req.bits.size := request_input.bits.size
  tlb.io.req.bits.cmd := request_input.bits.cmd
  tlb.io.req.bits.passthrough := Bool(false)
  val tlb_ready = tlb.io.req.ready && !tlb.io.resp.miss

  io.ptw <> tlb.io.ptw
  tlb.io.ptw.status := status
  tlb.io.sfence.valid := io.sfence
  tlb.io.sfence.bits.rs1 := Bool(false)
  tlb.io.sfence.bits.rs2 := Bool(false)
  tlb.io.sfence.bits.addr := UInt(0)
  tlb.io.sfence.bits.asid := UInt(0)
  tlb.io.kill := Bool(false)

  val tags_for_issue_Q = Module(new Queue(UInt(outer.tlTagBits.W), outer.numOutstandingRequestsAllowed + 4))
  tags_for_issue_Q.io.enq.valid := false.B

  val tags_init_reg = RegInit(0.U((outer.tlTagBits+1).W))
  when (tags_init_reg =/= (outer.numOutstandingRequestsAllowed).U) {
    tags_for_issue_Q.io.enq.bits := tags_init_reg
    tags_for_issue_Q.io.enq.valid := true.B
    when (tags_for_issue_Q.io.enq.ready) {
      ProtoaccLogger.logInfo(printInfo + " tags_for_issue_Q init with value %d\n", tags_for_issue_Q.io.enq.bits)
      tags_init_reg := tags_init_reg + 1.U
    }
  }

  val addr_mask_check = (UInt(0x1, 64.W) << request_input.bits.size) - UInt(1)
  val assertcheck = RegNext((!request_input.valid) || ((request_input.bits.addr & addr_mask_check) === UInt(0)))
  assert(assertcheck,
    printInfo + " L2IF: access addr must be aligned to write width\n")

  val global_memop_accepted = RegInit(0.U(64.W))
  when (io.userif.req.fire()) {
    global_memop_accepted := global_memop_accepted + 1.U
  }

  val global_memop_sent = RegInit(0.U(64.W))

  val global_memop_ackd = RegInit(0.U(64.W))

  io.userif.no_memops_inflight := global_memop_accepted === global_memop_ackd

  val free_outstanding_op_slots = (global_memop_sent - global_memop_ackd) < (1 << outer.tlTagBits).U
  val assert_free_outstanding_op_slots = (global_memop_sent - global_memop_ackd) <= (1 << outer.tlTagBits).U

  assert(assert_free_outstanding_op_slots,
    printInfo + " L2IF: Too many outstanding requests for tag count.\n")

  when (request_input.fire()) {
    global_memop_sent := global_memop_sent + 1.U
  }

  val sendtag = tags_for_issue_Q.io.deq.bits

  when (request_input.bits.cmd === M_XRD) {
    val (legal, bundle) = edge.Get(fromSource=sendtag,
                            toAddress=tlb.io.resp.paddr,
                            lgSize=request_input.bits.size)
    dmem.a.bits := bundle
  } .elsewhen (request_input.bits.cmd === M_XWR) {
    val (legal, bundle) = edge.Put(fromSource=sendtag,
                            toAddress=tlb.io.resp.paddr,
                            lgSize=request_input.bits.size,
                            data=request_input.bits.data << ((request_input.bits.addr(3, 0) << 3)))
    dmem.a.bits := bundle
  } .elsewhen (request_input.valid) {

    assert(Bool(false), "ERR")
  }

  val fire_req = DecoupledHelper(
    request_input.valid,
    dmem.a.ready,
    tlb_ready,
    free_outstanding_op_slots,
    tags_for_issue_Q.io.deq.valid,
  )

  dmem.a.valid := fire_req.fire(dmem.a.ready)
  request_input.ready := fire_req.fire(request_input.valid)
  tags_for_issue_Q.io.deq.ready := fire_req.fire(tags_for_issue_Q.io.deq.valid)

  when (dmem.a.fire()) {
    when (request_input.bits.cmd === M_XRD) {
      assert(false.B, printInfo + " L2IF: ERR: NO READS IN THIS MODULE!\n")
    }
    when (request_input.bits.cmd === M_XWR) {
      ProtoaccLogger.logInfo(printInfo + " L2IF: req(write) vaddr: 0x%x, paddr: 0x%x, wid: 0x%x, data: 0x%x, opnum: %d, sendtag: %d\n",
        request_input.bits.addr,
        tlb.io.resp.paddr,
        request_input.bits.size,
        request_input.bits.data,
        global_memop_sent,
        sendtag)
    }
  }

  val fire_actual_mem_resp = DecoupledHelper(
    dmem.d.valid,
    tags_for_issue_Q.io.enq.ready
  )

  when (fire_actual_mem_resp.fire(tags_for_issue_Q.io.enq.ready)) {
    tags_for_issue_Q.io.enq.valid := true.B
    tags_for_issue_Q.io.enq.bits := dmem.d.bits.source
  }

  when (fire_actual_mem_resp.fire(tags_for_issue_Q.io.enq.ready) &&
    tags_for_issue_Q.io.enq.valid) {
      ProtoaccLogger.logInfo(printInfo + " tags_for_issue_Q add back tag %d\n", tags_for_issue_Q.io.enq.bits)
  }

  dmem.d.ready := fire_actual_mem_resp.fire(dmem.d.valid)

  io.userif.resp.bits.data := 0.U
  io.userif.resp.valid := false.B

  when (dmem.d.fire()) {
    when (edge.hasData(dmem.d.bits)) {
      assert(false.B, printInfo + " L2IF: ERR: NO READS IN THIS MODULE!\n")
    } .otherwise {
      ProtoaccLogger.logInfo(printInfo + " L2IF: resp(write) opnum: %d, gettag: %d\n",
        global_memop_ackd,
        dmem.d.bits.source)
    }
  }

  when (dmem.d.fire()) {
    global_memop_ackd := global_memop_ackd + 1.U
  }
}
