package com.example.myzuoye.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.provider.Settings
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.example.myzuoye.R
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * 封面动态层：直接接管那张「相拥」插画的绘制，并叠加几层会呼吸的动效。
 *
 * 分五层，从下往上：
 * 1. **底图**：慢速 Ken Burns（30 秒一次的轻微推近/平移）+ 贴合「呼吸」的极小幅缩放，
 *    幅度控制在 0.6% 以内，静止看几乎察觉不到，但画面不会像一张贴纸；
 * 2. **光束微光**：顺着原图左上来的窗光做一层极淡的流动亮带，亮度随时间来回；
 * 3. **浮尘**：十几颗微小亮点在光束区域缓慢上浮，模拟空气里的尘埃，也让静态图「活」起来；
 * 4. **发梢**：沿女生长发外缘程序化绘制 16 根带锥度的发丝，发根几乎不动、发梢摆幅最大，
 *    颜色取「被窗光照亮的边缘光」，所以是在深色背景与黑发之间都能看清的冷色调；
 * 5. **收尾**：底部压暗渐变（与页面背景同色，衔接自然）+ 四角轻微暗角。
 *
 * 交互与状态：
 * - 传输成功时调 [celebrate]，从底部升起一串爱心并短暂提高暖光强度；
 * - [setMood] 让边缘光跟随连接状态变色（未连接冷蓝 / 已连接青绿 / 传输中紫 / 失败暖红）。
 *
 * 性能取舍：
 * - Choreographer 帧循环，dt 封顶 1/30 秒，卡顿时不会「跳帧」；
 * - 发丝在**位图坐标系**里绘制，只 concat 一次矩阵，避免每根发丝都做坐标换算；
 * - 光束与浮尘直接用固定 shader / 画点，不创建位图；
 * - 不可见或离屏时停止循环，零开销；系统关闭动画时只画静态一帧。
 *
 * 这个 View 纯粹是装饰，不重写 onTouchEvent，绝不拦截点击。
 */
class CoverLiveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** 未连接：冷蓝边缘光 */
        const val MOOD_IDLE = 0

        /** 已连接：青绿边缘光 */
        const val MOOD_CONNECTED = 1

        /** 传输中：淡紫边缘光，配合爱心显得「热闹」 */
        const val MOOD_TRANSFER = 2

        /** 失败：暖红边缘光 */
        const val MOOD_ERROR = 3
    }

    private val density = resources.displayMetrics.density

    private val bitmap: Bitmap? = runCatching {
        BitmapFactory.decodeResource(resources, R.drawable.p1)
    }.getOrNull()

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val strandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // 柔化边缘：硬边细线在深色背景上会像「划痕」，模糊一点才像被光照亮的发丝
        maskFilter = BlurMaskFilter(0.9f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val motePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val matrix = Matrix()
    private val hairPath = Path()

    /** 底图缩放到铺满控件的比例，onSizeChanged 时算一次 */
    private var baseScale = 1f

    /** 动画时间（秒） */
    private var time = 0f
    private var looping = false
    private var lastFrameNanos = 0L

    private var mood = MOOD_IDLE

    /** 爱心迸发的剩余时间；> 0 时暖光增强 */
    private var celebrateLeft = 0f

    /** 系统是否关闭了动画 */
    private val animationsOff: Boolean by lazy {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ 发丝定义

    /**
     * 一根发丝：坐标全部归一化到图片（0..1），绘制时 concat 底图矩阵，跟着缩放一起走。
     *
     * [bow] 是弯曲方向：正数向外鼓、负数向内收；
     * [phase] 错开摆动节奏，避免 16 根同时甩。
     */
    private class Strand(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val bow: Float,
        val phase: Float,
        val widthDp: Float,
        val alpha: Int
    )

    /**
     * 发丝都贴着女生长发的外缘走：从头部左侧绕到右上方，再顺着发尾垂到右下。
     * 参数是照着画面反复比对调出来的，改动前建议先截图对照。
     */
    private val strands = arrayOf(
        // 面部左侧的碎发（两条，幅度小）
        Strand(0.452f, 0.400f, 0.408f, 0.462f, -0.30f, 0.00f, 1.5f, 120),
        Strand(0.462f, 0.415f, 0.421f, 0.487f, -0.22f, 1.10f, 1.2f, 96),
        // 头顶到右肩的轮廓（沿发丝走向）
        Strand(0.470f, 0.375f, 0.560f, 0.428f, 0.26f, 2.20f, 1.6f, 104),
        Strand(0.487f, 0.385f, 0.610f, 0.462f, 0.22f, 0.70f, 1.5f, 96),
        Strand(0.500f, 0.397f, 0.658f, 0.498f, 0.18f, 3.10f, 1.4f, 90),
        Strand(0.512f, 0.412f, 0.700f, 0.540f, 0.15f, 1.60f, 1.5f, 108),
        Strand(0.520f, 0.430f, 0.731f, 0.588f, 0.12f, 4.05f, 1.3f, 88),
        // 发尾最外沿（摆幅最大，也是「发梢会动」最容易被看到的地方）
        Strand(0.528f, 0.450f, 0.757f, 0.640f, 0.10f, 2.65f, 1.7f, 116),
        Strand(0.534f, 0.470f, 0.769f, 0.685f, 0.08f, 5.20f, 1.5f, 104),
        Strand(0.536f, 0.492f, 0.762f, 0.722f, 0.06f, 0.35f, 1.6f, 100),
        Strand(0.532f, 0.520f, 0.742f, 0.756f, 0.04f, 3.70f, 1.4f, 92),
        Strand(0.522f, 0.548f, 0.712f, 0.782f, 0.02f, 1.95f, 1.3f, 84),
        // 下方散落的几缕
        Strand(0.508f, 0.572f, 0.678f, 0.800f, -0.02f, 4.60f, 1.2f, 78),
        Strand(0.492f, 0.594f, 0.640f, 0.810f, -0.04f, 2.90f, 1.1f, 70),
        Strand(0.474f, 0.610f, 0.598f, 0.806f, -0.06f, 0.95f, 1.0f, 62),
        Strand(0.456f, 0.622f, 0.556f, 0.792f, -0.08f, 5.60f, 0.9f, 56)
    )

    // ------------------------------------------------------------------ 浮尘 / 爱心

    private class Mote(var x: Float, var y: Float, var r: Float, var vy: Float, var phase: Float)

    private class Heart(
        var x: Float,
        var y: Float,
        var vy: Float,
        var sway: Float,
        var size: Float,
        var life: Float
    )

    private val motes = mutableListOf<Mote>()
    private val hearts = mutableListOf<Heart>()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!looping) return
            val dt = ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 1f / 30f)
            lastFrameNanos = frameTimeNanos
            advance(dt)
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animationsOff) invalidate() else startLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopLoop()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (animationsOff) return
        if (visibility == VISIBLE && isAttachedToWindow) startLoop() else stopLoop()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (animationsOff) return
        if (visibility == VISIBLE && isAttachedToWindow) startLoop() else stopLoop()
    }

    private fun startLoop() {
        if (looping) return
        looping = true
        lastFrameNanos = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        looping = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    // ------------------------------------------------------------------ 对外接口

    /** 传输成功：爱心迸发 + 暖光增强一小会儿 */
    fun celebrate() {
        if (width == 0 || height == 0) return
        celebrateLeft = 2.6f
        val w = width.toFloat()
        val h = height.toFloat()
        repeat(16) {
            hearts += Heart(
                x = w * (0.38f + Random.nextFloat() * 0.24f),
                y = h * (0.82f + Random.nextFloat() * 0.12f),
                vy = -(0.16f + Random.nextFloat() * 0.22f),
                sway = Random.nextFloat() * 6.28f,
                size = (5f + Random.nextFloat() * 6f) * density,
                life = 1f
            )
        }
        if (!animationsOff && !looping) {
            // 极少数情况下动画被停掉了（比如刚回到前台），补一帧让它动起来
            startLoop()
        }
    }

    /** 边缘光跟随连接状态变色 */
    fun setMood(newMood: Int) {
        if (mood == newMood) return
        mood = newMood
        invalidate()
    }

    // ------------------------------------------------------------------ 尺寸 / 动画

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val bmp = bitmap ?: return
        // 铺满控件（centerCrop）：留 7% 余量给推近与平移，避免边缘露白
        baseScale = max(w.toFloat() / bmp.width, h.toFloat() / bmp.height) * 1.07f
        buildScrim(w.toFloat(), h.toFloat())
        if (motes.isEmpty()) spawnMotes(w.toFloat(), h.toFloat())
    }

    private fun buildScrim(w: Float, h: Float) {
        val bottom = context.getColor(R.color.bg_grad_top)
        // 底部过渡到页面背景色，封面与下方内容之间不会出现一条硬边
        scrimPaint.shader = LinearGradient(
            0f, h * 0.42f, 0f, h,
            intArrayOf(Color.TRANSPARENT, (0xB3 shl 24) or (bottom and 0xFFFFFF), bottom),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun spawnMotes(w: Float, h: Float) {
        val random = Random(System.nanoTime())
        repeat(18) {
            motes += Mote(
                // 光束在原图左上到中部，浮尘集中在这一带才自然
                x = w * (0.06f + random.nextFloat() * 0.5f),
                y = h * (0.05f + random.nextFloat() * 0.85f),
                r = (0.7f + random.nextFloat() * 1.6f) * density,
                vy = 0.008f + random.nextFloat() * 0.022f,
                phase = random.nextFloat() * 6.28f
            )
        }
    }

    private fun advance(dt: Float) {
        time += dt
        val h = height.toFloat()
        val w = width.toFloat()

        for (m in motes) {
            m.y -= m.vy * dt * h
            m.phase += dt * 0.9f
            if (m.y < -m.r * 4f) {
                m.y = h + m.r * 4f
                m.x = w * (0.06f + Random.nextFloat() * 0.5f)
            }
        }

        if (hearts.isNotEmpty()) {
            val it = hearts.iterator()
            while (it.hasNext()) {
                val heart = it.next()
                heart.y += heart.vy * dt * h
                heart.sway += dt * 2.1f
                heart.x += sin(heart.sway) * 7f * density * dt
                heart.life -= dt * 0.42f
                if (heart.life <= 0f || heart.y < -h * 0.1f) it.remove()
            }
        }

        if (celebrateLeft > 0f) celebrateLeft = (celebrateLeft - dt).coerceAtLeast(0f)
    }

    // ------------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val bmp = bitmap ?: return
        if (w <= 0f || h <= 0f) return
        val t = if (animationsOff) 0f else time

        drawPhoto(canvas, bmp, w, h, t)
        drawBeam(canvas, w, h, t)
        drawMotes(canvas, t)
        drawHair(canvas, bmp, t)
        drawHearts(canvas)
        canvas.drawRect(0f, 0f, w, h, scrimPaint)
    }

    /** 底图：Ken Burns 推近 + 轻微横移 + 呼吸起伏（幅度都很小） */
    private fun drawPhoto(canvas: Canvas, bmp: Bitmap, w: Float, h: Float, t: Float) {
        val zoom = 1f + 0.022f * sin(t * 0.21f)
        // 呼吸：约 5.5 秒一次，只影响 0.4%，静止盯着看才觉得在动
        val breath = 1f + 0.004f * sin(t * 1.15f)
        val scale = baseScale * zoom * breath
        val panX = w * 0.008f * sin(t * 0.13f)
        val panY = h * 0.006f * cos(t * 0.17f)

        val dx = (w - bmp.width * scale) / 2f + panX
        val dy = (h - bmp.height * scale) / 2f + panY
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        canvas.drawBitmap(bmp, matrix, bitmapPaint)
    }

    /** 顺着原图窗光方向的一层极淡亮带，亮度缓慢起伏 */
    private fun drawBeam(canvas: Canvas, w: Float, h: Float, t: Float) {
        val shimmer = 0.45f + 0.55f * ((sin(t * 0.42f) + 1f) / 2f)
        val alpha = (10 + 14 * shimmer).toInt()
        beamPaint.shader = LinearGradient(
            w * 0.1f, 0f, w * 0.62f, h,
            intArrayOf(Color.TRANSPARENT, (alpha shl 24) or 0xBBD5FF, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, beamPaint)

        // 传输完成的一瞬间给一团暖光，配合爱心
        if (celebrateLeft > 0f) {
            val k = (celebrateLeft / 2.6f).coerceIn(0f, 1f)
            glowPaint.shader = RadialGradient(
                w * 0.5f, h * 0.72f, w * 0.55f,
                intArrayOf(((70 * k).toInt() shl 24) or 0xFFC98A, Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, glowPaint)
        }
    }

    private fun drawMotes(canvas: Canvas, t: Float) {
        for (m in motes) {
            val twinkle = (sin(t * 1.1f + m.phase) + 1f) / 2f
            val alpha = (26 + 74 * twinkle).toInt().coerceIn(0, 255)
            motePaint.color = (alpha shl 24) or 0xDCE9FF
            canvas.drawCircle(m.x, m.y, m.r, motePaint)
        }
    }

    /**
     * 发丝：在位图坐标系里画，外面 concat 一次矩阵。
     *
     * 每根用「采样中心线 + 两侧法线偏移」拼成带锥度的闭合路径，
     * 比直接 drawPath 描一条等宽线更像头发。
     */
    private fun drawHair(canvas: Canvas, bmp: Bitmap, t: Float) {
        canvas.save()
        canvas.concat(matrix)

        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        // 线宽按矩阵缩放折算，保证在屏幕上始终是 1dp 左右
        val scale = baseScale.coerceAtLeast(0.01f)
        val rimColor = rimColor()

        for (s in strands) {
            val x0 = s.x0 * bw
            val y0 = s.y0 * bh
            val x1 = s.x1 * bw
            val y1 = s.y1 * bh
            val dx = x1 - x0
            val dy = y1 - y0
            val len = hypot(dx, dy)
            if (len < 1f) continue
            val nx = -dy / len
            val ny = dx / len

            // 发根几乎不动、发梢摆幅最大：用 t^1.7 的权重
            val sway = sin(t * 1.25f + s.phase) * 0.72f + sin(t * 0.6f + s.phase * 1.7f) * 0.28f
            val amp = len * 0.11f
            val bow = s.bow * len * 0.20f

            // 末端朝发根方向回收：露在外面的只是一小段发梢，不是一根横贯画面的长线
            val tipX = x0 + (x1 - x0) * 0.80f
            val tipY = y0 + (y1 - y0) * 0.80f
            val endX = tipX + nx * (sway * amp + bow)
            val endY = tipY + ny * (sway * amp + bow)
            val c1x = x0 + dx * 0.42f + nx * bow * 0.7f
            val c1y = y0 + dy * 0.42f + ny * bow * 0.7f
            val c2x = x0 + dx * 0.76f + nx * (bow * 0.9f + sway * amp * 0.6f)
            val c2y = y0 + dy * 0.76f + ny * (bow * 0.9f + sway * amp * 0.6f)

            hairPath.reset()
            var started = false
            val steps = 7
            val halfWidth0 = (s.widthDp * density / scale) * 0.5f
            // 左半边：从发根到发梢
            for (i in 0..steps) {
                val u = i.toFloat() / steps
                val pt = bezier(x0, y0, c1x, c1y, c2x, c2y, endX, endY, u)
                val nrm = normalAt(x0, y0, c1x, c1y, c2x, c2y, endX, endY, u)
                val halfW = halfWidth0 * (1f - u).pow(0.7f)
                val lx = pt[0] + nrm[0] * halfW
                val ly = pt[1] + nrm[1] * halfW
                if (!started) {
                    hairPath.moveTo(lx, ly)
                    started = true
                } else {
                    hairPath.lineTo(lx, ly)
                }
            }
            // 右半边：从发梢回到发根，闭合出锥形
            for (i in steps downTo 0) {
                val u = i.toFloat() / steps
                val pt = bezier(x0, y0, c1x, c1y, c2x, c2y, endX, endY, u)
                val nrm = normalAt(x0, y0, c1x, c1y, c2x, c2y, endX, endY, u)
                val halfW = halfWidth0 * (1f - u).pow(0.7f)
                hairPath.lineTo(pt[0] - nrm[0] * halfW, pt[1] - nrm[1] * halfW)
            }
            hairPath.close()

            // 透明度压到 45% 左右：发丝是「边缘光」，不该比人物本身更抢眼
            val alpha = (s.alpha * 0.45f).toInt().coerceIn(0, 255)
            strandPaint.color = (alpha shl 24) or (rimColor and 0xFFFFFF)
            canvas.drawPath(hairPath, strandPaint)
        }

        canvas.restore()
    }

    /** 三次贝塞尔上的点 */
    private fun bezier(
        x0: Float, y0: Float, x1: Float, y1: Float,
        x2: Float, y2: Float, x3: Float, y3: Float, t: Float
    ): FloatArray {
        val mt = 1f - t
        val a = mt * mt * mt
        val b = 3f * mt * mt * t
        val c = 3f * mt * t * t
        val d = t * t * t
        return floatArrayOf(
            a * x0 + b * x1 + c * x2 + d * x3,
            a * y0 + b * y1 + c * y2 + d * y3
        )
    }

    /** 贝塞尔切线法线（单位向量，返回 [nx, ny]） */
    private fun normalAt(
        x0: Float, y0: Float, x1: Float, y1: Float,
        x2: Float, y2: Float, x3: Float, y3: Float, t: Float
    ): FloatArray {
        val mt = 1f - t
        val dx = 3f * mt * mt * (x1 - x0) + 6f * mt * t * (x2 - x1) + 3f * t * t * (x3 - x2)
        val dy = 3f * mt * mt * (y1 - y0) + 6f * mt * t * (y2 - y1) + 3f * t * t * (y3 - y2)
        val len = hypot(dx, dy).coerceAtLeast(0.0001f)
        return floatArrayOf(-dy / len, dx / len)
    }

    /** 边缘光颜色：跟随连接状态，但都压得比较淡，避免抢画面 */
    private fun rimColor(): Int = when (mood) {
        MOOD_CONNECTED -> 0x9FF1D8 // 青绿
        MOOD_TRANSFER -> 0xD9C4FF // 淡紫
        MOOD_ERROR -> 0xFFC0B4 // 暖红
        else -> 0xC9DAFF          // 冷蓝
    }

    private fun drawHearts(canvas: Canvas) {
        if (hearts.isEmpty()) return
        for (heart in hearts) {
            val alpha = (170 * heart.life.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
            heartPaint.color = (alpha shl 24) or 0xFF9DB4
            drawHeart(canvas, heart.x, heart.y, heart.size)
        }
    }

    /** 用两个圆 + 一个三角拼一颗心，比 Path 里手写贝塞尔省事也够可爱 */
    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val r = size * 0.5f
        canvas.drawCircle(cx - r * 0.55f, cy - r * 0.25f, r * 0.62f, heartPaint)
        canvas.drawCircle(cx + r * 0.55f, cy - r * 0.25f, r * 0.62f, heartPaint)
        hairPath.reset()
        hairPath.moveTo(cx - r * 1.05f, cy)
        hairPath.lineTo(cx + r * 1.05f, cy)
        hairPath.lineTo(cx, cy + r * 1.45f)
        hairPath.close()
        canvas.drawPath(hairPath, heartPaint)
    }
}
