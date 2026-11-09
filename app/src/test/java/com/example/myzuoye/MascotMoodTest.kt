package com.example.myzuoye

import com.example.myzuoye.ui.MascotView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表情库映射的纯逻辑测试：每个表情都得有图，且不能两张表情指向同一张图。
 *
 * 这里不加载图片资源（JVM 单测没有真实资源），只校验资源 id 的映射关系——
 * 「加了新表情却忘了配图」这种错，就是靠这里兜住的。
 */
class MascotMoodTest {

    private val allMoods = listOf(
        MascotView.MOOD_IDLE, MascotView.MOOD_HAPPY, MascotView.MOOD_ANGRY,
        MascotView.MOOD_SURPRISED, MascotView.MOOD_SHY, MascotView.MOOD_SLEEPY,
        MascotView.MOOD_SMUG, MascotView.MOOD_SAD, MascotView.MOOD_CHEER,
        MascotView.MOOD_SHUSH
    )

    @Test
    fun everyMoodHasItsOwnDrawable() {
        val ids = allMoods.map { MascotView.drawableForMood(it) }
        assertTrue("有表情没配图（资源 id 为 0）", ids.none { it == 0 })
        assertEquals("有表情共用同一张图，用户看不出区别", ids.size, ids.distinct().size)
    }

    @Test
    fun unknownMoodFallsBackToIdle() {
        assertEquals(MascotView.drawableForMood(MascotView.MOOD_IDLE), MascotView.drawableForMood(99))
    }

    @Test
    fun galleryCoversEveryMood() {
        assertEquals(allMoods.toSet(), MascotView.ALL_MOODS.toSet())
        assertEquals("预览顺序里不该有重复", MascotView.ALL_MOODS.size, MascotView.ALL_MOODS.distinct().size)
    }

    @Test
    fun moodConstantsAreDistinct() {
        assertEquals(allMoods.size, allMoods.distinct().size)
        assertNotEquals(MascotView.MOOD_IDLE, MascotView.MOOD_CHEER)
    }
}
