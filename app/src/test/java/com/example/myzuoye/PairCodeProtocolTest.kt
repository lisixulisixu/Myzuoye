package com.example.myzuoye

import com.example.myzuoye.transfer.TransferProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * 配对码握手帧的编解码。
 *
 * 这几条断言看着琐碎，但它们是「开了校验之后连不上」这类问题的第一道防线：
 * 帧格式一旦写错，两端都会表现为「连上就断」，日志里看不出任何线索。
 */
class PairCodeProtocolTest {

    private fun roundTrip(write: (DataOutputStream) -> Unit): TransferProtocol.Frame {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(write)
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        return requireNotNull(TransferProtocol.readFrame(input))
    }

    @Test
    fun `报到帧能带出配对码`() {
        val frame = roundTrip { TransferProtocol.writeHello(it, "4821") }
        assertEquals(TransferProtocol.TYPE_HELLO, frame.type)
        assertEquals("4821", frame.asPairCode())
    }

    @Test
    fun `没填配对码时报到帧解析为空串`() {
        val frame = roundTrip { TransferProtocol.writeHello(it, "") }
        assertEquals("", frame.asPairCode())
    }

    @Test
    fun `非报到帧不会被误判成配对码`() {
        val frame = roundTrip { TransferProtocol.writeText(it, "hello") }
        assertNull(frame.asPairCode())
    }

    @Test
    fun `拒绝帧里带的原因能原样读出来`() {
        val frame = roundTrip { TransferProtocol.writePairReject(it, "配对码不正确") }
        assertEquals(TransferProtocol.TYPE_PAIR_REJECT, frame.type)
        assertEquals("配对码不正确", frame.asText())
    }

    @Test
    fun `超长原因会被截断而不是把帧撑大`() {
        val long = "x".repeat(2000)
        val frame = roundTrip { TransferProtocol.writePairReject(it, long) }
        // 截断到 512 字节，避免异常输入让对端分配一大块内存
        assertEquals(512, frame.payload.size)
    }

    @Test
    fun `协议版本已升到 3`() {
        assertTrue(
            "报到帧/拒绝帧是 v3 才有的，版本号必须跟着升",
            TransferProtocol.VERSION >= 3
        )
    }
}
