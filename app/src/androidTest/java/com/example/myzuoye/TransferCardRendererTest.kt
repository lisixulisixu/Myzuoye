package com.example.myzuoye

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myzuoye.ui.TransferCardRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 传输卡片渲染验收。
 *
 * 断言「画出来了、尺寸对、不是一张纯色空图」，并把两张样例导出成 PNG，
 * 供人工核对排版（中文字形、省略号、双人构图有没有挤在一起）。
 */
@RunWith(AndroidJUnit4::class)
class TransferCardRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun sentCard(): Bitmap = TransferCardRenderer.render(
        context,
        TransferCardRenderer.CardData(
            fileName = "毕业设计的最终版真的不改了.docx",
            size = 3_400_000L,
            fromMe = true,
            at = 1_762_700_000_000L,
            durationMs = 2_400L,
            peer = "P60 Pro",
            deviceName = "Xiaomi 14"
        )
    )

    private fun receivedCard(): Bitmap = TransferCardRenderer.render(
        context,
        TransferCardRenderer.CardData(
            fileName = "VID_20261109_1930.mp4",
            size = 128L * 1024 * 1024,
            fromMe = false,
            at = 1_762_700_600_000L,
            durationMs = 14_600L,
            peer = "",
            deviceName = "Pixel 9"
        )
    )

    @Test
    fun rendersAtExpectedSize() {
        val bmp = sentCard()
        assertEquals(TransferCardRenderer.WIDTH, bmp.width)
        assertEquals(TransferCardRenderer.HEIGHT, bmp.height)
        // 3:4
        assertEquals(4, bmp.height * 3 / bmp.width)
    }

    @Test
    fun cardIsNotBlank() {
        val bmp = sentCard()
        // 取中心区域若干点，至少要出现不止一种颜色（背景 + 文字/贴纸）
        val colors = buildSet {
            for (x in 60..bmp.width - 60 step 120) {
                for (y in 60..bmp.height - 60 step 120) {
                    add(bmp.getPixel(x, y))
                }
            }
        }
        assertTrue("卡片看起来是纯色，渲染可能失败：$colors", colors.size > 3)
    }

    @Test
    fun exportsPreviewForManualReview() {
        val dir = File(context.filesDir, "card_preview").apply { mkdirs() }
        listOf("sent" to sentCard(), "received" to receivedCard()).forEach { (name, bmp) ->
            File(dir, "card_$name.png").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrue(File(dir, "card_$name.png").length() > 10_000)
        }
    }

    @Test
    fun fileNameCarriesTimestamp() {
        val name = TransferCardRenderer.fileNameFor(
            TransferCardRenderer.CardData(
                fileName = "a.txt", size = 1L, fromMe = true, at = 1_762_700_000_000L,
                durationMs = 0L, peer = "", deviceName = ""
            )
        )
        assertTrue(name.startsWith("WiFi快传-"))
        assertTrue(name.endsWith(".png"))
    }
}
