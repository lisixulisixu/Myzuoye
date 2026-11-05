package com.example.myzuoye

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myzuoye.ui.MascotView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Q 版人物气泡的排版验收图。
 *
 * 用真实的 [MascotView]、真实的 150dp 尺寸，把从短到长的几句台词各画一遍，
 * 拼成一张图导出来看：
 * - 长句有没有折行、有没有被控件边缘裁掉；
 * - 字号缩小后是否还清晰；
 * - 气泡变高时人物是否自动让位，没有和气泡叠在一起。
 */
@RunWith(AndroidJUnit4::class)
class MascotBubblePreviewTest {

    @Test
    fun 导出气泡排版对照图() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        val viewSize = (150 * density).toInt()

        val lines = listOf(
            "喵~",
            "今天也要元气满满",
            "传输中，稍等一下~",
            "文件传好了吗？我有点困了，先去睡一会儿…",
            "这是一段特别长的提示文字，用来验证气泡在极限情况下会自动缩小字号并折行，不会被裁切掉"
        )

        val cellW = viewSize + 30
        val cellH = viewSize + 40
        val cols = 2
        val rows = (lines.size + cols - 1) / cols

        val sheet = Bitmap.createBitmap(cellW * cols, cellH * rows, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.parseColor("#EDF3FB"))

        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22303F")
            textSize = 22f
        }

        lines.forEachIndexed { index, text ->
            val col = index % cols
            val row = index / cols
            val left = col * cellW
            val top = row * cellH

            val view = MascotView(context)
            val spec = View.MeasureSpec.makeMeasureSpec(viewSize, View.MeasureSpec.EXACTLY)
            view.measure(spec, spec)
            view.layout(0, 0, viewSize, viewSize)
            view.say(text)

            canvas.save()
            canvas.translate(
                left.toFloat() + 15f,
                top.toFloat() + 8f
            )
            view.draw(canvas)
            canvas.restore()

            canvas.drawText("${index + 1}：${text.length} 字", left + 8f, top + cellH - 10f, label)
        }

        val dir = File(context.filesDir, "mascot_preview").apply { mkdirs() }
        val out = File(dir, "bubble_sheet.png")
        FileOutputStream(out).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("对照图未生成", out.exists() && out.length() > 0)
    }
}
