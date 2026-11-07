package com.example.myzuoye

import com.example.myzuoye.transfer.RateLimiter
import com.example.myzuoye.transfer.linkStalled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知节流与断连判定的测试。
 *
 * 这两条规则都属于「上线后才发现」的类型：
 * 节流算错 → 通知栏在高吞吐时被打爆；断连判定写反 → 要么空转的连接被误杀，
 * 要么对端消失了界面永远卡在「传输中」。所以时间用一个假的时钟喂进去，
 * 不用等真实秒数，也不依赖 Robolectric。
 */
class TransferRateLimitTest {

    private var now = 0L
    private val limiter = RateLimiter(450L) { now }

    @Test
    fun `第一次一定放行`() {
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `间隔内拒绝，超过间隔放行`() {
        assertTrue(limiter.tryAcquire())     // t = 0
        now = 100L
        assertFalse(limiter.tryAcquire())    // 100ms 后太早
        now = 449L
        assertFalse(limiter.tryAcquire())    // 还差 1ms
        now = 450L
        assertTrue(limiter.tryAcquire())     // 正好到期
    }

    @Test
    fun `高频调用下每秒最多约两次`() {
        // 模拟 10MB/s：每 10ms 来一个分片，跑 10 秒 = 1000 次
        var allowed = 0
        while (now <= 10_000L) {
            if (limiter.tryAcquire()) allowed++
            now += 10L
        }
        // 10000 / 450 ≈ 22，允许 ±1 的取整误差
        assertTrue("放行次数过多: $allowed", allowed in 21..24)
    }

    @Test
    fun `重置后立刻放行`() {
        assertTrue(limiter.tryAcquire())
        now = 120L
        assertFalse(limiter.tryAcquire())
        limiter.reset()
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `空闲的连接不会被判定为断线`() {
        // 连着聊天但没在传文件：挂多久都不该被杀
        assertFalse(linkStalled(nowMs = 600_000L, lastIoAt = 0L, transferring = false, idleTimeoutMs = 30_000L))
    }

    @Test
    fun `传输中超过阈值判定为断线`() {
        assertFalse(linkStalled(nowMs = 29_999L, lastIoAt = 0L, transferring = true, idleTimeoutMs = 30_000L))
        assertTrue(linkStalled(nowMs = 30_000L, lastIoAt = 0L, transferring = true, idleTimeoutMs = 30_000L))
        assertTrue(linkStalled(nowMs = 120_000L, lastIoAt = 0L, transferring = true, idleTimeoutMs = 30_000L))
    }

    @Test
    fun `有字节流动就一直不判定断线`() {
        var lastIo = 0L
        var now = 0L
        val ticks = 100
        repeat(ticks) {
            now += 1_000L
            lastIo = now          // 每秒都有分片
            assertFalse(linkStalled(now, lastIo, transferring = true, idleTimeoutMs = 30_000L))
        }
        assertEquals(ticks * 1_000L, now)
    }
}
