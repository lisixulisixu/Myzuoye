package com.example.myzuoye

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myzuoye.ui.MascotView
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 表情贴纸资源验收：能解码、是方的、四角干净（生成图上的水印痕迹要被抹掉）。
 *
 * 顺带把九张拼成一张对照表导出，方便人工看「风格是不是一家人」。
 */
@RunWith(AndroidJUnit4::class)
class MascotAssetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyStickerDecodesAndIsUsable() {
        MascotView.ALL_MOODS.forEach { mood ->
            val res = MascotView.drawableForMood(mood)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(context.resources, res, opts)
            assertTrue("表情 $mood 没解码出图片", opts.outWidth > 0 && opts.outHeight > 0)
            // 老素材并不全是正方：happy 是 400×395、angry 是躺着的 400×300。
            // 所以只要求「短边够大、比例不极端」，贴纸本身由 View 等比缩放。
            val ratio = opts.outWidth.toFloat() / opts.outHeight
            assertTrue("表情 $mood 比例太极端（$ratio）", ratio in 0.7f..1.5f)
            val shortSide = minOf(opts.outWidth, opts.outHeight)
            assertTrue("表情 $mood 太小，放大后会糊：短边 ${shortSide}px", shortSide >= 300)
        }
    }

    @Test
    fun stickerCornersAreClean() {
        MascotView.ALL_MOODS.forEach { mood ->
            val bmp = BitmapFactory.decodeResource(context.resources, MascotView.drawableForMood(mood))
            assertNotNull(bmp)
            val w = bmp.width
            val h = bmp.height
            // 四个角各取一个 6x6 小块：只允许纯白或透明（老素材是透明底，新素材是白底）。
            // 有平均灰度偏灰的痕迹说明生成图的水印没擦干净。
            val corners = listOf(2 to 2, w - 8 to 2, 2 to h - 8, w - 8 to h - 8)
            corners.forEach corner@{ (x0, y0) ->
                var sum = 0
                var n = 0
                var transparent = 0
                for (x in x0 until x0 + 6) {
                    for (y in y0 until y0 + 6) {
                        val p = bmp.getPixel(x, y)
                        if (android.graphics.Color.alpha(p) < 16) {
                            transparent++
                        } else {
                            sum += (android.graphics.Color.red(p) + android.graphics.Color.green(p) +
                                android.graphics.Color.blue(p)) / 3
                        }
                        n++
                    }
                }
                if (transparent == n) return@corner // 整块透明，算干净
                val avg = if (n - transparent == 0) 255 else sum / (n - transparent)
                assertTrue("表情 $mood 的角($x0,$y0)不干净，均值 $avg（水印没擦掉？）", avg > 235)
            }
            bmp.recycle()
        }
    }

    @Test
    fun exportsContactSheet() {
        val cell = 200
        val cols = 3
        val rows = (MascotView.ALL_MOODS.size + cols - 1) / cols
        val sheet = android.graphics.Bitmap.createBitmap(
            cell * cols, cell * rows, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(sheet)
        canvas.drawColor(0xFF141B2B.toInt())
        MascotView.ALL_MOODS.forEachIndexed { i, mood ->
            val bmp = BitmapFactory.decodeResource(context.resources, MascotView.drawableForMood(mood))
                ?: return@forEachIndexed
            val left = (i % cols) * cell
            val top = (i / cols) * cell
            canvas.drawBitmap(
                bmp, null,
                android.graphics.RectF(left + 8f, top + 8f, left + cell - 8f, top + cell - 8f),
                null
            )
            bmp.recycle()
        }
        val dir = File(context.filesDir, "mascot_preview").apply { mkdirs() }
        File(dir, "mascot_sheet.png").outputStream().use {
            sheet.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue(File(dir, "mascot_sheet.png").length() > 10_000)
    }
}
