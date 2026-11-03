package com.example.myzuoye

import com.example.myzuoye.transfer.Checksum
import com.example.myzuoye.transfer.TransferProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 帧编解码的单元测试。协议层是纯 JVM 代码，不依赖 Android 运行时。
 */
class TransferProtocolTest {

    @Test
    fun textFrameRoundTrip() {
        val buffer = ByteArrayOutputStream()
        TransferProtocol.writeText(DataOutputStream(buffer), "你好 P2P")

        val frame = TransferProtocol.readFrame(
            DataInputStream(ByteArrayInputStream(buffer.toByteArray()))
        )

        assertEquals(TransferProtocol.TYPE_TEXT, frame!!.type)
        assertEquals("你好 P2P", frame.asText())
    }

    @Test
    fun multipleFramesAreReadInOrder() {
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        TransferProtocol.writeText(out, "first")
        TransferProtocol.writeText(out, "second")

        val input = DataInputStream(ByteArrayInputStream(buffer.toByteArray()))
        assertEquals("first", TransferProtocol.readFrame(input)!!.asText())
        assertEquals("second", TransferProtocol.readFrame(input)!!.asText())
        // 两条读完后应当正常返回 null，而不是抛异常
        assertNull(TransferProtocol.readFrame(input))
    }

    @Test
    fun emptyStreamReturnsNull() {
        assertNull(
            TransferProtocol.readFrame(DataInputStream(ByteArrayInputStream(ByteArray(0))))
        )
    }

    @Test
    fun oversizedLengthIsRejected() {
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        out.writeByte(TransferProtocol.TYPE_TEXT.toInt())
        out.writeInt(64 * 1024 * 1024)
        out.flush()

        assertThrows(IllegalStateException::class.java) {
            TransferProtocol.readFrame(
                DataInputStream(ByteArrayInputStream(buffer.toByteArray()))
            )
        }
    }

    /**
     * 用真实 ServerSocket / Socket 走一遍双向收发，验证帧协议在 TCP 流上不会串包。
     */
    @Test
    fun textFramesOverRealSocket() {
        ServerSocket(0).use { server ->
            val port = server.localPort
            val receivedByServer = CompletableFuture<String>()

            val serverThread = thread(name = "test-server") {
                server.accept().use { client ->
                    val input = DataInputStream(client.getInputStream())
                    val out = DataOutputStream(client.getOutputStream())
                    // 先回一条，再读客户端发来的
                    TransferProtocol.writeText(out, "pong")
                    val frame = TransferProtocol.readFrame(input)
                    receivedByServer.complete(frame?.asText() ?: "")
                }
            }

            Socket("127.0.0.1", port).use { client ->
                val out = DataOutputStream(client.getOutputStream())
                val input = DataInputStream(client.getInputStream())
                TransferProtocol.writeText(out, "ping")
                val reply = TransferProtocol.readFrame(input)
                assertEquals("pong", reply?.asText())
            }

            serverThread.join(3000)
            assertEquals("ping", receivedByServer.get(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun fileHeaderRoundTrip() {
        val buffer = ByteArrayOutputStream()
        val header = TransferProtocol.FileHeader("测试文档.txt", 123456L, "0123456789abcdef0123456789abcdef")
        TransferProtocol.writeFileHeader(DataOutputStream(buffer), header)

        val frame = TransferProtocol.readFrame(
            DataInputStream(ByteArrayInputStream(buffer.toByteArray()))
        )
        assertEquals(TransferProtocol.TYPE_FILE_HEADER, frame!!.type)

        val parsed = frame.asFileHeader()
        assertEquals("测试文档.txt", parsed.fileName)
        assertEquals(123456L, parsed.fileSize)
        assertEquals("0123456789ABCDEF0123456789ABCDEF", parsed.md5Hex)
    }

    @Test
    fun fileChunkAndEndRoundTrip() {
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)

        val chunk = ByteArray(TransferProtocol.CHUNK_SIZE) { (it % 256).toByte() }
        TransferProtocol.writeFileChunk(out, chunk)
        TransferProtocol.writeFileEnd(out, chunk.size.toLong())

        val input = DataInputStream(ByteArrayInputStream(buffer.toByteArray()))
        val chunkFrame = TransferProtocol.readFrame(input)!!
        assertEquals(TransferProtocol.TYPE_FILE_CHUNK, chunkFrame.type)
        assertEquals(chunk.size, chunkFrame.payload.size)

        val endFrame = TransferProtocol.readFrame(input)!!
        assertEquals(TransferProtocol.TYPE_FILE_END, endFrame.type)
        assertEquals(chunk.size.toLong(), endFrame.asLong())
    }

    @Test
    fun md5IsStable() {
        val bytes = "hello md5".toByteArray()
        val a = Checksum.md5Hex(bytes)
        val b = Checksum.md5Hex(ByteArrayInputStream(bytes))
        assertEquals(a, b)
        assertEquals(32, a.length)
    }
}
