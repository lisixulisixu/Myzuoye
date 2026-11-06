package com.example.myzuoye

import com.example.myzuoye.ui.HairField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 封面「头发摆动」形变场的测试。
 *
 * 这块是整个封面里最容易「看着不对劲但说不出哪不对」的部分：
 * 权重漏到衬衫上，男生衣服会跟着抖；权重在边界上跳变，头发轮廓会被拉出一条缝。
 * 所以用一个位图坐标系（0..1）把关键位置钉死，改参数时先跑这里再上机看。
 */
class HairFieldTest {

    /** 采样一个矩形区域里的最大权重，用来判断「这一块到底动不动」 */
    private fun maxWeight(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        var max = 0f
        for (i in 0..20) {
            for (j in 0..20) {
                val u = x0 + (x1 - x0) * i / 20f
                val v = y0 + (y1 - y0) * j / 20f
                max = maxOf(max, HairField.weight(u, v))
            }
        }
        return max
    }

    @Test
    fun `左侧男生衬衫与门框完全不参与形变`() {
        // 男生的白衬衫 / 深色头发都在 x < 0.44 一带，动了会非常奇怪
        assertEquals(0f, maxWeight(0.00f, 0.00f, 0.44f, 1.00f), 0.0001f)
        // 右侧门框（0.78 之后）也不该被带着走
        assertEquals(0f, maxWeight(0.78f, 0.00f, 1.00f, 1.00f), 0.0001f)
    }

    @Test
    fun `长发主体权重接近满值`() {
        assertTrue(HairField.weight(0.580f, 0.465f) > 0.9f)
        assertTrue(HairField.weight(0.560f, 0.650f) > 0.8f)
        // 最外侧翘起的发梢
        assertTrue(HairField.weight(0.665f, 0.470f) > 0.6f)
    }

    @Test
    fun `头顶与裙摆之外权重收敛到零`() {
        assertEquals(0f, HairField.weight(0.55f, 0.20f), 0.0001f)  // 头顶上方
        assertEquals(0f, HairField.weight(0.55f, 0.85f), 0.0001f)  // 裙子以下
    }

    @Test
    fun `权重沿边界连续下降不跳变`() {
        // 从画面中心往左走到衬衫，每 0.01 采一次，相邻两点差值不能太大，
        // 否则网格形变会把像素拉出一条可见的折痕
        var prev = HairField.weight(0.58f, 0.47f)
        var u = 0.58f
        while (u > 0.40f) {
            u -= 0.01f
            val cur = HairField.weight(u, 0.47f)
            assertTrue("u=$u 处权重跳变过大", abs(cur - prev) < 0.2f)
            prev = cur
        }
        assertEquals(0f, prev, 0.0001f)
    }

    @Test
    fun `摆动是循环的且幅度有限`() {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        var t = 0f
        while (t < 40f) {
            val s = HairField.sway(0.58f, 0.47f, t)
            min = minOf(min, s)
            max = maxOf(max, s)
            t += 0.05f
        }
        // 幅度控制在 ±1.2 以内，换算到屏幕上就是 3~4dp 的摆幅
        assertTrue("摆幅过大: $min..$max", min > -1.25f && max < 1.25f)
        assertTrue("摆幅过小，几乎看不出来: $min..$max", max - min > 0.8f)

        // 同一个点在不同时刻的值要不一样，否则就是一条直线（不动）
        assertTrue(abs(HairField.sway(0.58f, 0.47f, 0.3f) - HairField.sway(0.58f, 0.47f, 1.6f)) > 0.1f)
    }

    @Test
    fun `发根与发梢的相位不同`() {
        // 主波带 -u*6.2 的相位延迟，靠近头部（u 小）与发梢（u 大）不该同相
        val head = HairField.sway(0.50f, 0.45f, 1.0f)
        val tip = HairField.sway(0.70f, 0.45f, 1.0f)
        assertTrue(abs(head - tip) > 0.05f)
    }
}
