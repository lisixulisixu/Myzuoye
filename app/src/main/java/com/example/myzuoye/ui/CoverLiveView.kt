package com.example.myzuoye.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * 封面动态层：接管这张「相拥」插画，让画面里的东西真的动起来。
 *
 * 和上一版的区别：
 * 1. **头发是真的在动**——不再是往图上画几根发光线条，而是把整张图铺成一张
 *    [MESH_COLS] × [MESH_ROWS] 的网格，只让长发那一块按 [HairField] 的位移场弯折，
 *    所以动的是原图里的头发像素本身，循环播放、没有接缝；
 * 2. **光线不再突兀**——发丝线条全部删除，改成几团极淡的柔光落在头发受光的一侧，
 *    缓慢呼吸，看上去是「光打在头发上」，而不是「头发上贴了几道白线」；
 * 3. **环境特效补回来了**——漂移光斑、斜射光束、焦外光点、浮尘、星芒，亮度都压得很低，
 *    效果是画面「活的」，不抢人物。
 *
 * 层的顺序（下 → 上）：
 * 底图（网格形变）→ 头发柔光 → 光束 → 光斑 → 焦外光点 → 浮尘 → 星芒 → 爱心 → 压暗 + 暗角。
 *
 * 交互与状态：
 * - 传输成功调 [celebrate]：爱心升空 + 短暂暖光；
 * - [setMood] 让头发柔光跟随连接状态变色（未连接冷蓝 / 已连接青绿 / 传输中淡紫 / 失败暖红）。
 *
 * 性能取舍：
 * - Choreographer 帧循环，dt 封顶 1/30 秒，卡顿时不「跳帧」；
 * - 网格顶点坐标一次预计算，每帧只做一次 `Matrix.mapPoints` + 700 次正弦，无对象分配；
 * - 所有渐变 shader 在 [onSizeChanged] 里建好，每帧只改 `Paint.alpha` 并用 canvas 平移，
 *   避免每帧 new RadialGradient 触发 GC；
 * - 不可见或离屏时停止循环，零开销；系统关闭动画时只画静态一帧。
 *
 * 这个 View 纯粹是装饰，不重写 onTouchEvent，绝不拦截点击。
 */
class CoverLiveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** 未连接：冷蓝柔光 */
        const val MOOD_IDLE = 0

        /** 已连接：青绿柔光 */
        const val MOOD_CONNECTED = 1

        /** 传输中：淡紫柔光 */
        const val MOOD_TRANSFER = 2

        /** 失败：暖红柔光 */
        const val MOOD_ERROR = 3

        /**
         * 网格密度。34 × 19 个格子（700 个顶点）在 1280 宽的图上刚好够顺：
         * 再稀会在头发边缘看到折线，再密 CPU 端每帧的正弦就白算了。
         */
        private const val MESH_COLS = 34
        private const val MESH_ROWS = 19

        /** 发梢最大摆幅（dp）。再大头发轮廓会和背景「拖影」分离 */
        private const val SWAY_DP = 4.2f
    }

    private val density = resources.displayMetrics.density

    private val bitmap: Bitmap? = runCatching {
        BitmapFactory.decodeResource(resources, R.drawable.p1)
    }.getOrNull()

    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val motePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.9f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val matrix = Matrix()
    private val tmpPath = Path()

    /** 底图铺满控件的比例，onSizeChanged 时算一次 */
    private var baseScale = 1f

    /** 网格：顶点原始坐标（位图坐标）、每帧结果（屏幕坐标）、每个顶点的形变权重 */
    private var baseVerts = FloatArray(0)
    private var verts = FloatArray(0)
    private var weights = FloatArray(0)

    // ---- 预建渐变（坐标按 onSizeChanged 的尺寸，动画只平移 canvas / 改 alpha）
    private var beamShader: Shader? = null
    private var vignetteShader: Shader? = null
    private var scrimShader: Shader? = null
    private var coolShader: Shader? = null
    private var warmShader: Shader? = null
    private var hairShader: Shader? = null

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

    // ------------------------------------------------------------------ 特效定义

    /**
     * 一团柔光。位置、半径都归一化（半径按控件宽度折算），
     * [alpha] 是峰值不透明度，[phase] 错开呼吸节奏，[drift] 是游走的幅度（0 = 不动）。
     */
    private class Glow(
        val x: Float,
        val y: Float,
        val r: Float,
        val alpha: Int,
        val phase: Float,
        val drift: Float
    )

    /** 环境光斑：三团大而极淡的柔光，在画面里缓慢游走，是「活起来」最省的一层 */
    private val spots = arrayOf(
        Glow(0.16f, 0.28f, 0.34f, 15, 0.0f, 0.55f),
        Glow(0.58f, 0.16f, 0.25f, 10, 2.1f, 0.40f),
        Glow(0.31f, 0.76f, 0.27f, 12, 4.0f, 0.35f)
    )

    /**
     * 头发柔光：沿着长发受光的一侧（左上来的窗光）铺五团小柔光，
     * 叠起来像一条贴着发丝走向的高光带——比原来的「白线」淡得多，
     * 但落在头发上，看起来是「光」，不是「贴上去的线」。
     */
    private val hairGlows = arrayOf(
        Glow(0.462f, 0.372f, 0.080f, 26, 0.0f, 0.28f),
        Glow(0.500f, 0.420f, 0.090f, 30, 0.9f, 0.34f),
        Glow(0.545f, 0.468f, 0.100f, 32, 1.8f, 0.42f),
        Glow(0.590f, 0.518f, 0.095f, 26, 2.7f, 0.46f),
        Glow(0.520f, 0.612f, 0.082f, 22, 3.6f, 0.34f)
    )

    /** 焦外光点：光束里几个虚焦的小圆，给画面一点纵深 */
    private val bokeh = arrayOf(
        Glow(0.200f, 0.520f, 0.030f, 26, 0.7f, 0.80f),
        Glow(0.330f, 0.200f, 0.022f, 20, 2.9f, 0.70f),
        Glow(0.450f, 0.700f, 0.026f, 17, 4.3f, 0.90f),
        Glow(0.620f, 0.340f, 0.020f, 13, 5.1f, 0.60f),
        Glow(0.100f, 0.760f, 0.018f, 11, 1.9f, 0.50f)
    )

    /** 星芒：极小的十字高光，亮度做三次方衰减，闪一下就灭，像空气里的亮尘 */
    private class Spark(val x: Float, val y: Float, val size: Float, val phase: Float)

    private val sparks = arrayOf(
        Spark(0.300f, 0.235f, 3.4f, 0.0f),
        Spark(0.385f, 0.545f, 2.6f, 1.7f),
        Spark(0.225f, 0.620f, 3.0f, 3.1f),
        Spark(0.470f, 0.180f, 2.2f, 4.6f)
    )

    private class Mote(
        var x: Float,
        var y: Float,
        var r: Float,
        var vy: Float,
        var vx: Float,
        var phase: Float,
        var warm: Boolean
    )

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
        if (!animationsOff && !looping) startLoop()
    }

    /** 头发柔光跟随连接状态变色 */
    fun setMood(newMood: Int) {
        if (mood == newMood) return
        mood = newMood
        hairShader = null
        ensureShaders(width.toFloat(), height.toFloat())
        invalidate()
    }

    // ------------------------------------------------------------------ 尺寸 / 网格

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val bmp = bitmap ?: return
        // 铺满控件（centerCrop）：留 7% 余量给推近与平移，避免边缘露白
        baseScale = max(w.toFloat() / bmp.width, h.toFloat() / bmp.height) * 1.07f
        buildMesh(bmp)
        // 这几个渐变按尺寸定位，尺寸一变（旋转 / 分屏）必须重建
        beamShader = null
        vignetteShader = null
        scrimShader = null
        ensureShaders(w.toFloat(), h.toFloat())
        if (motes.isEmpty()) spawnMotes(w.toFloat(), h.toFloat())
    }

    private fun buildMesh(bmp: Bitmap) {
        val n = (MESH_COLS + 1) * (MESH_ROWS + 1)
        if (baseVerts.size != n * 2) {
            baseVerts = FloatArray(n * 2)
            verts = FloatArray(n * 2)
            weights = FloatArray(n)
        }
        var k = 0
        for (j in 0..MESH_ROWS) {
            val v = j.toFloat() / MESH_ROWS
            for (i in 0..MESH_COLS) {
                val u = i.toFloat() / MESH_COLS
                baseVerts[k * 2] = u * bmp.width
                baseVerts[k * 2 + 1] = v * bmp.height
                weights[k] = HairField.weight(u, v)
                k++
            }
        }
    }

    /**
     * 所有渐变提前建好。除了按尺寸定位的那几个，柔光类都用「圆心在原点、半径 1」的写法，
     * 绘制时靠 `canvas.translate + scale` 摆到目标位置——这样一个 shader 就能给所有光点复用。
     */
    private fun ensureShaders(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        if (coolShader == null) coolShader = softShader(0xBFE3FF)
        if (warmShader == null) warmShader = softShader(0xFFE3C4)
        if (hairShader == null) hairShader = softShader(rimColor())

        if (beamShader == null) {
            // 顺着原图左上窗光的方向铺一条极淡的斜向亮带；亮度靠 Paint.alpha 调，不重建 shader
            beamShader = LinearGradient(
                w * 0.08f, 0f, w * 0.66f, h,
                intArrayOf(Color.TRANSPARENT, 0xFFBBE0FF.toInt(), 0x70BBE0FF, Color.TRANSPARENT),
                floatArrayOf(0f, 0.38f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        if (vignetteShader == null) {
            vignetteShader = RadialGradient(
                w * 0.5f, h * 0.46f, max(w, h) * 0.78f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0x7A000000),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        if (scrimShader == null) {
            // 底部压到页面背景色，封面与下方内容之间不会出现一条硬边
            val bottom = context.getColor(R.color.bg_grad_top)
            scrimShader = LinearGradient(
                0f, h * 0.42f, 0f, h,
                intArrayOf(Color.TRANSPARENT, (0xB3 shl 24) or (bottom and 0xFFFFFF), bottom),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    /** 圆心 (0,0)、半径 1 的柔光：中心实、边缘全透明，避免出现圆的硬边 */
    private fun softShader(color: Int): Shader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(
            (0xFF shl 24) or (color and 0xFFFFFF),
            (0x66 shl 24) or (color and 0xFFFFFF),
            (0x00 shl 24) or (color and 0xFFFFFF)
        ),
        floatArrayOf(0f, 0.42f, 1f),
        Shader.TileMode.CLAMP
    )

    private fun spawnMotes(w: Float, h: Float) {
        val random = Random(System.nanoTime())
        repeat(22) {
            motes += Mote(
                // 光束在原图左上到中部，浮尘集中在这一带才自然
                x = w * (0.04f + random.nextFloat() * 0.55f),
                y = h * (0.05f + random.nextFloat() * 0.9f),
                r = (0.6f + random.nextFloat() * 1.5f) * density,
                vy = 0.008f + random.nextFloat() * 0.020f,
                vx = (random.nextFloat() - 0.5f) * 0.006f,
                phase = random.nextFloat() * 6.28f,
                warm = random.nextFloat() < 0.28f
            )
        }
    }

    // ------------------------------------------------------------------ 动画推进

    private fun advance(dt: Float) {
        time += dt
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        for (m in motes) {
            m.y -= m.vy * dt * h
            m.x += (m.vx + sin(time * 0.5f + m.phase) * 0.004f) * dt * w
            m.phase += dt * 0.9f
            if (m.y < -m.r * 4f) {
                m.y = h + m.r * 4f
                m.x = w * (0.04f + Random.nextFloat() * 0.55f)
            }
            if (m.x < -m.r * 4f) m.x = w + m.r * 4f
            if (m.x > w + m.r * 4f) m.x = -m.r * 4f
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
        render(canvas, if (animationsOff) 0f else time)
    }

    /** 抽出成独立函数，测试里可以用固定时间点渲染，方便逐帧比对 */
    internal fun render(canvas: Canvas, t: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bmp = bitmap ?: return
        if (w <= 0f || h <= 0f || baseVerts.isEmpty()) return

        drawPhoto(canvas, bmp, w, h, t)
        drawHairGlow(canvas, w, h, t)
        drawBeam(canvas, w, h, t)
        drawSpots(canvas, w, h, t)
        drawBokeh(canvas, w, h, t)
        drawMotes(canvas, t)
        drawSparks(canvas, w, h, t)
        drawHearts(canvas)
        drawFinish(canvas, w, h, t)
    }

    /**
     * 底图：慢速 Ken Burns（推近 + 横移）+ 呼吸，再把长发那一块按 [HairField] 的位移场弯折。
     *
     * 摆动位移直接加在「已经过矩阵映射的屏幕坐标」上，所以摆幅和控件尺寸无关，
     * 手机横竖屏、不同分辨率下看到的头发摆幅是一致的。
     */
    private fun drawPhoto(canvas: Canvas, bmp: Bitmap, w: Float, h: Float, t: Float) {
        // 整体推移幅度比上一版再收一点，让「动」主要来自头发而不是整张图
        val zoom = 1f + 0.014f * sin(t * 0.21f)
        val breath = 1f + 0.004f * sin(t * 1.15f)
        val scale = baseScale * zoom * breath
        val panX = w * 0.006f * sin(t * 0.13f)
        val panY = h * 0.004f * cos(t * 0.17f)

        val dx = (w - bmp.width * scale) / 2f + panX
        val dy = (h - bmp.height * scale) / 2f + panY
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)

        if (animationsOff) {
            canvas.drawBitmap(bmp, matrix, photoPaint)
            return
        }

        matrix.mapPoints(verts, baseVerts)
        val amp = SWAY_DP * density
        var k = 0
        for (j in 0..MESH_ROWS) {
            val v = j.toFloat() / MESH_ROWS
            for (i in 0..MESH_COLS) {
                val wgt = weights[k]
                if (wgt > 0.004f) {
                    val u = i.toFloat() / MESH_COLS
                    val sway = HairField.sway(u, v, t) * amp * wgt
                    // 头发是向下偏右垂着的，位移以竖直为主、水平只占三成，看起来才像被风撩动
                    verts[k * 2] += sway * 0.32f
                    verts[k * 2 + 1] += sway
                }
                k++
            }
        }
        canvas.drawBitmapMesh(bmp, MESH_COLS, MESH_ROWS, verts, 0, null, 0, photoPaint)
    }

    /** 头发柔光：四团极淡的光落在长发受光的一侧，缓慢呼吸、微微游走 */
    private fun drawHairGlow(canvas: Canvas, w: Float, h: Float, t: Float) {
        val shader = hairShader ?: return
        // 庆祝时整体提亮一点，配合爱心
        val boost = 1f + 0.8f * (celebrateLeft / 2.6f).coerceIn(0f, 1f)
        for (g in hairGlows) drawGlow(canvas, w, h, g, shader, t, boost)
    }

    /** 斜射光束 + 庆祝暖光 */
    private fun drawBeam(canvas: Canvas, w: Float, h: Float, t: Float) {
        val shader = beamShader ?: return
        val shimmer = 0.5f + 0.5f * sin(t * 0.42f)
        beamPaint.shader = shader
        beamPaint.alpha = (9 + 8 * shimmer).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.translate(w * 0.012f * sin(t * 0.19f), h * 0.02f * sin(t * 0.11f))
        canvas.drawRect(-w * 0.1f, -h * 0.1f, w * 1.1f, h * 1.1f, beamPaint)
        canvas.restore()

        if (celebrateLeft > 0f) {
            val k = (celebrateLeft / 2.6f).coerceIn(0f, 1f)
            glowPaint.shader = warmShader
            glowPaint.alpha = (74 * k).toInt().coerceIn(0, 255)
            val r = w * 0.55f
            canvas.save()
            canvas.translate(w * 0.5f, h * 0.72f)
            canvas.scale(r, r)
            canvas.drawRect(-1f, -1f, 1f, 1f, glowPaint)
            canvas.restore()
        }
    }

    private fun drawSpots(canvas: Canvas, w: Float, h: Float, t: Float) {
        val cool = coolShader ?: return
        val warm = warmShader ?: return
        spots.forEachIndexed { index, g ->
            drawGlow(canvas, w, h, g, if (index == 1) warm else cool, t)
        }
    }

    private fun drawBokeh(canvas: Canvas, w: Float, h: Float, t: Float) {
        val cool = coolShader ?: return
        bokeh.forEachIndexed { index, g ->
            drawGlow(canvas, w, h, g, if (index % 2 == 1) warmShader ?: cool else cool, t)
        }
    }

    /**
     * 画一团柔光。用「平移 + 缩放 + 画单位圆」而不是 `drawCircle(x, y, r)` 配新 shader，
     * 是为了让所有光点共用同一个 shader 对象，每帧零分配。
     */
    private fun drawGlow(
        canvas: Canvas,
        w: Float,
        h: Float,
        g: Glow,
        shader: Shader,
        t: Float,
        boost: Float = 1f
    ) {
        val pulse = 0.58f + 0.42f * ((sin(t * 0.52f + g.phase) + 1f) / 2f)
        val alpha = (g.alpha * pulse * boost).toInt()
        if (alpha <= 0) return
        val r = g.r * w * (0.94f + 0.09f * sin(t * 0.31f + g.phase))
        val ox = w * 0.014f * g.drift * sin(t * 0.27f + g.phase)
        val oy = h * 0.020f * g.drift * cos(t * 0.21f + g.phase * 1.3f)

        glowPaint.shader = shader
        glowPaint.alpha = alpha.coerceIn(0, 255)
        canvas.save()
        canvas.translate(g.x * w + ox, g.y * h + oy)
        canvas.scale(r, r)
        // 画单位方框而不是单位圆：渐变在半径 1 之外本来就是全透明，方框只需两个三角形
        canvas.drawRect(-1f, -1f, 1f, 1f, glowPaint)
        canvas.restore()
    }

    private fun drawMotes(canvas: Canvas, t: Float) {
        for (m in motes) {
            val twinkle = (sin(t * 1.1f + m.phase) + 1f) / 2f
            val alpha = (22 + 78 * twinkle).toInt().coerceIn(0, 255)
            motePaint.color = if (m.warm) {
                (alpha shl 24) or 0xFFE9CE
            } else {
                (alpha shl 24) or 0xDCE9FF
            }
            canvas.drawCircle(m.x, m.y, m.r, motePaint)
        }
    }

    private fun drawSparks(canvas: Canvas, w: Float, h: Float, t: Float) {
        for (s in sparks) {
            val twinkle = (sin(t * 1.55f + s.phase) + 1f) / 2f
            // 三次方让「闪」变得短促：大部分时间很淡，偶尔一下亮起来
            val k = twinkle * twinkle * twinkle
            val alpha = (110 * k).toInt()
            if (alpha < 8) continue
            val cx = s.x * w
            val cy = s.y * h
            val l = s.size * density * (0.5f + k)
            sparkPaint.color = (alpha shl 24) or 0xF2F7FF
            canvas.drawLine(cx - l, cy, cx + l, cy, sparkPaint)
            canvas.drawLine(cx, cy - l * 0.72f, cx, cy + l * 0.72f, sparkPaint)
            motePaint.color = ((alpha / 2) shl 24) or 0xFFFFFF
            canvas.drawCircle(cx, cy, l * 0.28f, motePaint)
        }
    }

    private fun drawHearts(canvas: Canvas) {
        if (hearts.isEmpty()) return
        for (heart in hearts) {
            val alpha = (170 * heart.life.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
            heartPaint.color = (alpha shl 24) or 0xFF9DB4
            drawHeart(canvas, heart.x, heart.y, heart.size)
        }
    }

    /** 底部压暗 + 四角暗角（暗角缓慢呼吸，画面不会「死平」） */
    private fun drawFinish(canvas: Canvas, w: Float, h: Float, t: Float) {
        val vg = vignetteShader
        if (vg != null) {
            vignettePaint.shader = vg
            vignettePaint.alpha = (206 + 34 * sin(t * 0.23f)).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, vignettePaint)
        }
        val sc = scrimShader
        if (sc != null) {
            scrimPaint.shader = sc
            canvas.drawRect(0f, 0f, w, h, scrimPaint)
        }
    }

    /** 边缘光颜色：跟随连接状态，但都压得比较淡，避免抢画面 */
    private fun rimColor(): Int = when (mood) {
        MOOD_CONNECTED -> 0x9FF1D8 // 青绿
        MOOD_TRANSFER -> 0xD9C4FF // 淡紫
        MOOD_ERROR -> 0xFFC0B4 // 暖红
        else -> 0xC9DAFF          // 冷蓝
    }

    /** 用两个圆 + 一个三角拼一颗心，比 Path 里手写贝塞尔省事也够可爱 */
    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val r = size * 0.5f
        canvas.drawCircle(cx - r * 0.55f, cy - r * 0.25f, r * 0.62f, heartPaint)
        canvas.drawCircle(cx + r * 0.55f, cy - r * 0.25f, r * 0.62f, heartPaint)
        tmpPath.reset()
        tmpPath.moveTo(cx - r * 1.05f, cy)
        tmpPath.lineTo(cx + r * 1.05f, cy)
        tmpPath.lineTo(cx, cy + r * 1.45f)
        tmpPath.close()
        canvas.drawPath(tmpPath, heartPaint)
    }
}
