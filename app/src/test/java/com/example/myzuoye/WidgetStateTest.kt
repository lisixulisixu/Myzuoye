package com.example.myzuoye

import com.example.myzuoye.widget.WidgetState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 敲一敲的「提示窗口」判定。
 *
 * 卡片上的「咚～ 已敲对方」只该存在两三秒，超过就得还原成连接状态。
 * 这段判断一旦写错（比如把窗口算成永久），卡片会一直停在「已敲对方」，
 * 用户下次看桌面会以为按钮卡住了，所以单独钉一遍边界。
 */
class WidgetStateTest {

    @Test
    fun knockIsActiveRightAfterTap() {
        val now = 1_000_000L
        assertTrue(WidgetState.knockActive(now, knockAt = now))
        assertTrue(WidgetState.knockActive(now + 1, knockAt = now))
        assertTrue(WidgetState.knockActive(now + 2_000, knockAt = now))
    }

    @Test
    fun knockExpiresAfterWindow() {
        val now = 1_000_000L
        assertFalse(
            "窗口结束后必须失效，否则卡片一直显示「已敲对方」",
            WidgetState.knockActive(now + WidgetState.KNOCK_HINT_MS, knockAt = now)
        )
        assertFalse(WidgetState.knockActive(now + 60_000, knockAt = now))
    }

    @Test
    fun neverKnockedIsNotActive() {
        assertFalse(WidgetState.knockActive(nowMs = 1_000_000L, knockAt = 0L))
    }

    /** 系统时间被往回调（用户改时间、时区跳变）时不能把提示一直挂住 */
    @Test
    fun clockGoingBackwardsDoesNotKeepHintAlive() {
        val knockAt = 2_000_000L
        assertFalse(WidgetState.knockActive(nowMs = knockAt - 5_000, knockAt = knockAt))
    }

    @Test
    fun customWindowIsRespected() {
        val now = 1_000L
        assertTrue(WidgetState.knockActive(now + 500, knockAt = now, windowMs = 1_000L))
        assertFalse(WidgetState.knockActive(now + 1_500, knockAt = now, windowMs = 1_000L))
    }
}
