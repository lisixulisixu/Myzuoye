package com.example.myzuoye

import com.example.myzuoye.util.TransferEta
import org.junit.Assert.assertEquals
import org.junit.Test

/** 剩余时间估算（桌面小组件用）的纯函数测试 */
class TransferEtaTest {

    @Test
    fun zeroWhenNoSpeedSampleYet() {
        assertEquals(0L, TransferEta.etaSeconds(0L, 1000L, 0.5f))
    }

    @Test
    fun zeroWhenTotalUnknown() {
        // SAF 拿不到 SIZE 时 totalBytes 为 0，不能硬凑「剩 -- 秒」
        assertEquals(0L, TransferEta.etaSeconds(100L, 0L, 0f))
    }

    @Test
    fun remainingBytesDividedBySpeed() {
        // 还剩 5000 字节，速度 100 B/s → 50 秒
        assertEquals(50L, TransferEta.etaSeconds(100L, 10_000L, 0.5f))
    }

    @Test
    fun zeroWhenAlreadyComplete() {
        assertEquals(0L, TransferEta.etaSeconds(100L, 10_000L, 1f))
        assertEquals(0L, TransferEta.etaSeconds(100L, 10_000L, 1.5f))
    }

    @Test
    fun progressIsClampedIntoRange() {
        // progress 越界（-0.5 / 2.0）按 0 / 1 处理，不会算出负数或爆炸值
        assertEquals(100L, TransferEta.etaSeconds(100L, 10_000L, -0.5f))
        assertEquals(0L, TransferEta.etaSeconds(100L, 10_000L, 2f))
    }
}
