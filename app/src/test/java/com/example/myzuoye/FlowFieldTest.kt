package com.example.myzuoye

import com.example.myzuoye.ui.FieldMotion
import com.example.myzuoye.ui.FlowBackgroundView.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * 状态场的方向规则。
 *
 * 这套效果最容易出的问题是「方向反了」——比如搜索时粒子往里收，
 * 看起来像在吞东西而不是在找。这类错误在截图里几乎看不出来，
 * 所以把方向单独抽出来锁住。
 */
class FlowFieldTest {

    private val cx = 500f
    private val cy = 800f

    @Test
    fun `待机时向上漂浮`() {
        val (dx, dy) = FieldMotion.direction(Field.IDLE, 100f, 200f, cx, cy)
        assertEquals(0f, dx, 0.0001f)
        assertEquals(-1f, dy, 0.0001f)
    }

    @Test
    fun `传输时水平向右`() {
        val (dx, dy) = FieldMotion.direction(Field.TRANSFERRING, 100f, 200f, cx, cy)
        assertEquals(1f, dx, 0.0001f)
        assertEquals(0f, dy, 0.0001f)
    }

    @Test
    fun `搜索时从中心向外`() {
        // 中心右侧的点应该继续向右
        val right = FieldMotion.direction(Field.SEARCHING, cx + 100f, cy, cx, cy)
        assertEquals(1f, right.first, 0.0001f)
        assertEquals(0f, right.second, 0.0001f)

        // 中心上方的点应该继续向上
        val up = FieldMotion.direction(Field.SEARCHING, cx, cy - 100f, cx, cy)
        assertEquals(0f, up.first, 0.0001f)
        assertEquals(-1f, up.second, 0.0001f)

        // 中心的左下方向
        val leftDown = FieldMotion.direction(Field.SEARCHING, cx - 100f, cy + 100f, cx, cy)
        assertTrue(leftDown.first < 0f)
        assertTrue(leftDown.second > 0f)
    }

    @Test
    fun `搜索方向始终是单位向量`() {
        val cases = listOf(
            0f to 0f,
            1200f to 300f,
            -80f to 2200f,
            cx to cy
        )
        for ((x, y) in cases) {
            val (dx, dy) = FieldMotion.direction(Field.SEARCHING, x, y, cx, cy)
            val len = hypot(dx, dy)
            assertEquals(1f, len, 0.001f)
        }
    }

    @Test
    fun `落在正中心时不会除零`() {
        val (dx, dy) = FieldMotion.direction(Field.SEARCHING, cx, cy, cx, cy)
        // 给的是兜底方向，关键是不能是 NaN
        assertTrue(!dx.isNaN() && !dy.isNaN())
        assertEquals(1f, hypot(dx, dy), 0.001f)
    }
}
