package com.example.myzuoye.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 连接二维码内容的编解码测试。
 *
 * 覆盖三块：
 * - round-trip：编出去再解回来必须还原（含配对码 / 不含配对码）；
 * - 容错：端口缺省用默认端口、scheme 大小写不敏感；
 * - 拒收：非本应用格式返回 null，配对码非 4 位数字直接丢弃。
 */
class QrPairTest {

    @Test
    fun `round trip with pair code`() {
        val p = QrPair.Payload("192.168.1.23", 8988, "1234")
        assertEquals(p, QrPair.decode(QrPair.encode(p)))
    }

    @Test
    fun `round trip without pair code`() {
        val p = QrPair.Payload("10.0.0.7", 9000, null)
        assertEquals(p, QrPair.decode(QrPair.encode(p)))
    }

    @Test
    fun `port omitted falls back to default`() {
        val payload = QrPair.decode("wfshare://192.168.49.1")
        assertEquals("192.168.49.1", payload?.host)
        assertEquals(TransferProtocol.PORT, payload?.port)
        assertNull(payload?.pairCode)
    }

    @Test
    fun `scheme is case insensitive`() {
        val payload = QrPair.decode("WFSHARE://192.168.1.1:8988?c=9999")
        assertEquals(QrPair.Payload("192.168.1.1", 8988, "9999"), payload)
    }

    @Test
    fun `foreign text is rejected`() {
        assertNull(QrPair.decode("https://example.com/download"))
        assertNull(QrPair.decode(""))
        assertNull(QrPair.decode("wfshare://"))
    }

    @Test
    fun `malformed pair code is dropped but host kept`() {
        // 配对码不是 4 位数字：丢弃码（让用户手输），但 IP/端口仍然可信
        val payload = QrPair.decode("wfshare://192.168.1.5:8988?c=abc")
        assertEquals("192.168.1.5", payload?.host)
        assertEquals(8988, payload?.port)
        assertNull(payload?.pairCode)
    }
}
