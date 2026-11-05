package com.example.myzuoye

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myzuoye.weather.WeatherCondition
import com.example.myzuoye.widget.WeatherSceneRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 天气场景渲染的冒烟测试。
 *
 * 这段代码全是 Canvas + Shader + BlurMaskFilter，写错参数（比如渐变的两个坐标点重合）
 * 会在真机上抛 IllegalArgumentException 让桌面小组件更新失败、卡片空白。
 * 这里把每种天气的昼/夜、几种典型卡片尺寸都画一遍，确保不崩、尺寸正确。
 */
@RunWith(AndroidJUnit4::class)
class WeatherSceneRendererTest {

    private val density = InstrumentationRegistry.getInstrumentation()
        .targetContext.resources.displayMetrics.density

    @Test
    fun 所有天气昼夜组合都能渲染() {
        val sizes = listOf(
            240 to 110,   // 4x2 小卡片
            480 to 220,   // 4x2 高分辨率
            360 to 320    // 4x3 近方形
        )
        for ((wDp, hDp) in sizes) {
            for (condition in WeatherCondition.values()) {
                for (isDay in listOf(true, false)) {
                    val w = (wDp * density).toInt()
                    val h = (hDp * density).toInt()
                    val bitmap = WeatherSceneRenderer.renderBackground(w, h, condition, isDay)
                    val tag = "$condition/$isDay/${wDp}x$hDp"
                    assertTrue("$tag 位图为空", bitmap.width > 0 && bitmap.height > 0)
                    // 渲染尺寸会被安全阈值压缩，但长宽比必须保持
                    val expected = w.toFloat() / h
                    val actual = bitmap.width.toFloat() / bitmap.height
                    assertEquals("$tag 长宽比被改变", expected, actual, 0.05f)
                }
            }
        }
    }

    @Test
    fun 极小与极大尺寸不会崩() {
        val tiny = WeatherSceneRenderer.renderBackground(1, 1, WeatherCondition.SNOW, true)
        assertTrue(tiny.width >= 1 && tiny.height >= 1)
        val huge = WeatherSceneRenderer.renderBackground(4000, 3000, WeatherCondition.THUNDER, false)
        // 超出上限时会被压到安全范围，避免 RemoteViews 事务超限
        assertTrue(huge.width <= 480)
    }

    @Test
    fun 相同参数命中缓存返回同一张图() {
        val a = WeatherSceneRenderer.renderBackground(600, 280, WeatherCondition.RAIN, true)
        val b = WeatherSceneRenderer.renderBackground(600, 280, WeatherCondition.RAIN, true)
        assertTrue("应命中缓存复用位图", a === b)
    }
}
