package com.example.myzuoye.transfer

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

    /**
     * 续传应答：接收端告诉发送端「我已经有这么多字节了」。
     *
     * payload 为 long（大端）。只有协议版本 ≥ 2 的发送端才会等这个帧；
     * 老版本发送端发来的文件头版本是 0，接收端不会回它，双方各走各的路径，
     * 不会出现「老发送端发全量、新接收端从偏移追加」这种把文件写坏的情况。
     */
    const val TYPE_FILE_RESUME: Byte = 0x06

    /**
     * 报到帧：连上之后、传任何东西之前，客户端先自报家门。
     *
     * payload：`版本号(1) + 配对码长度(2) + 配对码(UTF-8)`
     *
     * 配对码为空表示「本机没拿到码」——组主没开校验时随便传什么都能过，
     * 开了校验就会被拒。这样新旧客户端都能连：不带报到帧的老客户端
     * 在没开校验时照旧能用，开了校验才会被拦下（这是有意的，安全优先）。
     */
    const val TYPE_HELLO: Byte = 0x07

    /** 拒绝接入：payload 为 UTF-8 的原因，供界面显示 */
    const val TYPE_PAIR_REJECT: Byte = 0x08

    /**
     * 「敲一敲」：payload 为空，只是打个招呼。
     *
     * 桌面小组件上点一下就会发这一个帧，对端的 Q 版人物会弹一下。
     * 老版本收到未知帧只会在日志里记一行「收到未知帧类型」，不影响通道，
     * 所以这个功能不需要升协议版本，也不需要能力协商。
     */
    const val TYPE_KNOCK: Byte = 0x09

    /**
     * 当前协议版本，写在文件头的第 1 个字节。
     *
     * - 0：早期版本，文件头带 MD5、结束帧只有字节数
     * - 2：文件头 MD5 可为占位符、结束帧带 MD5、支持续传握手
     * - 3：新增报到帧与拒绝帧（配对码校验）
     */
    const val VERSION: Byte = 3

    /**
     * 分片大小。
     *
     * 从 8KB 提到 64KB：每帧都要 flush 一次，帧头只有 5 字节，
     * 分片太小的话系统调用与帧头开销占比很高。实测 64KB 在本机链路上吞吐最好，
     * 再大意义不大（单帧内存占用会上去）。
     */
    const val CHUNK_SIZE = 64 * 1024

    /** MD5 占位值：发送端边发边算，发文件头时还不知道最终 MD5，先填这个 */
    const val MD5_PLACEHOLDER = "00000000000000000000000000000000"

    /** MD5 十六进制长度（大写） */
    const val MD5_HEX_LEN = 32

    /** 单帧上限。文件分片 8KB，头/文本都远小于此；上限只为防止异常帧把内存撑爆 */
    private const val MAX_FRAME_BYTES = 4 * 1024 * 1024

    // ------------------------------------------------------------------ 写入

    fun writeText(out: DataOutputStream, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        writeFrame(out, TYPE_TEXT, bytes)
    }

    /**
     * 写文件头。
     *
     * [FileHeader.md5Hex] 允许为空——发送端改成「边发边算」后，发文件头时 MD5 还没算出来，
     * 先写 [MD5_PLACEHOLDER] 占位，真正的 MD5 放在结束帧里回传。
     */
    fun writeFileHeader(out: DataOutputStream, header: FileHeader) {
        val nameBytes = header.fileName.toByteArray(StandardCharsets.UTF_8)
        val md5Text = if (header.md5Hex.isBlank()) MD5_PLACEHOLDER else header.md5Hex.uppercase()
        val md5Bytes = md5Text.toByteArray(StandardCharsets.US_ASCII)
        check(md5Bytes.size == MD5_HEX_LEN) { "MD5 必须为 $MD5_HEX_LEN 个十六进制字符" }

        val payload = ByteArray(1 + 2 + nameBytes.size + 8 + 32)
        var offset = 0

        payload[offset++] = VERSION // 协议版本：接收端据此决定要不要发续传应答
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

    /** 旧格式结束帧：只有总字节数 */
    fun writeFileEnd(out: DataOutputStream, totalBytes: Long) {
        val payload = ByteArray(8)
        writeLong(payload, 0, totalBytes)
        writeFrame(out, TYPE_FILE_END, payload)
    }

    /**
     * 新格式结束帧：总字节数 + 发送端最终算出的 MD5。
     *
     * 接收端两种长度都要能读：8 字节（旧客户端）与 8+32 字节（新客户端）。
     */
    fun writeFileEnd(out: DataOutputStream, totalBytes: Long, md5Hex: String) {
        val md5Bytes = md5Hex.uppercase().toByteArray(StandardCharsets.US_ASCII)
        check(md5Bytes.size == MD5_HEX_LEN) { "MD5 必须为 $MD5_HEX_LEN 个十六进制字符" }
        val payload = ByteArray(8 + MD5_HEX_LEN)
        writeLong(payload, 0, totalBytes)
        System.arraycopy(md5Bytes, 0, payload, 8, MD5_HEX_LEN)
        writeFrame(out, TYPE_FILE_END, payload)
    }

    fun writeFileCancel(out: DataOutputStream) {
        writeFrame(out, TYPE_FILE_CANCEL, ByteArray(0))
    }

    /** 回一个续传应答：接收端已有的字节数 */
    fun writeFileResume(out: DataOutputStream, offset: Long) {
        val payload = ByteArray(8)
        writeLong(payload, 0, offset)
        writeFrame(out, TYPE_FILE_RESUME, payload)
    }

    /** 报到：把本机知道的配对码带上（没有就传空串） */
    fun writeHello(out: DataOutputStream, pairCode: String) {
        val codeBytes = pairCode.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(1 + 2 + codeBytes.size)
        payload[0] = VERSION
        payload[1] = (codeBytes.size ushr 8).toByte()
        payload[2] = codeBytes.size.toByte()
        System.arraycopy(codeBytes, 0, payload, 3, codeBytes.size)
        writeFrame(out, TYPE_HELLO, payload)
    }

    /** 敲一敲：空载荷，成本极低，走一次 flush 就到对面 */
    fun writeKnock(out: DataOutputStream) {
        writeFrame(out, TYPE_KNOCK, ByteArray(0))
    }

    /** 拒绝接入，[reason] 会直接显示在对端界面上 */
    fun writePairReject(out: DataOutputStream, reason: String) {
        val payload = reason.toByteArray(StandardCharsets.UTF_8)
        // 原因文案很短，截一下防止异常字符串把帧撑大
        val safe = if (payload.size > 512) payload.copyOf(512) else payload
        writeFrame(out, TYPE_PAIR_REJECT, safe)
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
            // payload[0] 是协议版本
            val version = payload[offset].toInt() and 0xFF
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

            return FileHeader(name, size, md5, version)
        }

        /** 解析报到帧里的配对码；不是报到帧或格式不对时返回 null */
        fun asPairCode(): String? {
            if (type != TYPE_HELLO) return null
            if (payload.size < 3) return null
            val len = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
            if (len < 0 || 3 + len > payload.size) return null
            return String(payload, 3, len, StandardCharsets.UTF_8)
        }

        /** 解析续传应答里的偏移量 */        fun asResumeOffset(): Long {
            check(type == TYPE_FILE_RESUME) { "不是续传应答帧" }
            check(payload.size >= 8) { "续传应答长度异常：${payload.size}" }
            return readLong(payload, 0)
        }

        /** 旧接口：只取总字节数。新格式（带 MD5）也能用，忽略尾部 MD5 */
        fun asLong(): Long {
            check(type == TYPE_FILE_END) { "不是结束帧" }
            check(payload.size >= 8) { "结束帧长度异常：${payload.size}" }
            return readLong(payload, 0)
        }

        /** 解析结束帧：总字节数 + 可选的 MD5（旧客户端发来的没有 MD5） */
        fun asFileEnd(): FileEnd {
            check(type == TYPE_FILE_END) { "不是结束帧" }
            check(payload.size >= 8) { "结束帧长度异常：${payload.size}" }
            val md5 = if (payload.size >= 8 + MD5_HEX_LEN) {
                String(payload, 8, MD5_HEX_LEN, StandardCharsets.US_ASCII)
            } else {
                null
            }
            return FileEnd(readLong(payload, 0), md5)
        }
    }

    // ------------------------------------------------------------------ 文件头

    data class FileHeader(
        val fileName: String,
        val fileSize: Long,
        /** 为空或等于 [MD5_PLACEHOLDER] 时表示「发送端还没算出来，见结束帧」 */
        val md5Hex: String,
        /** 对端协议版本；0 表示老版本客户端 */
        val version: Int = 0
    ) {
        /** 文件头里是否带了真实 MD5 */
        val md5Known: Boolean
            get() = md5Hex.length == MD5_HEX_LEN &&
                !md5Hex.equals(MD5_PLACEHOLDER, ignoreCase = true)

        /** 对端是否支持续传握手（版本 2 起） */
        val supportsResume: Boolean
            get() = version >= 2
    }

    /** 结束帧内容 */
    data class FileEnd(
        val totalBytes: Long,
        /** 发送端最终 MD5；旧客户端为 null */
        val md5Hex: String?
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
