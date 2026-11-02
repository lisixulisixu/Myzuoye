package com.example.ptp.transfer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets

/**
 * 自定义帧协议。M2 只有文本帧，M3 会在此基础上追加文件头 / 文件分片 / 结束帧。
 *
 * 帧结构（大端）：
 * ```
 * +--------+----------+----------------+
 * | type   | length   | payload        |
 * | 1 字节 | 4 字节   | length 字节    |
 * +--------+----------+----------------+
 * ```
 */
object TransferProtocol {

    /** 固定端口，组主监听、客户端连接 */
    const val PORT = 8988

    /** 文本帧：payload 为 UTF-8 字符串 */
    const val TYPE_TEXT: Byte = 0x01

    /** 单帧上限，防止对方发来异常长度把内存撑爆 */
    private const val MAX_FRAME_BYTES = 4 * 1024 * 1024

    fun writeText(out: DataOutputStream, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        out.writeByte(TYPE_TEXT.toInt())
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

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
    }
}
