package com.example.myzuoye

import com.example.myzuoye.transfer.ReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断线自动重连策略的纯函数测试。
 *
 * 规则回顾：连续快速断线最多 3 轮；连接稳定超过一分钟才断的算偶发，额度清零重计。
 */
class ReconnectPolicyTest {

    @Test
    fun maxRoundsIsThree() {
        assertEquals(3, ReconnectPolicy.MAX_ROUNDS)
    }

    @Test
    fun canAttemptWithinLimit() {
        assertTrue(ReconnectPolicy.canAttempt(0))
        assertTrue(ReconnectPolicy.canAttempt(1))
        assertTrue(ReconnectPolicy.canAttempt(2))
        assertFalse(ReconnectPolicy.canAttempt(3))
        assertFalse(ReconnectPolicy.canAttempt(10))
    }

    @Test
    fun streakResetsOnlyAfterStableConnection() {
        // 1 分钟内断的不算偶发，不重置
        assertFalse(ReconnectPolicy.shouldResetStreak(0L))
        assertFalse(ReconnectPolicy.shouldResetStreak(59_999L))
        // 活满一分钟才断：偶发，重置额度
        assertTrue(ReconnectPolicy.shouldResetStreak(60_000L))
        assertTrue(ReconnectPolicy.shouldResetStreak(10L * 60_000L))
    }

    @Test
    fun attemptsShrinkWithRounds() {
        // 首轮最多（组网重建可能要好几秒），后面逐轮收敛
        assertTrue(ReconnectPolicy.attemptsForRound(1) > ReconnectPolicy.attemptsForRound(2))
        assertTrue(ReconnectPolicy.attemptsForRound(2) >= ReconnectPolicy.attemptsForRound(3))
        // 超界轮次给最小值，不抛异常
        assertEquals(ReconnectPolicy.attemptsForRound(3), ReconnectPolicy.attemptsForRound(9))
    }
}
