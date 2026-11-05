package com.example.myzuoye

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myzuoye.weather.WeatherCondition
import com.example.myzuoye.widget.WeatherSceneRenderer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 把每种天气的昼 / 夜场景拼成一张对照图，导出到应用外部目录。
 *
 * 不是断言型测试，而是「肉眼验收」工具：调完配色跑一次，
 * `adb pull` 出来就能一眼看出哪一档天气画崩了（比如云太厚糊成一片白）。
 */
@RunWith(AndroidJUnit4::class)
class WeatherScenePreviewTest {

    @Test
    fun 导出天气对照图() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "weather_preview").apply { mkdirs() }

        val cellW = 380
        val cellH = 170
        val gap = 8
        val conditions = WeatherCondition.values()
        val sheet = Bitmap.createBitmap(
            cellW * 2 + gap * 3,
            (cellH + gap) * conditions.size + gap,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.parseColor("#1B1F27"))

        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
        }

        conditions.forEachIndexed { row, condition ->
            for ((col, isDay) in listOf(true, false).withIndex()) {
                val left = (gap + (cellW + gap) * col).toFloat()
                val top = (gap + (cellH + gap) * row).toFloat()
                val scene = WeatherSceneRenderer.renderBackground(cellW, cellH, condition, isDay)
                canvas.drawBitmap(scene, left, top, null)
                // 天体层按真实布局的样子叠在右上角
                val sky = WeatherSceneRenderer.renderCelestial(cellH, condition, isDay)
                if (sky != null) {
                    canvas.drawBitmap(
                        sky,
                        left + cellW - cellH + 8f,
                        top + 6f,
                        null
                    )
                }
                canvas.drawText(
                    "${condition.name} / ${if (isDay) "白天" else "夜间"}",
                    left + 10f,
                    top + cellH - 12f,
                    label
                )
            }
        }

        val out = File(dir, "weather_sheet.png")
        FileOutputStream(out).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("对照图未生成", out.exists() && out.length() > 0)
    }
}
