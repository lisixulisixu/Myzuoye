package com.example.myzuoye.transfer

import java.io.InputStream
import java.security.MessageDigest

/**
 * 文件校验工具：计算 MD5，用于传输完成后核对完整性。
 */
object Checksum {

    fun md5Hex(stream: InputStream, bufferSize: Int = 8 * 1024): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(bufferSize)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
