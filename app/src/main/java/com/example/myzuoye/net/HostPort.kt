package com.example.myzuoye.net

/**
 * 「地址:端口」文本解析，从 MainActivity 拆出来的纯函数。
 *
 * 支持 `1.2.3.4` 和 `1.2.3.4:8988` 两种写法；端口写错或没写就用 [defaultPort]。
 */
object HostPort {

    fun parse(text: String, defaultPort: Int): Pair<String, Int> {
        val parts = text.split(":")
        return if (parts.size >= 2) {
            parts[0] to (parts[1].toIntOrNull() ?: defaultPort)
        } else {
            text to defaultPort
        }
    }
}
