package com.example.myzuoye

import com.example.myzuoye.transfer.Checksum
import com.example.myzuoye.transfer.TransferProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    /**
     * 端到端：文件头 + 多分片 + 结束帧，接收侧拼回内容并核对 MD5。
     *
     * 重点覆盖“协议里 MD5 是大写、Checksum 返回小写”这个坑——
     * 之前的实现直接用 == 比较，导致真机上永远校验失败。
     */
    @Test
    fun fileTransferEndToEndKeepsContentAndMd5() {
        val content = ByteArray(20_000) { (it % 256).toByte() }
        val md5 = Checksum.md5Hex(content)

        // 发送侧
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        TransferProtocol.writeFileHeader(
            out,
            TransferProtocol.FileHeader("demo.bin", content.size.toLong(), md5)
        )
        var offset = 0
        while (offset < content.size) {
            val len = minOf(TransferProtocol.CHUNK_SIZE, content.size - offset)
            TransferProtocol.writeFileChunk(out, content.copyOfRange(offset, offset + len))
            offset += len
        }
        TransferProtocol.writeFileEnd(out, content.size.toLong())

        // 接收侧
        val input = DataInputStream(ByteArrayInputStream(buffer.toByteArray()))
        val header = TransferProtocol.readFrame(input)!!.asFileHeader()
        assertEquals("demo.bin", header.fileName)
        assertEquals(content.size.toLong(), header.fileSize)

        val received = ByteArrayOutputStream()
        var total = 0L
        var endValue = -1L
        while (true) {
            val frame = TransferProtocol.readFrame(input) ?: break
            when (frame.type) {
                TransferProtocol.TYPE_FILE_CHUNK -> {
                    received.write(frame.payload)
                    total += frame.payload.size
                }

                TransferProtocol.TYPE_FILE_END -> {
                    endValue = frame.asLong()
                    break
                }
            }
        }

        val receivedBytes = received.toByteArray()
        assertEquals(content.size.toLong(), total)
        assertEquals(content.size.toLong(), endValue)
        assertArrayEquals(content, receivedBytes)

        // 关键断言：协议里存大写，本地算小写，必须忽略大小写才相等
        assertTrue(header.md5Hex.equals(Checksum.md5Hex(receivedBytes), ignoreCase = true))
    }
}
