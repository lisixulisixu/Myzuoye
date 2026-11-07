package com.example.myzuoye.transfer

/**
 * 连接二维码的内容编解码。
 *
 * 扫码配对的核心是把「连哪里 + 用什么码」塞进一张二维码里：
 * 对端扫一下就能直接建连，不用手输 IP，配对码也一起带过去免输入。
 *
 * 格式刻意用自定义 scheme，避免和网页二维码混淆：
 * ```
 * wfshare://192.168.1.23:8988?c=1234
 * ```
 * - 端口可省（省略时用默认端口 [TransferProtocol.PORT]）
 * - 配对码可省（对端没开校验时本来就不需要）
 *
 * 编解码只依赖字符串处理，JVM 单测可以直接跑；
 * 二维码图（[encodeBitmap]/[decodeBitmap]）依赖 Android 图形，放同一个文件的
 * 独立扩展区里，单测只测编解码部分。
 */
object QrPair {

    private const val SCHEME = "wfshare://"
    private const val KEY_CODE = "c"

    data class Payload(val host: String, val port: Int, val pairCode: String?)

    fun encode(payload: Payload): String = buildString {
        append(SCHEME)
        append(payload.host)
        append(':')
        append(payload.port)
        if (!payload.pairCode.isNullOrBlank()) {
            append('?')
            append(KEY_CODE)
            append('=')
            append(payload.pairCode)
        }
    }

    /**
     * 解析二维码文本；不是本应用格式返回 null。
     *
     * 容错考虑：
     * - 大小写：scheme 部分不区分大小写（有些二维码生成器会规范化 URL）；
     * - 端口缺省、非法：退回默认端口而不是整体失败——IP 对了端口几乎总是对的；
     * - 配对码非 4 位数字：直接丢弃（可能是别的应用的 wfshare，宁可让用户手输）。
     */
    fun decode(text: String): Payload? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(SCHEME, ignoreCase = true)) return null
        val body = trimmed.substring(SCHEME.length)
        val (hostPort, query) = body.split('?', limit = 2).let {
            if (it.size == 2) it[0] to it[1] else it[0] to ""
        }
        val host = hostPort.substringBefore(':').trim()
        if (host.isEmpty()) return null
        val port = hostPort.substringAfter(':', "").toIntOrNull()
            ?: TransferProtocol.PORT
        val code = query.split('&')
            .firstOrNull { it.substringBefore('=') == KEY_CODE }
            ?.substringAfter('=', "")
            ?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        return Payload(host, port, code)
    }
}
