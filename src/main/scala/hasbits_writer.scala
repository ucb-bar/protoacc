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


class HasBitsWriteRequest extends Bundle {
  val hasbits_base_addr = UInt(64.W)
  val relative_fieldno = UInt(32.W)
  val flushonly = Bool()
}


class HasBitsWriter()(implicit p: Parameters) extends Module
  with MemoryOpConstants {

  val io = IO(new Bundle {
    val requestin = Decoupled(new HasBitsWriteRequest).flip

    val l1helperUser = new L1MemHelperBundle
  })



  val hasbits_req_buffer = Module(new Queue(new HasBitsWriteRequest, 10))
  hasbits_req_buffer.io.enq <> io.requestin

  val logprefix = "[hasbitswriter]"


  val have_in_progress_chunk = RegInit(false.B)
  val in_progress_chunk = RegInit(0.U(32.W))
  val in_progress_chunkno = RegInit(0.U(32.W))
  val in_progress_chunk_hasbits_base_addr = RegInit(0.U(64.W))
  val in_progress_writeaddr = in_progress_chunk_hasbits_base_addr +
                              (in_progress_chunkno << 2)


  val input_bitindex = hasbits_req_buffer.io.deq.bits.relative_fieldno + 1.U
  val input_chunkno = input_bitindex >> 5
  val input_intrachunk_bitindex = input_bitindex & (0x1F).U(64.W)
  val input_readaddr = hasbits_req_buffer.io.deq.bits.hasbits_base_addr +
                       (input_chunkno << 2)
  val input_is_fresh_chunk_assuming_inorder =
    (input_intrachunk_bitindex === 0.U) ||
    ((input_chunkno === 0.U) &&
      (input_intrachunk_bitindex === 1.U))
  val input_chunk_OR_bit = (1.U(32.W) << input_intrachunk_bitindex(4, 0))

  val chunkno_match = (in_progress_chunkno === input_chunkno)
  val cpp_addr_match = (in_progress_chunk_hasbits_base_addr ===
                        hasbits_req_buffer.io.deq.bits.hasbits_base_addr)
  val chunk_match = have_in_progress_chunk && chunkno_match && cpp_addr_match

  when (hasbits_req_buffer.io.deq.valid) {
    ProtoaccLogger.logInfo(logprefix + " hasbits write req. hasbits_base_addr 0x%x, relative_fieldno %d, flushonly %d, input_bitindex %d, input_chunkno %d, input_intrachunk_bitindex %d, input_readaddr 0x%x, is_fresh %d, input_chunk_OR_bit 0x%x, chunkno_match %d, cpp_addr_match %d, chunk_match %d\n",
      hasbits_req_buffer.io.deq.bits.hasbits_base_addr,
      hasbits_req_buffer.io.deq.bits.relative_fieldno,
      hasbits_req_buffer.io.deq.bits.flushonly,
      input_bitindex,
      input_chunkno,
      input_intrachunk_bitindex,
      input_readaddr,
      input_is_fresh_chunk_assuming_inorder,
      input_chunk_OR_bit,
      chunkno_match,
      cpp_addr_match,
      chunk_match
    )
  }
  when (hasbits_req_buffer.io.deq.fire()) {
    ProtoaccLogger.logInfo(logprefix + " hasbits write req. consumed\n")
  }

  val sAccept = 0.U(4.W)
  val sHandleLoad = 2.U(4.W)
  val sHandleWriteAck = 3.U(4.W)
  val sHandleLoadResp = 4.U(4.W)

  hasbits_req_buffer.io.deq.ready := false.B
  io.l1helperUser.req.valid := false.B
  io.l1helperUser.resp.ready := false.B
  io.l1helperUser.req.bits.size := 2.U
  io.l1helperUser.req.bits.data := in_progress_chunk

  val issued_write = RegInit(false.B)

  val fieldState = RegInit(sAccept)
  switch (fieldState) {
    is (sAccept) {
      when (chunk_match && !(hasbits_req_buffer.io.deq.bits.flushonly)) {
        when (hasbits_req_buffer.io.deq.valid) {
          val new_in_progress_chunk = in_progress_chunk | input_chunk_OR_bit
          in_progress_chunk := new_in_progress_chunk
          ProtoaccLogger.logInfo(logprefix + " chunk already present. new val 0x%x\n", new_in_progress_chunk)
        }
        hasbits_req_buffer.io.deq.ready := true.B
      } .otherwise {
        when (have_in_progress_chunk) {
          io.l1helperUser.req.bits.cmd := M_XWR
          io.l1helperUser.req.bits.addr := in_progress_writeaddr
          io.l1helperUser.req.valid := hasbits_req_buffer.io.deq.valid
          when (io.l1helperUser.req.ready && hasbits_req_buffer.io.deq.valid) {
            issued_write := true.B
            when (hasbits_req_buffer.io.deq.bits.flushonly) {
              have_in_progress_chunk := false.B
              fieldState := sHandleWriteAck
            } .otherwise {
              fieldState := sHandleLoad
            }
            ProtoaccLogger.logInfo(logprefix + " no chunk match. flushing old to mem: have_in_progress_chunk %d, in_progress_chunk 0x%x, in_progress_chunkno %d, in_progress_chunk_hasbits_base_addr 0x%x, writeaddr 0x%x\n",
              have_in_progress_chunk,
              io.l1helperUser.req.bits.data,
              in_progress_chunkno,
              in_progress_chunk_hasbits_base_addr,
              io.l1helperUser.req.bits.addr
            )
          }
        } .otherwise {
          when (hasbits_req_buffer.io.deq.valid) {
            ProtoaccLogger.logInfo(logprefix + " no chunk match. nothing to flush.\n")
            issued_write := false.B
            when (!hasbits_req_buffer.io.deq.bits.flushonly) {
              fieldState := sHandleLoad
            }
          }
          hasbits_req_buffer.io.deq.ready := hasbits_req_buffer.io.deq.bits.flushonly
        }
      }
    }
    is (sHandleLoad) {
      io.l1helperUser.req.bits.cmd := M_XRD
      io.l1helperUser.req.bits.addr := input_readaddr
      io.l1helperUser.req.valid := true.B
      when (io.l1helperUser.req.ready) {

        when (issued_write) {
          ProtoaccLogger.logInfo(logprefix + " issue chunk load. loadaddr 0x%x. next: sHandleWriteAck\n", io.l1helperUser.req.bits.addr)
          fieldState := sHandleWriteAck
        } .otherwise {
          ProtoaccLogger.logInfo(logprefix + " issue chunk load. loadaddr 0x%x. next: sHandleLoadResp\n", io.l1helperUser.req.bits.addr)
          fieldState := sHandleLoadResp
        }
      }
    }
    is (sHandleWriteAck) {
      io.l1helperUser.resp.ready := true.B
      when (io.l1helperUser.resp.valid) {
        ProtoaccLogger.logInfo(logprefix + " got write ack.\n")
        when (hasbits_req_buffer.io.deq.bits.flushonly) {
          fieldState := sAccept
        } .otherwise {
          fieldState := sHandleLoadResp
        }
        hasbits_req_buffer.io.deq.ready := hasbits_req_buffer.io.deq.bits.flushonly
      }
    }
    is (sHandleLoadResp) {
      io.l1helperUser.resp.ready := true.B
      when (io.l1helperUser.resp.valid) {
        hasbits_req_buffer.io.deq.ready := true.B
        have_in_progress_chunk := true.B
        val full_new_in_progress_chunk = io.l1helperUser.resp.bits.data | input_chunk_OR_bit
        in_progress_chunk := full_new_in_progress_chunk
        in_progress_chunkno := input_chunkno
        in_progress_chunk_hasbits_base_addr := hasbits_req_buffer.io.deq.bits.hasbits_base_addr
        fieldState := sAccept

        ProtoaccLogger.logInfo(logprefix + " got load resp. resp chunk 0x%x, input_chunk_OR_bit 0x%x, full_new_chunk 0x%x\n",
          io.l1helperUser.resp.bits.data,
          input_chunk_OR_bit,
          full_new_in_progress_chunk
        )

      }
    }
  }

}
