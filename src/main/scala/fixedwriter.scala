package protoacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants

class FixedWriterRequest extends Bundle {
  val write_width = UInt(3.W)
  val write_data = UInt(128.W)
  val write_addr = UInt(128.W)
}


class FixedWriter()(implicit p: Parameters) extends Module
  with MemoryOpConstants {
  val io = IO(new Bundle {
    val fixed_writer_request = Decoupled(new FixedWriterRequest).flip

    val l1helperUser = new L1MemHelperBundle
    val no_writes_inflight = Output(Bool())
  })

  val l1reqQueue = Module(new Queue(new L1ReqInternal, 4))
  io.l1helperUser.req <> l1reqQueue.io.deq


  l1reqQueue.io.enq.bits.cmd := M_XWR

  io.no_writes_inflight := io.l1helperUser.no_memops_inflight && (l1reqQueue.io.count === 0.U)

  l1reqQueue.io.enq.bits.addr := io.fixed_writer_request.bits.write_addr
  l1reqQueue.io.enq.bits.size := io.fixed_writer_request.bits.write_width
  l1reqQueue.io.enq.bits.data := io.fixed_writer_request.bits.write_data

  l1reqQueue.io.enq.valid := io.fixed_writer_request.valid
  io.fixed_writer_request.ready := l1reqQueue.io.enq.ready

  io.l1helperUser.resp.ready := Bool(true)
}

