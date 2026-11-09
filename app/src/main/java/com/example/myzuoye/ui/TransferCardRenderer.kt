package com.example.myzuoye.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.example.myzuoye.R
import com.example.myzuoye.util.Formatters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 传输卡片：把一次传输变成一张可以保存 / 分享的图。
 *
 * 为什么做：传输本身是「一次性」的——传完文件留下，过程什么都没有。
 * 卡片把那一刻留下来：跟谁、传了什么、多大、用了多久、有多快。
 *
 * 画法完全离屏 Canvas，不依赖布局与主题：同一份数据画出来永远一样，
 * 测试里可以直接渲染成 PNG 人工核对（见 TransferCardRendererTest）。
 *
 * 视觉上和主界面同一套语言：深色夜空底 + 蓝紫光斑 + 贴纸小人物。
 * 小人物直接用主界面同一批 PNG（mascot_happy），所以卡片和 App 是「一个人」。
 */
object TransferCardRenderer {

    /** 3:4，微信/相册里都好看，也不至于太大 */
    const val WIDTH = 1080
    const val HEIGHT = 1440

    data class CardData(
        val fileName: String,
        val size: Long,
        /** true = 本机发出；false = 本机接收 */
        val fromMe: Boolean,
        /** 传输完成时刻（epoch 毫秒） */
        val at: Long,
        /** 传输用时（毫秒）；0 表示未知，卡片显示「—」 */
        val durationMs: Long,
        /** 对端设备名，空则显示「对方的手机」 */
        val peer: String,
        /** 本机型号 */
        val deviceName: String
    )

    // 主界面同一套色板，避免卡片和 App 长得不像一家人
    private const val TEXT_PRIMARY = 0xFFEDF2FF.toInt()
    private const val TEXT_SECONDARY = 0xFF9FB0CC.toInt()
    private const val TEXT_MUTED = 0xFF6E7F9B.toInt()
    private const val ACCENT_BLUE = 0xFF3B82F6.toInt()
    private const val ACCENT_GREEN = 0xFF34C759.toInt()

    fun render(context: Context, data: CardData): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()

        drawBackground(canvas, w, h)
        drawFrame(canvas, w, h)
        drawHeader(canvas, data, w)
        drawStatus(canvas, data)
        drawFilePanel(canvas, data)
        drawStats(canvas, data)
        drawMascots(canvas, context, data)
        drawFooter(canvas, data, w, h)

        return bitmap
    }

    /** 卡片文件名（保存 / 分享时用），带上时间避免互相覆盖 */
    fun fileNameFor(data: CardData): String {
        val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "WiFi快传-${fmt.format(Date(data.at))}.png"
    }

    // ------------------------------------------------------------------ 各层

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h,
                0xFF070C16.toInt(), 0xFF12182A.toInt(),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, bg)

        // 三团柔光：左上偏蓝、右下偏紫、中间偏青，让底色「活」一点
        glow(canvas, 170f, 120f, 640f, 0x5C3B82F6)
        glow(canvas, 960f, 1210f, 660f, 0x4A8B5CF6)
        glow(canvas, 540f, 960f, 420f, 0x2622D3EE)
    }

    private fun glow(canvas: Canvas, cx: Float, cy: Float, radius: Float, argb: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(argb, Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, radius, paint)
    }

    private fun drawFrame(canvas: Canvas, w: Float, h: Float) {
        val inset = 34f
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0x1FFFFFFF
        }
        canvas.drawRoundRect(RectF(inset, inset, w - inset, h - inset), 56f, 56f, stroke)
    }

    private fun drawHeader(canvas: Canvas, data: CardData, w: Float) {
        // 左上：应用名 + 一个蓝点，像「已上线」的呼吸灯
        canvas.drawCircle(106f, 118f, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_BLUE })
        canvas.drawText("WiFi 快传", 128f, 128f, text(30f, TEXT_SECONDARY))

        val date = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()).format(Date(data.at))
        val p = text(28f, TEXT_MUTED)
        canvas.drawText(date, w - 104f - p.measureText(date), 128f, p)
    }

    private fun drawStatus(canvas: Canvas, data: CardData) {
        val title = if (data.fromMe) "已送达" else "已收到"
        val check = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = ACCENT_GREEN
        }
        // 对勾：一个圆 + 折线，比 ✓ 字符更可控
        canvas.drawCircle(112f, 232f, 34f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2634C759 })
        val checkPath = Path().apply {
            moveTo(96f, 232f)
            lineTo(108f, 244f)
            lineTo(130f, 219f)
        }
        canvas.drawPath(checkPath, check)

        canvas.drawText(title, 168f, 256f, text(74f, TEXT_PRIMARY, bold = true))
        canvas.drawText(
            "两台手机直接点对点，没有经过任何服务器",
            104f, 330f, text(27f, TEXT_MUTED)
        )
    }

    private fun drawFilePanel(canvas: Canvas, data: CardData) {
        val panel = RectF(80f, 396f, 1000f, 656f)
        canvas.drawRoundRect(panel, 40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF })
        canvas.drawRoundRect(
            panel, 40f, 40f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = 0x1FFFFFFF
            }
        )

        // 文件类型角标：圆角块 + 扩展名
        val icon = RectF(120f, 440f, 240f, 560f)
        canvas.drawRoundRect(icon, 32f, 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x243B82F6 })
        val ext = extensionLabel(data.fileName)
        val extPaint = text(30f, 0xFF9FC2FF.toInt(), bold = true)
        canvas.drawText(
            ext,
            icon.centerX() - extPaint.measureText(ext) / 2f,
            icon.centerY() + 11f,
            extPaint
        )

        val namePaint = text(42f, TEXT_PRIMARY, bold = true)
        canvas.drawText(
            ellipsize(data.fileName, 660f, namePaint),
            280f, 508f, namePaint
        )
        canvas.drawText(
            Formatters.size(data.size),
            280f, 566f, text(30f, TEXT_SECONDARY)
        )
    }

    private fun drawStats(canvas: Canvas, data: CardData) {
        val label = text(26f, TEXT_MUTED)
        val value = text(42f, TEXT_PRIMARY, bold = true)

        val cols = listOf(
            220f to ("用时" to durationText(data.durationMs)),
            540f to ("平均速度" to averageSpeed(data)),
            860f to ("方向" to if (data.fromMe) "本机发送" else "本机接收")
        )
        cols.forEach { (cx, pair) ->
            canvas.drawText(pair.first, cx - label.measureText(pair.first) / 2f, 762f, label)
            canvas.drawText(pair.second, cx - value.measureText(pair.second) / 2f, 828f, value)
        }

        // 两条细分隔线
        val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF }
        canvas.drawRect(400f, 726f, 402f, 848f, divider)
        canvas.drawRect(720f, 726f, 722f, 848f, divider)
    }

    private fun drawMascots(canvas: Canvas, context: Context, data: CardData) {
        // 左边是对方（呆滞脸），右边是本机（开心脸）：同样的贴纸重复两次会像复制粘贴
        val left = loadMascot(context, R.drawable.mascot_idle)
        val right = loadMascot(context, R.drawable.mascot_happy)

        // 贴纸尺寸与位置：两人之间留出 290px 的空当给虚线和徽章，
        // 顶部压在统计行下方（数值基线 828），不遮任何数字
        val size = 250f
        drawMascot(canvas, left, 270f, 1215f, size)
        drawMascot(canvas, right, 810f, 1215f, size)
        left?.recycle()
        right?.recycle()

        // 中间：虚线弧 + 箭头，表示「东西从这边走到那边」
        val arc = Path().apply {
            moveTo(425f, 1120f)
            quadTo(540f, 1050f, 655f, 1120f)
        }
        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
            color = 0x6660A5FA.toInt()
            pathEffect = DashPathEffect(floatArrayOf(18f, 16f), 0f)
        }
        canvas.drawPath(arc, dash)
        // 箭头指向接收方：本机发的指向左（对方），本机收的指向右（本机）
        val head = if (data.fromMe) {
            Path().apply {
                moveTo(446f, 1102f)
                lineTo(420f, 1120f)
                lineTo(448f, 1134f)
                close()
            }
        } else {
            Path().apply {
                moveTo(634f, 1102f)
                lineTo(660f, 1120f)
                lineTo(632f, 1134f)
                close()
            }
        }
        canvas.drawPath(head, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF60A5FA.toInt() })

        // 「传输完成」小徽章压在弧顶上方
        val chip = RectF(430f, 972f, 650f, 1034f)
        canvas.drawRoundRect(chip, 31f, 31f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2634C759 })
        val chipText = text(26f, 0xFF7CEFAF.toInt())
        canvas.drawText("传输完成", chip.centerX() - chipText.measureText("传输完成") / 2f, 1016f, chipText)

        // 人物标签
        drawMascotLabel(canvas, data.peer.ifBlank { "对方的手机" }, 270f)
        drawMascotLabel(canvas, data.deviceName.ifBlank { "本机" }, 810f)
    }

    /**
     * 画一张贴纸。
     *
     * 贴纸原图是白底的方形 PNG，直接铺在深色卡片上像一块亮补丁；
     * 这里裁成圆角矩形，让它看起来是「贴上去的贴纸」而不是一张图片。
     */
    private fun drawMascot(canvas: Canvas, bmp: Bitmap?, cx: Float, bottom: Float, size: Float) {
        if (bmp == null) {
            // 兜底：画一个圆头像，保证卡片永远不为空
            canvas.drawCircle(cx, bottom - size / 2f, size / 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x33FFFFFF
            })
            return
        }
        val scale = (size / bmp.height.toFloat()).coerceAtMost(size / bmp.width.toFloat())
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = cx - dw / 2f
        val top = bottom - dh
        val rect = RectF(left, top, left + dw, top + dh)

        val clip = Path().apply { addRoundRect(rect, 34f, 34f, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(bmp, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()

        // 一圈极淡的白边，把贴纸和背景分开
        canvas.drawRoundRect(
            rect, 34f, 34f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = 0x33FFFFFF
            }
        )
    }

    private fun drawMascotLabel(canvas: Canvas, name: String, cx: Float) {
        val p = text(28f, TEXT_SECONDARY)
        val shown = ellipsize(name, 320f, p)
        canvas.drawText(shown, cx - p.measureText(shown) / 2f, 1300f, p)
    }

    private fun drawFooter(canvas: Canvas, data: CardData, w: Float, h: Float) {
        val p = text(24f, TEXT_MUTED)
        val text = "WiFi 快传 · ${SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()).format(Date(data.at))}"
        canvas.drawText(text, w / 2f - p.measureText(text) / 2f, h - 70f, p)
    }

    // ------------------------------------------------------------------ 工具

    private fun text(size: Float, color: Int, bold: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    /** 超宽就截断加省略号（二分找最长的能放下的前缀，中文宽度差异大，不能按字符数估） */
    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (paint.measureText(text.substring(0, mid) + "…") <= maxWidth) low = mid else high = mid - 1
        }
        return if (low <= 0) "…" else text.substring(0, low) + "…"
    }

    /** 扩展名角标：`photo.jpg` → `JPG`；没有扩展名给 `FILE` */
    private fun extensionLabel(name: String): String {
        val ext = name.substringAfterLast('.', "").trim()
        if (ext.isEmpty() || ext.length > 4 || ext.any { !it.isLetterOrDigit() }) return "FILE"
        return ext.uppercase(Locale.US)
    }

    /** 用时文案：一秒以内保留一位小数，否则交给统一的 Formatters */
    private fun durationText(ms: Long): String = when {
        ms <= 0L -> "—"
        ms < 1000L -> "%.1f 秒".format(Locale.US, ms / 1000f)
        else -> Formatters.duration(ms / 1000L)
    }

    private fun averageSpeed(data: CardData): String {
        if (data.durationMs <= 0L || data.size <= 0L) return "—"
        val bytesPerSecond = (data.size * 1000L / data.durationMs).coerceAtLeast(1L)
        return Formatters.speed(bytesPerSecond)
    }

    private fun loadMascot(context: Context, res: Int): Bitmap? =
        runCatching { BitmapFactory.decodeResource(context.resources, res) }.getOrNull()
}
