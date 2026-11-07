package com.example.myzuoye

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myzuoye.widget.TransferWidgetProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 小组件渲染验收（不是断言型测试，主要是「把 RemoteViews 真的应用一次」）。
 *
 * 价值有两点：
 * 1. **能抓住 RemoteViews 的运行时错误**——比如用了白名单外的控件、引用了不存在的 id，
 *    这些在编译期完全看不出来，只有 apply 的时候才会抛 ActionException；
 * 2. 把 4×2 与 2×2 两种尺寸画成位图导出，肉眼核对紧凑模式有没有把文字挤掉。
 */
@RunWith(AndroidJUnit4::class)
class WidgetRenderTest {

    @Test
    fun renderStandardAndCompact() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val manager = AppWidgetManager.getInstance(context)
        val density = context.resources.displayMetrics.density

        val standard = render(context, 250, 110)
        val compact = render(context, 165, 92)

        assertNotNull(standard)
        assertNotNull(compact)

        // 拼成一张对照图：左 4×2，右 2×2
        val gap = (12 * density).toInt()
        val sheetW = standard.width + compact.width + gap * 3
        val sheetH = maxOf(standard.height, compact.height) + gap * 2
        val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(0xFF202430.toInt())
        canvas.drawBitmap(standard, gap.toFloat(), gap.toFloat(), null)
        canvas.drawBitmap(
            compact,
            (gap * 2 + standard.width).toFloat(),
            gap.toFloat(),
            null
        )

        val dir = File(context.filesDir, "widget_preview").apply { mkdirs() }
        File(dir, "widget_sheet.png").outputStream().use {
            sheet.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /**
     * 4×4 大卡与 1×1 迷你卡。
     *
     * 这两套布局是新加的，最容易犯的错是「布局里有、代码里没设」或者用了
     * RemoteViews 不支持的控件——编译期完全看不出来，只有 apply 才会抛。
     */
    @Test
    fun renderLargeAndTiny() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val density = context.resources.displayMetrics.density

        val large = render(context, 250, 240)
        val tiny = render(context, 72, 72)
        assertNotNull(large)
        assertNotNull(tiny)

        val gap = (12 * density).toInt()
        val sheetW = large.width + tiny.width + gap * 3
        val sheetH = maxOf(large.height, tiny.height) + gap * 2
        val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(0xFF202430.toInt())
        canvas.drawBitmap(large, gap.toFloat(), gap.toFloat(), null)
        canvas.drawBitmap(tiny, (gap * 2 + large.width).toFloat(), gap.toFloat(), null)

        val dir = File(context.filesDir, "widget_preview").apply { mkdirs() }
        File(dir, "widget_sheet_large.png").outputStream().use {
            sheet.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /**
     * 三档形态按**桌面真实下发的尺寸**渲染。
     *
     * 之前的用例用的是 250×110（标准卡）和 165×92（紧凑卡），
     * 都是自己估的尺寸，结果真机上 4×2 报的是 300×100，落进 1×1 分支，
     * 整张卡只剩温度——测试全绿，桌面上一眼就看出来不对。
     * 这里改成 Pixel 桌面（411dp 宽、4 列）的实际值。
     */
    @Test
    fun renderRealLauncherSizes() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val density = context.resources.displayMetrics.density

        val oneByOne = render(context, 90, 90)
        val twoByTwo = render(context, 180, 180)
        val fourByTwo = render(context, 300, 100)
        val fourByFour = render(context, 300, 300)

        val gap = (12 * density).toInt()
        val colW = maxOf(oneByOne.width, twoByTwo.width) + gap
        val sheetW = colW + fourByTwo.width + gap * 3
        val sheetH = fourByFour.height + twoByTwo.height + gap * 4
        val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(0xFF202430.toInt())

        canvas.drawBitmap(oneByOne, gap.toFloat(), gap.toFloat(), null)
        canvas.drawBitmap(twoByTwo, gap.toFloat(), (gap * 2 + oneByOne.height).toFloat(), null)
        canvas.drawBitmap(fourByTwo, (gap * 2 + colW).toFloat(), gap.toFloat(), null)
        canvas.drawBitmap(
            fourByFour,
            (gap * 2 + colW).toFloat(),
            (gap * 2 + fourByTwo.height).toFloat(),
            null
        )

        val dir = File(context.filesDir, "widget_preview").apply { mkdirs() }
        File(dir, "widget_sheet_real.png").outputStream().use {
            sheet.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun render(
        context: Context,
        widthDp: Int,
        heightDp: Int
    ): Bitmap {
        val model = TransferWidgetProvider.buildModel(context, widthDp, heightDp)
        val density = context.resources.displayMetrics.density
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()
        val views = TransferWidgetProvider.buildViews(context, model, full = true)

        // apply 会真正执行所有 RemoteViews action，白名单/ID 有问题会在这里抛
        val host = FrameLayout(context)
        val view: View = views.apply(context, host)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, widthPx, heightPx)

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }
}
