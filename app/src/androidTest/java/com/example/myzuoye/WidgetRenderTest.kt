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
