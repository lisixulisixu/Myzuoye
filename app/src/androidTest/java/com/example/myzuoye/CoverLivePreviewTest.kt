package com.example.myzuoye

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myzuoye.ui.CoverLiveView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 封面动效的「肉眼验收」+ 头发确实在动的自动断言。
 *
 * 封面这块全是手感活（摆幅、光的浓度、粒子的密度），单测只能保证参数不越界，
 * 真正的判断得看图。所以这里把同一时刻的几帧拼成一张对照图导出，
 * `adb pull` 出来看有没有：头发被拉出拖影、光线过曝、光斑糊成一块白。
 */
@RunWith(AndroidJUnit4::class)
class CoverLivePreviewTest {

    private val width = 1080
    private val height = 440

    private fun newView(): CoverLiveView {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = CoverLiveView(context)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        return view
    }

    private fun renderFrame(view: CoverLiveView, t: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.render(canvas, t)
        return bitmap
    }

    @Test
    fun 导出封面分帧对照图() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = newView()
        val times = listOf(0f, 0.75f, 1.5f, 2.25f, 3.0f, 3.75f)

        val gap = 6
        val labelH = 22
        val cols = 2
        val rows = (times.size + cols - 1) / cols
        val cellW = width / 2
        val cellH = height / 2
        val sheet = Bitmap.createBitmap(
            cellW * cols + gap * (cols + 1),
            (cellH + labelH) * rows + gap * (rows + 1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.parseColor("#11151C"))
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB8C6DB.toInt()
            textSize = 15f
        }
        val src = android.graphics.Rect(0, 0, width, height)
        val dst = android.graphics.Rect()

        times.forEachIndexed { index, t ->
            val col = index % cols
            val row = index / cols
            val left = (gap + (cellW + gap) * col)
            val top = (gap + (cellH + labelH + gap) * row)
            dst.set(left, top, left + cellW, top + cellH)
            val frame = renderFrame(view, t)
            canvas.drawBitmap(frame, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.drawText("t = ${t}s", left + 8f, top + cellH + 16f, label)
            frame.recycle()
        }

        val dir = File(context.filesDir, "cover_preview").apply { mkdirs() }
        val out = File(dir, "cover_frames.png")
        FileOutputStream(out).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("对照图未生成", out.exists() && out.length() > 0)
    }

    /** 头发区与衬衫区的帧间差异对比：头发应该明显更「动」 */
    @Test
    fun 头发区域在两帧之间确实发生了形变() {
        val view = newView()
        val a = renderFrame(view, 0.4f)
        val b = renderFrame(view, 1.6f)

        val hairDiff = diffRatio(a, b, 0.47f, 0.34f, 0.70f, 0.72f)
        val shirtDiff = diffRatio(a, b, 0.06f, 0.34f, 0.26f, 0.72f)

        a.recycle()
        b.recycle()

        assertTrue("头发区完全没有变化，形变没生效", hairDiff > 0.004f)
        assertTrue(
            "头发区变化($hairDiff) 没有明显强于衬衫区($shirtDiff)，可能权重漏到了衣服上",
            hairDiff > shirtDiff * 1.6f
        )
    }

    /** 头发局部放大对照：确认形变是「真的在飘」而不是拖影/折线 */
    @Test
    fun 导出头发局部放大对照() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = newView()
        val times = listOf(0f, 0.9f, 1.8f, 2.7f)

        val x0 = (0.42f * width).toInt()
        val y0 = (0.22f * height).toInt()
        val x1 = (0.76f * width).toInt()
        val y1 = (0.86f * height).toInt()
        val cropW = x1 - x0
        val cropH = y1 - y0
        val scale = 1.2f
        val cellW = (cropW * scale).toInt()
        val cellH = (cropH * scale).toInt()

        val sheet = Bitmap.createBitmap(cellW * 2 + 18, (cellH + 6) * 2 + 12, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.parseColor("#11151C"))
        val src = android.graphics.Rect(x0, y0, x1, y1)
        val dst = android.graphics.Rect()

        times.forEachIndexed { index, t ->
            val col = index % 2
            val row = index / 2
            val left = 6 + (cellW + 6) * col
            val top = 6 + (cellH + 6) * row
            dst.set(left, top, left + cellW, top + cellH)
            val frame = renderFrame(view, t)
            canvas.drawBitmap(frame, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            frame.recycle()
        }

        val dir = File(context.filesDir, "cover_preview").apply { mkdirs() }
        val out = File(dir, "cover_hair_zoom.png")
        FileOutputStream(out).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("放大对照图未生成", out.exists() && out.length() > 0)
    }

    /** 原图 vs 加了柔光的渲染：确认光是「软的」，没有把画面糊成一片 */
    @Test
    fun 导出原图与特效对照() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = newView()
        val frame = renderFrame(view, 0f)

        val photo = android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.p1)
        val plain = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(plain)
        canvas.drawColor(Color.BLACK)
        val scale = maxOf(width.toFloat() / photo.width, height.toFloat() / photo.height) * 1.07f
        val m = android.graphics.Matrix()
        m.setScale(scale, scale)
        m.postTranslate((width - photo.width * scale) / 2f, (height - photo.height * scale) / 2f)
        canvas.drawBitmap(photo, m, Paint(Paint.FILTER_BITMAP_FLAG))

        val x0 = (0.40f * width).toInt()
        val y0 = (0.20f * height).toInt()
        val x1 = (0.78f * width).toInt()
        val y1 = (0.88f * height).toInt()
        val src = android.graphics.Rect(x0, y0, x1, y1)
        val cropW = x1 - x0
        val cropH = y1 - y0
        val scaleUp = 1.15f
        val cellW = (cropW * scaleUp).toInt()
        val cellH = (cropH * scaleUp).toInt()

        val sheet = Bitmap.createBitmap(cellW * 2 + 18, cellH + 34, Bitmap.Config.ARGB_8888)
        val sheetCanvas = Canvas(sheet)
        sheetCanvas.drawColor(Color.parseColor("#11151C"))
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB8C6DB.toInt()
            textSize = 16f
        }
        var left = 6
        for ((name, bmp) in listOf("原图" to plain, "加柔光后" to frame)) {
            val dst = android.graphics.Rect(left, 6, left + cellW, 6 + cellH)
            sheetCanvas.drawBitmap(bmp, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            sheetCanvas.drawText(name, left + 6f, 6 + cellH + 20f, label)
            left += cellW + 6
        }
        plain.recycle()
        frame.recycle()

        val dir = File(context.filesDir, "cover_preview").apply { mkdirs() }
        val out = File(dir, "cover_light_compare.png")
        FileOutputStream(out).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("对照图未生成", out.exists() && out.length() > 0)
    }

    /** 区域内「变化明显」的像素占比 */    private fun diffRatio(
        a: Bitmap,
        b: Bitmap,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float
    ): Float {
        val left = (x0 * width).toInt()
        val top = (y0 * height).toInt()
        val right = (x1 * width).toInt().coerceAtMost(width)
        val bottom = (y1 * height).toInt().coerceAtMost(height)
        if (right <= left || bottom <= top) return 0f
        val w = right - left
        val h = bottom - top
        val pa = IntArray(w * h)
        val pb = IntArray(w * h)
        a.getPixels(pa, 0, w, left, top, w, h)
        b.getPixels(pb, 0, w, left, top, w, h)
        var changed = 0
        for (i in pa.indices) {
            val ca = pa[i]
            val cb = pb[i]
            val d = kotlin.math.abs(Color.red(ca) - Color.red(cb)) +
                kotlin.math.abs(Color.green(ca) - Color.green(cb)) +
                kotlin.math.abs(Color.blue(ca) - Color.blue(cb))
            if (d > 30) changed++
        }
        return changed.toFloat() / pa.size
    }
}
