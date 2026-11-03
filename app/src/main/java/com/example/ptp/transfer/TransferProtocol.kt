package com.example.ptp.transfer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets

/**
 * 自定义帧协议。
 *
 * 帧结构（大端）：
 * ```
 * +--------+----------+----------------+
 * | type   | length   | payload        |
 * | 1 字节 | 4 字节   | length 字节    |
 * +--------+----------+----------------+
 * ```
 *
 * M2 已有文本帧；M3 新增文件头 / 分片 / 结束帧，用于点对点文件传输。
 */
object TransferProtocol {

    /** 固定端口，组主监听、客户端连接 */
    const val PORT = 8988

    // ------------------------------------------------------------------ 帧类型

    /** 文本帧：payload 为 UTF-8 字符串 */
    const val TYPE_TEXT: Byte = 0x01

    /** 文件头：payload 结构见 [FileHeader] */
    const val TYPE_FILE_HEADER: Byte = 0x02

    /** 文件分片：payload 为原始文件字节 */
    const val TYPE_FILE_CHUNK: Byte = 0x03

    /** 文件结束：payload 为收到的字节总数（long，大端），用于完整性校验 */
    const val TYPE_FILE_END: Byte = 0x04

    /** 传输被对端取消 */
    const val TYPE_FILE_CANCEL: Byte = 0x05

    /** 分片固定大小 */
    const val CHUNK_SIZE = 8 * 1024

    /** 单帧上限。文件分片 8KB，头/文本都远小于此；上限只为防止异常帧把内存撑爆 */
    private const val MAX_FRAME_BYTES = 4 * 1024 * 1024

    // ------------------------------------------------------------------ 写入

    fun writeText(out: DataOutputStream, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        writeFrame(out, TYPE_TEXT, bytes)
    }

    fun writeFileHeader(out: DataOutputStream, header: FileHeader) {
        val nameBytes = header.fileName.toByteArray(StandardCharsets.UTF_8)
        val md5Bytes = header.md5Hex.uppercase().toByteArray(StandardCharsets.US_ASCII)
        check(md5Bytes.size == 32) { "MD5 必须为 32 个十六进制字符" }

        val payload = ByteArray(1 + 2 + nameBytes.size + 8 + 32)
        var offset = 0

        payload[offset++] = 0 // 预留：协议版本
        payload[offset++] = (nameBytes.size ushr 8).toByte()
        payload[offset++] = nameBytes.size.toByte()
        System.arraycopy(nameBytes, 0, payload, offset, nameBytes.size)
        offset += nameBytes.size
        writeLong(payload, offset, header.fileSize)
        offset += 8
        System.arraycopy(md5Bytes, 0, payload, offset, 32)

        writeFrame(out, TYPE_FILE_HEADER, payload)
    }

    fun writeFileChunk(out: DataOutputStream, chunk: ByteArray) {
        writeFrame(out, TYPE_FILE_CHUNK, chunk)
    }

    fun writeFileEnd(out: DataOutputStream, totalBytes: Long) {
        val payload = ByteArray(8)
        writeLong(payload, 0, totalBytes)
        writeFrame(out, TYPE_FILE_END, payload)
    }

    fun writeFileCancel(out: DataOutputStream) {
        writeFrame(out, TYPE_FILE_CANCEL, ByteArray(0))
    }

    private fun writeFrame(out: DataOutputStream, type: Byte, payload: ByteArray) {
        out.writeByte(type.toInt())
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    // ------------------------------------------------------------------ 读取

    /**
     * 读一帧。返回 null 表示对方正常关闭了连接（EOF）。
     *
     * @throws java.io.IOException 流异常
     * @throws IllegalStateException 帧长度超限
     */
    fun readFrame(input: DataInputStream): Frame? {
        val type = try {
            input.readByte()
        } catch (e: EOFException) {
            return null
        }
        val length = input.readInt()
        check(length in 0..MAX_FRAME_BYTES) { "帧长度异常：$length" }
        val payload = ByteArray(length)
        input.readFully(payload)
        return Frame(type, payload)
    }

    class Frame(val type: Byte, val payload: ByteArray) {
        fun asText(): String = String(payload, StandardCharsets.UTF_8)

        fun asFileHeader(): FileHeader {
            check(type == TYPE_FILE_HEADER) { "不是文件头帧" }
            check(payload.size >= 1 + 2 + 8 + 16) { "文件头长度异常：${payload.size}" }

            var offset = 0
            // payload[0] 是协议版本，当前忽略
            offset += 1

            val nameLen = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
            offset += 2
            check(nameLen in 1..1024) { "文件名长度异常：$nameLen" }
            check(offset + nameLen + 8 + 32 <= payload.size) { "文件头字段越界" }

            val name = String(payload, offset, nameLen, StandardCharsets.UTF_8)
            offset += nameLen

            val size = readLong(payload, offset)
            offset += 8

            val md5 = String(payload, offset, 32, StandardCharsets.US_ASCII)

            return FileHeader(name, size, md5)
        }

        fun asLong(): Long {
            check(type == TYPE_FILE_END) { "不是结束帧" }
            check(payload.size == 8) { "结束帧长度异常：${payload.size}" }
            return readLong(payload, 0)
        }
    }

    // ------------------------------------------------------------------ 文件头

    data class FileHeader(
        val fileName: String,
        val fileSize: Long,
        val md5Hex: String
    )

    // ------------------------------------------------------------------ 工具

    private fun writeLong(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            target[offset + i] = (value ushr (56 - i * 8)).toByte()
        }
    }

    private fun readLong(source: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (source[offset + i].toLong() and 0xFF)
        }
        return result
    }
}
