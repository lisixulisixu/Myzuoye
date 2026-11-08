package com.example.myzuoye

import com.example.myzuoye.util.ShakeDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摇一摇识别。
 *
 * 重点不是「摇得准」，而是「不该摇的时候别误触发」——
 * 误触发会莫名其妙开始广播配对，比没反应更让人困惑。
 */
class ShakeDetectorTest {

    private val g = 9.80665f

    /**
     * 时间基准。
     *
     * 真实环境里传的是 elapsedRealtime（开机以来毫秒），不会是 0；
     * 而「冷却」是按「距上次触发的间隔」算的，从 0 开始的话第一次就会误判成冷却中。
     */
    private val base = 10_000L

    /** 静止采样 */
    private fun rest(d: ShakeDetector, t: Long) = d.feed(0f, 0f, g, t + base)

    /** 一次摆动：加速度远超重力 */
    private fun swing(d: ShakeDetector, t: Long) = d.feed(0f, g + 5f, 0f, t + base)

    @Test
    fun threeQuickSwingsTrigger() {
        val d = ShakeDetector()
        assertFalse(rest(d, 0))
        assertFalse(swing(d, 40))
        assertFalse(rest(d, 60))
        assertFalse(swing(d, 100))
        assertFalse(rest(d, 120))
        assertTrue(swing(d, 160))
    }

    @Test
    fun singleBumpDoesNotTrigger() {
        val d = ShakeDetector()
        rest(d, 0)
        swing(d, 40)
        rest(d, 60)
        // 只晃了一下（比如从桌上拿起来），后面都是静止
        var t = 100L
        repeat(40) { rest(d, t); t += 20 }
        assertFalse("单次抖动不该触发", rest(d, t))
    }

    @Test
    fun peaksSpreadTooWideDoNotTrigger() {
        val d = ShakeDetector()
        // 每 900ms 晃一次：任意 800ms 窗口里最多两个峰
        val times = longArrayOf(0, 900, 1800, 2700)
        var triggered = false
        for (t in times) {
            rest(d, t)
            triggered = triggered or swing(d, t + 40)
            rest(d, t + 60)
        }
        assertFalse("间隔太开的摆动不该算作一次摇", triggered)
    }

    @Test
    fun sustainedAccelerationCountsAsSinglePeak() {
        val d = ShakeDetector()
        rest(d, 0)
        // 连续 10 个采样都高于阈值（比如坐车颠簸），没有回落到静止
        var triggered = false
        for (i in 1..10) {
            triggered = triggered or d.feed(0f, g + 6f, 0f, base + i * 20L)
        }
        assertFalse("持续高加速度不该被切成一串峰", triggered)
    }

    @Test
    fun cooldownBlocksImmediateRetrigger() {
        val d = ShakeDetector()
        var t = 0L
        fun shakeBurst(start: Long): Boolean {
            var hit = false
            var x = start
            repeat(3) {
                hit = rest(d, x) or hit
                x += 40
                hit = swing(d, x) or hit
                x += 20
            }
            return hit
        }

        assertTrue(shakeBurst(0))
        // 冷却期内（2.2 秒）立刻再摇，不该重复触发
        assertFalse(shakeBurst(600))
        // 过了冷却就不该再压制
        t = 3000
        assertTrue(shakeBurst(t))
    }

    @Test
    fun resetClearsPendingPeaks() {
        val d = ShakeDetector()
        rest(d, 0)
        swing(d, 40)
        rest(d, 60)
        swing(d, 100)
        d.reset()
        rest(d, 120)
        assertFalse("reset 之后应该重新计数", swing(d, 160))
    }
}
