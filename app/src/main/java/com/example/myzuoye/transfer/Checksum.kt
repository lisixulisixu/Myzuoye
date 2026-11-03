package com.example.myzuoye.transfer

import java.io.InputStream
import java.security.MessageDigest

/**
 * 文件校验工具：计算 MD5，用于传输完成后核对完整性。
 */
object Checksum {

    /** 一遍读完得到的摘要 + 实际字节数 */
    data class Digest(val md5Hex: String, val size: Long)

    fun md5Hex(stream: InputStream, bufferSize: Int = 8 * 1024): String =
        digestOf(stream, bufferSize).md5Hex

    /**
     * 一遍读取同时算出 MD5 和实际字节数。
     *
     * SAF 返回的 SIZE 列有时是 -1 或不准确，用实际读到的字节数做文件头更可靠。
     */
    fun digestOf(stream: InputStream, bufferSize: Int = 8 * 1024): Digest {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(bufferSize)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            total += read
        }
        return Digest(digest.digest().joinToString("") { "%02x".format(it) }, total)
    }

    fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
