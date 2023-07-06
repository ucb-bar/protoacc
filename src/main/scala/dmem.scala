
package protoacc

import Chisel._
import org.chipsalliance.cde.config.{Field, Parameters}
import freechips.rocketchip.tile.HasCoreParameters
import freechips.rocketchip.rocket.{HellaCacheReq, TLB, TLBPTWIO, TLBConfig, MStatus, PRV}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._

case object ProtoTLB extends Field[Option[TLBConfig]](None)

class DmemModule(implicit p: Parameters) extends LazyModule {
  lazy val module = new DmemModuleImp(this)
  val node = TLClientNode(Seq(TLClientPortParameters(Seq(TLClientParameters("protoacc_tlbdmeminfo")))))
}

class DmemModuleImp(outer: DmemModule)(implicit p: Parameters) extends LazyModuleImp(outer)
  with HasCoreParameters {

  val io = IO(new Bundle {
    val req = Decoupled(new HellaCacheReq).flip
    val mem = Decoupled(new HellaCacheReq)
    val ptw = new TLBPTWIO
    val status = Valid(new MStatus).flip
    val sfence = Bool(INPUT)
  })

  val (tl, edge) = outer.node.out.head
  tl.a.valid := Bool(false)
  tl.b.ready := Bool(true)
  tl.c.valid := Bool(false)
  tl.d.ready := Bool(true)
  tl.e.valid := Bool(false)

  val status = Reg(new MStatus)
  when (io.status.valid) {
    printf("setting status.dprv to: %x compare %x\n", io.status.bits.dprv, UInt(PRV.M))
    status := io.status.bits
  }

  val tlb = Module(new TLB(false, log2Ceil(coreDataBytes), p(ProtoTLB).get)(edge, p))
  tlb.io.req.valid := io.req.valid
  tlb.io.req.bits.vaddr := io.req.bits.addr
  tlb.io.req.bits.size := io.req.bits.size
  tlb.io.req.bits.cmd := io.req.bits.cmd
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

  io.req.ready := io.mem.ready && tlb_ready

  io.mem.valid := io.req.valid && tlb_ready
  io.mem.bits := io.req.bits
  io.mem.bits.addr := tlb.io.resp.paddr

  io.mem.bits.phys := (status.dprv =/= UInt(PRV.M))



}
