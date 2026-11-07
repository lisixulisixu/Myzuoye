package com.example.myzuoye

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myzuoye.ui.ConnectionStepsView
import com.example.myzuoye.ui.TransferProgressView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 新增动效的「肉眼验收」对照图：
 * - 进度条：0% / 35%（流光）/ 100%、流光扫过不同相位、完成闪光的中点；
 * - 连接步骤：五档状态各一帧，等待段的流动虚线、刚点亮段的能量点。
 *
 * 自动断言两件事：流光确实在动（不同相位的画面不同），
 * 进度 35% 时填充像素明显多于 0%。
 */
@RunWith(AndroidJUnit4::class)
class UiMotionPreviewTest {

    private val barWidth = 520
    private val barHeight = 12
    private val stepsWidth = 620
    private val stepsHeight = 64

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** ValueAnimator 只能在 Looper 线程上跑，视图操作统一丢回主线程 */
    private fun onMain(block: () -> Unit) {
        instrumentation.runOnMainSync(block)
    }

    private fun measure(view: View, w: Int, h: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, w, h)
    }

    private fun render(view: View, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        return bmp
    }

    private fun lumaDiff(a: Bitmap, b: Bitmap): Long {
        var diff = 0L
        val pixelsA = IntArray(a.width * a.height)
        val pixelsB = IntArray(b.width * b.height)
        a.getPixels(pixelsA, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(pixelsB, 0, b.width, 0, 0, b.width, b.height)
        for (i in pixelsA.indices) {
            val pa = pixelsA[i]
            val pb = pixelsB[i]
            if (pa != pb) diff++
        }
        return diff
    }

    @Test
    fun 导出进度条与步骤动效对照图() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "motion_preview").apply { mkdirs() }

        // ---- 进度条 ----
        // 每一帧的流程：主线程创建视图并设置状态 → 睡眠等动画推进 → 主线程渲染抓帧
        fun progressFrame(label: String, busy: Boolean, target: Int, waitMs: Long): Bitmap {
            lateinit var view: TransferProgressView
            lateinit var bmp: Bitmap
            onMain {
                view = TransferProgressView(context)
                measure(view, barWidth, barHeight)
                if (busy) view.setBusy(true)
                view.progress = target
            }
            Thread.sleep(waitMs)
            onMain { bmp = render(view, barWidth, barHeight) }
            return bmp
        }

        val frames = mutableListOf<Pair<String, Bitmap>>()
        frames += "0%" to progressFrame("0%", busy = false, target = 0, waitMs = 0)
        frames += "35%+流光" to progressFrame("35%", busy = true, target = 35, waitMs = 450)
        Thread.sleep(300) // 与上一帧错开流光相位
        frames += "35%+流光相位2" to progressFrame("35%", busy = true, target = 35, waitMs = 150)
        frames += "100%" to progressFrame("100%", busy = false, target = 100, waitMs = 450)

        // 完成闪光：进度推满后触发 celebrate，等 250ms 到闪光峰值附近再抓
        lateinit var flashBmp: Bitmap
        lateinit var flashView: TransferProgressView
        onMain {
            flashView = TransferProgressView(context)
            measure(flashView, barWidth, barHeight)
            flashView.progress = 100
        }
        Thread.sleep(450)
        onMain {
            flashView.celebrate()
        }
        Thread.sleep(250)
        onMain { flashBmp = render(flashView, barWidth, barHeight) }
        frames += "完成闪光" to flashBmp

        // ---- 连接步骤 ----
        val stepNames = listOf("发现", "协商中", "组网中", "全部完成", "失败@协商")
        val stepValues = listOf(
            Triple(0, false, "发现"),
            Triple(1, false, "协商中"),
            Triple(2, false, "组网中"),
            Triple(ConnectionStepsView.STEP_DONE, false, "全部完成"),
            Triple(1, true, "失败@协商")
        )
        stepValues.forEachIndexed { index, (step, failed, _) ->
            lateinit var bmp: Bitmap
            onMain {
                val v = ConnectionStepsView(context)
                measure(v, stepsWidth, stepsHeight)
                v.setProgress(step, failed)
                // 测试环境没有窗口附着，动画器不跑；手动注入相位看流动效果
                v.debugPulse(0.42f)
                bmp = render(v, stepsWidth, stepsHeight)
            }
            frames += stepNames[index] to bmp
        }

        // ---- 拼图 ----
        val gap = 6
        val labelH = 22
        val sheetW = maxOf(barWidth, stepsWidth) + gap * 2
        val sheetH = frames.size * (labelH + maxOf(barHeight, stepsHeight) + gap) + gap
        val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.parseColor("#11151C"))
        val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB8C6DB.toInt()
            textSize = 15f
        }
        var y = gap.toFloat()
        frames.forEach { (name, bmp) ->
            canvas.drawText(name, gap.toFloat(), y + 16f, label)
            canvas.drawBitmap(bmp, gap.toFloat(), y + labelH, null)
            y += labelH + bmp.height + gap
            bmp.recycle()
        }

        val out = File(dir, "motion_sheet.png")
        FileOutputStream(out).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        sheet.recycle()
        assertTrue("对照图未生成", out.exists() && out.length() > 0)
    }

    @Test
    fun 流光在不同相位画面不同() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var view: TransferProgressView
        lateinit var a: Bitmap
        lateinit var b: Bitmap
        onMain {
            view = TransferProgressView(context)
            measure(view, barWidth, barHeight)
            view.setBusy(true)
            view.progress = 50
        }
        Thread.sleep(450) // chase 结束，只剩 shimmer 循环
        onMain { a = render(view, barWidth, barHeight) }
        Thread.sleep(120)
        onMain { b = render(view, barWidth, barHeight) }

        // chase 结束后 shimmer 仍在循环，两帧画面必须有可见差异
        val diff = lumaDiff(a, b)
        a.recycle()
        b.recycle()
        assertTrue("流光/追赶动画没有在动（差异像素 $diff）", diff > 200)
    }

    @Test
    fun 进度前进时有填充() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var view: TransferProgressView
        lateinit var bmp: Bitmap
        onMain {
            view = TransferProgressView(context)
            measure(view, barWidth, barHeight)
            view.progress = 35
        }
        Thread.sleep(450) // 等 chase 动画跑完
        onMain { bmp = render(view, barWidth, barHeight) }

        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp.recycle()

        // 中线上左 1/3 应该有填充色（非透明轨道色），右半段应是暗轨道
        val midY = barHeight / 2
        val filled = pixels[midY * bmp.width + barWidth / 3]
        val empty = pixels[midY * bmp.width + barWidth * 5 / 6]
        val filledIsBright = (filled ushr 24) > 0x60
        val emptyIsDim = (empty ushr 24) < 0x40
        assertTrue("35% 处应有填充：alpha=${filled ushr 24}", filledIsBright)
        assertTrue("右侧应是空轨道：alpha=${empty ushr 24}", emptyIsDim)
    }
}
