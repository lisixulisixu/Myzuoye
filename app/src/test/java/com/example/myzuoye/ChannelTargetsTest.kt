package com.example.myzuoye

import com.example.myzuoye.net.ChannelTargets
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 组主候选地址的推导测试。
 *
 * 这块以前的分支是「拿不到地址就放弃」，模拟器和部分 ROM 上会直接卡在
 * 「组主地址未知，无法建立文本通道」。现在要求无论系统报什么，都至少试一次固定地址。
 */
class ChannelTargetsTest {

    @Test
    fun reportedAddressComesFirstThenFixedAddress() {
        assertEquals(
            listOf("192.168.49.1" to 8988),
            ChannelTargets.forGroupOwner("192.168.49.1")
        )
        assertEquals(
            listOf("192.168.49.7" to 8988, "192.168.49.1" to 8988),
            ChannelTargets.forGroupOwner("192.168.49.7")
        )
    }

    @Test
    fun nullReportedFallsBackToFixedAddress() {
        assertEquals(
            listOf("192.168.49.1" to 8988),
            ChannelTargets.forGroupOwner(null)
        )
    }

    @Test
    fun blankAndZeroAddressAreTreatedAsMissing() {
        listOf("", "   ", "0.0.0.0").forEach { reported ->
            assertEquals(
                "「$reported」应当被当成没拿到地址",
                listOf("192.168.49.1" to 8988),
                ChannelTargets.forGroupOwner(reported)
            )
        }
    }

    @Test
    fun customPortIsPreserved() {
        assertEquals(
            listOf("192.168.49.1" to 9999),
            ChannelTargets.forGroupOwner(null, port = 9999)
        )
    }

    @Test
    fun noDuplicateTargets() {
        val targets = ChannelTargets.forGroupOwner("192.168.49.1")
        assertEquals(targets.size, targets.distinct().size)
    }
}
