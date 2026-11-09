package com.example.myzuoye

import com.example.myzuoye.net.HostPort
import org.junit.Assert.assertEquals
import org.junit.Test

/** 「地址:端口」解析的纯函数测试 */
class HostPortTest {

    @Test
    fun bareHostUsesDefaultPort() {
        assertEquals("1.2.3.4" to 8988, HostPort.parse("1.2.3.4", 8988))
    }

    @Test
    fun hostWithPort() {
        assertEquals("192.168.1.5" to 9000, HostPort.parse("192.168.1.5:9000", 8988))
    }

    @Test
    fun invalidPortFallsBackToDefault() {
        assertEquals("10.0.2.2" to 8988, HostPort.parse("10.0.2.2:abc", 8988))
    }

    @Test
    fun defaultPortCanBeAnything() {
        assertEquals("a" to 1234, HostPort.parse("a", 1234))
        assertEquals("a" to 9999, HostPort.parse("a:9999", 1234))
    }

    @Test
    fun extraColonsKeepFirstSegmentAsHost() {
        // 与原实现一致：按第一个冒号切段，多余部分不参与解析
        assertEquals("10.0.2.2" to 8988, HostPort.parse("10.0.2.2:x:y", 8988))
    }
}
