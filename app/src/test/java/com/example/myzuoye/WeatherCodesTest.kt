package com.example.myzuoye

import com.example.myzuoye.weather.WeatherCodes
import com.example.myzuoye.weather.WeatherCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WMO 天气代码 → 天气分类的映射测试。
 *
 * 这段映射直接决定小组件画哪种天气特效，映射错了用户会看到「下雪天出大太阳」，
 * 所以逐个代码段都钉死。
 */
class WeatherCodesTest {

    @Test
    fun `晴与云量`() {
        assertEquals(WeatherCondition.SUNNY, WeatherCodes.of(0))
        assertEquals(WeatherCondition.PARTLY, WeatherCodes.of(1))
        assertEquals(WeatherCondition.CLOUDY, WeatherCodes.of(2))
        assertEquals(WeatherCondition.OVERCAST, WeatherCodes.of(3))
    }

    @Test
    fun `雾`() {
        assertEquals(WeatherCondition.FOG, WeatherCodes.of(45))
        assertEquals(WeatherCondition.FOG, WeatherCodes.of(48))
    }

    @Test
    fun `毛毛雨`() {
        for (code in intArrayOf(51, 53, 55, 56, 57)) {
            assertEquals(WeatherCondition.DRIZZLE, WeatherCodes.of(code))
        }
    }

    @Test
    fun `雨`() {
        for (code in intArrayOf(61, 63, 65, 66, 67)) {
            assertEquals(WeatherCondition.RAIN, WeatherCodes.of(code))
        }
    }

    @Test
    fun `雪`() {
        for (code in intArrayOf(71, 73, 75, 77, 85, 86)) {
            assertEquals(WeatherCondition.SNOW, WeatherCodes.of(code))
        }
    }

    @Test
    fun `阵雨`() {
        for (code in intArrayOf(80, 81, 82)) {
            assertEquals(WeatherCondition.SHOWER, WeatherCodes.of(code))
        }
    }

    @Test
    fun `雷暴`() {
        for (code in intArrayOf(95, 96, 99)) {
            assertEquals(WeatherCondition.THUNDER, WeatherCodes.of(code))
        }
    }

    @Test
    fun `未知代码不会崩也不会误判成晴天`() {
        assertEquals(WeatherCondition.CLOUDY, WeatherCodes.of(-1))
        assertEquals(WeatherCondition.CLOUDY, WeatherCodes.of(1234))
    }

    @Test
    fun `降水判定`() {
        assertTrue(WeatherCodes.isWet(WeatherCondition.RAIN))
        assertTrue(WeatherCodes.isWet(WeatherCondition.SNOW))
        assertTrue(WeatherCodes.isWet(WeatherCondition.THUNDER))
        assertFalse(WeatherCodes.isWet(WeatherCondition.SUNNY))
        assertFalse(WeatherCodes.isWet(WeatherCondition.OVERCAST))
    }

    @Test
    fun `每个分类都有中文短名`() {
        WeatherCondition.entries.forEach { condition ->
            assertTrue("${condition.name} 缺少显示名", condition.label.isNotBlank())
        }
    }
}
