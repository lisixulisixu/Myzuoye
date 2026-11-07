package com.example.myzuoye

import com.example.myzuoye.widget.TransferWidgetProvider
import com.example.myzuoye.widget.TransferWidgetProvider.Companion.CardSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 桌面小组件的尺寸分档。
 *
 * 这组用例是为了钉住一个真实事故：4×2 卡片在桌面上只显示了一个温度和「阴」，
 * 标题、城市、连接状态、进度条全都不见了。原因是用 `||` 判 1×1——
 * 而桌面给 4×2 的最小高度只有 100dp 左右，一条件命中就整张卡退化成极简形态。
 *
 * 尺寸取的是 Pixel 桌面（411dp 宽、4 列）的实际下发布局单位值。
 */
class WidgetCardSizeTest {

    @Test
    fun `4x2 是标准卡`() {
        // 桌面给 4×2 的典型上下限：宽 300 左右、高 100 上下
        assertEquals(CardSize.STANDARD, TransferWidgetProvider.cardSizeOf(300, 100))
        assertEquals(CardSize.STANDARD, TransferWidgetProvider.cardSizeOf(295, 96))
        assertEquals(CardSize.STANDARD, TransferWidgetProvider.cardSizeOf(320, 110))
    }

    @Test
    fun `1x1 才是极简卡`() {
        assertEquals(CardSize.TINY, TransferWidgetProvider.cardSizeOf(90, 90))
        assertEquals(CardSize.TINY, TransferWidgetProvider.cardSizeOf(70, 70))
        // 边界：刚好都小于 110
        assertEquals(CardSize.TINY, TransferWidgetProvider.cardSizeOf(109, 109))
    }

    @Test
    fun `只有一个方向小不能当极简卡`() {
        // 4×1：很宽但很矮
        assertEquals(CardSize.STANDARD, TransferWidgetProvider.cardSizeOf(300, 60))
        // 1×4：很窄但很高
        assertEquals(CardSize.STANDARD, TransferWidgetProvider.cardSizeOf(80, 300))
        // 2×2：正好两边都不算小
        assertEquals(CardSize.STANDARD, TransferWidgetProvider.cardSizeOf(180, 180))
    }

    @Test
    fun `4x4 是大卡`() {
        assertEquals(CardSize.LARGE, TransferWidgetProvider.cardSizeOf(300, 300))
        assertEquals(CardSize.LARGE, TransferWidgetProvider.cardSizeOf(250, 200))
        // 够宽但不够高：还是标准卡，别把曲线挤扁
        assertEquals(CardSize.STANDARD, TransferWidgetProvider.cardSizeOf(320, 180))
    }
}
