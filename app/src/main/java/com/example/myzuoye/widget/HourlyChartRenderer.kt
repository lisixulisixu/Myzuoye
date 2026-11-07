package com.example.myzuoye.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min

/**
 * 逐小时温度曲线。
 *
 * 为什么画成位图而不是用一堆 TextView 拼：
 * RemoteViews 没有自定义 View、没有折线图控件，用 TextView 拼 12 个点既排不齐，
 * 也没法画曲线和渐变。项目里已经有一套「离屏 Canvas 画位图塞进 ImageView」的做法
 * （[WeatherSceneRenderer] 画天气场景就是这么干的），这里沿用同一套路。
 *
 * 画法：
 * - 曲线用「相邻点中点 + 二次贝塞尔」做平滑，比直接 lineTo 顺眼，也不像三次样条那样容易过冲；
 * - 曲线下方填充一层从线色到透明的渐变，一眼能看出「温度是升还是降」；
 * - 每 3 个点标一次小时和温度，其余点只留圆点，小卡片上才不会糊成一片。
 */
object HourlyChartRenderer {

    /**
     * @param temps 逐小时温度，至少两个点才会画
     * @param startHour 第一个点对应的小时（0..23），第一个标签固定写「现在」
     * @return 透明底位图；点数不足时返回 null，调用方据此隐藏图表
     */
    fun render(
        widthPx: Int,
        heightPx: Int,
        temps: List<Int>,
        startHour: Int,
        lineColor: Int,
        textColor: Int,
        dotColor: Int
    ): Bitmap? {
        if (temps.size < 2 || widthPx < 80 || heightPx < 40) return null

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = widthPx.toFloat()
        val h = heightPx.toFloat()

        val density = w / 320f
        val textSize = (10f * density).coerceIn(18f, 34f)
        val paddingX = textSize * 1.1f
        val topPadding = textSize * 1.35f
        val bottomPadding = textSize * 1.5f
        val plotTop = topPadding
        val plotBottom = h - bottomPadding
        val plotHeight = plotBottom - plotTop

        val minTemp = temps.min()
        val maxTemp = temps.max()
        // 温差为 0 时给一个虚拟跨度，否则所有点会压在一条水平线上
        val span = max(1, maxTemp - minTemp).toFloat()
        val stepX = (w - paddingX * 2) / (temps.size - 1)

        fun xOf(index: Int) = paddingX + stepX * index
        fun yOf(temp: Int) = plotBottom - (temp - minTemp) / span * plotHeight * 0.82f - plotHeight * 0.09f

        val points = temps.mapIndexed { index, t -> xOf(index) to yOf(t) }

        // ---- 曲线 ----
        // 每段用「水平切线的三次贝塞尔」连接：控制点取两点的水平中点，
        // 曲线精确穿过每个数据点、在每个点处切线水平，既顺滑又不会过冲出假的峰谷。
        val curve = Path().apply {
            moveTo(points.first().first, points.first().second)
            for (i in 0 until points.size - 1) {
                val (x0, y0) = points[i]
                val (x1, y1) = points[i + 1]
                val midX = (x0 + x1) / 2f
                cubicTo(midX, y0, midX, y1, x1, y1)
            }
        }

        // ---- 曲线下方渐变填充 ----
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, plotTop, 0f, plotBottom,
                intArrayOf(
                    (0x55 shl 24) or (lineColor and 0xFFFFFF),
                    (0x14 shl 24) or (lineColor and 0xFFFFFF),
                    0x00000000
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val fillPath = Path(curve).apply {
            lineTo(points.last().first, plotBottom)
            lineTo(points.first().first, plotBottom)
            close()
        }
        canvas.drawPath(fillPath, fillPaint)

        // ---- 曲线本体 ----
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (1.8f * density).coerceIn(2.4f, 6f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = lineColor
        }
        canvas.drawPath(curve, linePaint)

        // ---- 数据点 ----
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dotColor }
        val dotRadius = (2.1f * density).coerceIn(3f, 7f)
        points.forEach { (x, y) -> canvas.drawCircle(x, y, dotRadius, dotPaint) }

        // ---- 刻度与温度 ----
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            color = textColor
            textAlign = Paint.Align.CENTER
        }
        val labelStep = if (temps.size <= 6) 1 else 3
        temps.forEachIndexed { index, temp ->
            val isLast = index == temps.lastIndex
            val isFirst = index == 0
            // 收尾两个点一定要有标签，否则曲线的「去向」看不出来
            if (index % labelStep != 0 && !isLast) return@forEachIndexed

            val (x, y) = points[index]
            // 温度写在点上方；首尾贴着边缘时往里收一点，避免被裁掉
            val anchorX = x.coerceIn(textSize, w - textSize)
            canvas.drawText("$temp°", anchorX, y - dotRadius - textSize * 0.35f, textPaint)

            val hour = (startHour + index) % 24
            val hourText = if (isFirst) "现在" else "%02d".format(hour)
            canvas.drawText(hourText, anchorX, h - bottomPadding * 0.18f, textPaint)
        }

        // 让最左/最右的标签不被裁掉：文字是居中绘制，靠 paddingX 留出的空间刚刚好
        return bitmap
    }

    /**
     * 没有逐小时数据时画一块「占位图」，而不是把图表整块隐藏。
     *
     * 隐藏会让 4×4 卡片下半截留一大片空白，看起来像渲染失败；
     * 画一条虚线 + 一句提示，位置不变、观感完整，也顺便告诉用户为什么没数据。
     */
    fun renderPlaceholder(
        widthPx: Int,
        heightPx: Int,
        text: String,
        textColor: Int
    ): Bitmap {
        val w = widthPx.coerceAtLeast(80)
        val h = heightPx.coerceAtLeast(40)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val density = w / 320f
        val textSize = (10f * density).coerceIn(18f, 34f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            this.textSize = textSize
            textAlign = Paint.Align.CENTER
        }

        val midY = h / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = textSize * 0.09f
        paint.pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(textSize * 0.5f, textSize * 0.45f), 0f
        )
        val dashY = midY + textSize * 0.9f
        canvas.drawLine(textSize, dashY, w - textSize, dashY, paint)

        paint.style = Paint.Style.FILL
        paint.pathEffect = null
        canvas.drawText(text, w / 2f, midY, paint)
        return bitmap
    }

    /** 卡片上留给图表的可用高度（dp）换算成像素，供 Provider 调用 */
    fun chartHeightPx(cardHeightDp: Int, density: Float): Int =
        (min(cardHeightDp * 0.42f, 96f) * density).toInt().coerceAtLeast(64)
}
