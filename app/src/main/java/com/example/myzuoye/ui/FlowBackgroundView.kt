package com.example.myzuoye.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.provider.Settings
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.example.myzuoye.R
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 深色流动背景。
 *
 * 四层叠加，从下往上：
 * 1. 多段线性渐变打底（墨蓝 → 靛蓝 → 深紫 → 深青），保证整体是「有颜色的深色」而不是纯黑；
 * 2. 四团大面积柔光（蓝 / 紫 / 青 / 粉）缓慢漂移，并且两套配色互相交叉淡入淡出，
 *    看到的是「色相在流动」，不是一块光斑在平移；
 * 3. 一层很淡的斜向光带，像极光一样极慢地转；
 * 4. 几十颗微小光点缓慢上浮 + 明暗呼吸，最后压一层暗角把视线收拢到中间。
 *
 * 性能与安全约束（都是踩过坑定下来的）：
 * - 不重写 onTouchEvent，纯装饰，绝不拦截任何点击；
 * - Shader 只在尺寸变化时创建一次，每帧只做「平移画布 + 改 alpha」，
 *   否则每帧 new 四个 RadialGradient，低端机直接掉帧；
 * - 只在自己可见且窗口可见时跑（attach + visibility 双重判断），退到后台立刻停，零开销；
 * - 系统关掉动画（开发者选项「动画时长缩放 = 关闭」）时画静态一帧。
 */
class FlowBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private var phase = 0f
    private var looping = false
    private var lastFrameNanos = 0L

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

    // 两套配色，交叉淡入淡出用
    private val glowColorsA = intArrayOf(
        context.getColor(R.color.flow_glow_1),
        context.getColor(R.color.flow_glow_2),
        context.getColor(R.color.flow_glow_3),
        context.getColor(R.color.flow_glow_4)
    )

    private val glowColorsB = intArrayOf(
        context.getColor(R.color.flow_glow_3),
        context.getColor(R.color.flow_glow_4),
        context.getColor(R.color.flow_glow_1),
        context.getColor(R.color.flow_glow_2)
    )

    /** 每团柔光两套 shader，都固定在原点，靠 canvas.translate 定位 */
    private val glowShadersA = arrayOfNulls<Shader>(glowColorsA.size)
    private val glowShadersB = arrayOfNulls<Shader>(glowColorsB.size)

    private var glowRadius = 0f

    /**
     * 状态色调：连接状态变化时，整块背景会被染上一层很淡的状态色
     * （未连接冷蓝 / 已连接青绿 / 传输中紫 / 失败红）。
     * 用户扫一眼背景就知道现在是什么状态，比读一行 11sp 的小字快得多。
     */
    private var accentColor = 0
    private var accentShader: Shader? = null
    private var accentAlpha = 0f
    private var accentAnimator: android.animation.ValueAnimator? = null

    /**
     * 背景当前表现的状态。
     *
     * 之前粒子永远匀速上浮，只有光晕颜色跟着状态变——前景在演「搜索中 / 传输中」，
     * 背景却始终是同一副表情。这里让粒子的运动方式也跟状态走，
     * 整块背景就从「装饰」变成了「状态的放大器」。
     */
    enum class Field {
        /** 待机：缓慢上浮 */
        IDLE,

        /** 搜索附近设备：粒子从中心向外扩散，像一圈圈雷达波推出去了 */
        SEARCHING,

        /** 传输数据：粒子改为单向水平流动，速度也更快 */
        TRANSFERRING
    }

    private var field = Field.IDLE

    /** 传输进度 0..1，用来让状态光晕随进度变亮；非传输状态恒为 1 */
    private var intensity = 1f

    /** 完成瞬间的环形冲击波；< 0 表示当前没有正在播放的波 */
    private var rippleT = -1f
    private var rippleColor = 0xFFA855F7.toInt()

    private val particles = mutableListOf<Particle>()

    private data class Particle(
        var x: Float,
        var y: Float,
        var radius: Float,
        var speed: Float,
        var phase: Float,
        var tint: Int,
        /** 当前运动方向（单位向量），状态切换时插值转向，避免粒子「跳」一下 */
        var dx: Float = 0f,
        var dy: Float = -1f
    )

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!looping) return
            // 卡顿时按 1/30 秒封顶，避免一次跳很远
            val dt = ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 1f / 30f)
            lastFrameNanos = frameTimeNanos
            advance(dt)
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animationsOff) startLoop() else invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopLoop()
    }

    /**
     * 退到后台 / 被其它界面盖住时停掉帧循环。
     *
     * 只有 attach/detach 是不够的：应用切到后台时 View 仍然 attached，
     * Choreographer 会继续每帧推进相位并 invalidate 一整屏渐变，纯耗电。
     */
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

    private fun advance(dt: Float) {
        // 约 20 秒走完一个周期：足够慢，不会让人分心。
        // 搜索与传输时整体节奏加快一点，让「真的在发生事情」有存在感
        val rhythm = when (field) {
            Field.IDLE -> 1f
            Field.SEARCHING -> 1.35f
            Field.TRANSFERRING -> 1.7f
        }
        phase = (phase + dt * 0.05f * rhythm) % 1f

        if (rippleT >= 0f) {
            rippleT += dt / RIPPLE_SECONDS
            if (rippleT > 1f) rippleT = -1f
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.5f
        val cy = h * 0.42f
        val maxR = hypot(w, h) * 0.62f

        for (p in particles) {
            // 目标方向：待机朝上、搜索朝外、传输朝右
            val (targetX, targetY) = FieldMotion.direction(field, p.x, p.y, cx, cy)

            // 约 0.25 秒完成转向：状态切换时粒子是「拐过去」而不是瞬移
            val turn = (dt * 4f).coerceIn(0f, 1f)
            p.dx += (targetX - p.dx) * turn
            p.dy += (targetY - p.dy) * turn

            val step = p.speed * dt * rhythm
            p.x += p.dx * step * w
            p.y += p.dy * step * h
            p.phase = (p.phase + dt * 0.35f * rhythm) % 1f

            when (field) {
                // 冲出视野就从中心附近重新出发，形成持续的向外扩散
                Field.SEARCHING -> if (hypot(p.x - cx, p.y - cy) > maxR) {
                    p.x = cx + (Random.nextFloat() - 0.5f) * w * 0.12f
                    p.y = cy + (Random.nextFloat() - 0.5f) * h * 0.10f
                }

                // 从右侧出场后回到左侧，形成一条不断流过的数据流
                Field.TRANSFERRING -> if (p.x > w + p.radius * 2f) {
                    p.x = -p.radius * 2f
                    p.y = Random.nextFloat() * h
                }

                Field.IDLE -> {
                    if (p.y < -p.radius * 2f) {
                        p.y = h + p.radius * 2f
                        p.x = Random.nextFloat() * w
                    }
                    if (p.x < -p.radius * 2f) p.x = w + p.radius * 2f
                    if (p.x > w + p.radius * 2f) p.x = -p.radius * 2f
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        buildShaders(w.toFloat(), h.toFloat())
        if (particles.isEmpty()) spawnParticles(w.toFloat(), h.toFloat())
    }

    private fun buildShaders(w: Float, h: Float) {
        // 1) 打底渐变：对角方向，四段色，末段偏青，制造冷调纵深
        basePaint.shader = LinearGradient(
            0f, 0f, w * 0.35f, h,
            intArrayOf(
                context.getColor(R.color.flow_bg_1),
                context.getColor(R.color.flow_bg_2),
                context.getColor(R.color.flow_bg_3),
                context.getColor(R.color.flow_bg_4)
            ),
            floatArrayOf(0f, 0.45f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )

        // 2) 柔光：半径固定在这里，之后每帧只平移画布，绝不重建 shader
        glowRadius = (w + h) * 0.30f
        for (i in glowShadersA.indices) {
            glowShadersA[i] = RadialGradient(
                0f, 0f, glowRadius,
                intArrayOf(glowColorsA[i], 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            glowShadersB[i] = RadialGradient(
                0f, 0f, glowRadius,
                intArrayOf(glowColorsB[i], 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        // 3) 斜向光带：很淡的一条，缓慢旋转
        bandPaint.shader = LinearGradient(
            -w, 0f, w * 1.6f, h,
            intArrayOf(0x00000000, 0x1A7DD3FC, 0x00000000),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        // 3.5) 状态色：一层很大的柔光，颜色由连接状态决定
        if (accentColor != 0) {
            accentShader = RadialGradient(
                0f, 0f, (w + h) * 0.55f,
                intArrayOf((0x66 shl 24) or (accentColor and 0xFFFFFF), 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        // 4) 暗角：中心透明、四周压暗，把视线收到中间
        vignettePaint.shader = RadialGradient(
            w / 2f, h * 0.45f, maxOf(w, h) * 0.78f,
            intArrayOf(0x00000000, 0x00000000, 0x66000000),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun spawnParticles(w: Float, h: Float) {
        val random = Random(System.nanoTime())
        repeat(PARTICLE_COUNT) {
            particles += Particle(
                x = random.nextFloat() * w,
                y = random.nextFloat() * h,
                radius = w * (0.0015f + random.nextFloat() * 0.0035f),
                speed = 0.012f + random.nextFloat() * 0.03f,
                phase = random.nextFloat(),
                tint = random.nextInt(3)
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, basePaint)

        drawAuroraBand(canvas, w, h)
        drawGlows(canvas, w, h)
        drawAccent(canvas, w, h)
        drawParticles(canvas)
        drawRipple(canvas, w, h)

        canvas.drawRect(0f, 0f, w, h, vignettePaint)
    }

    /**
     * 状态色柔光：位置固定在画面上半部，透明度随状态切换平滑过渡（约 700ms）。
     * 没有状态色时直接跳过，不产生任何绘制开销。
     */
    private fun drawAccent(canvas: Canvas, w: Float, h: Float) {
        val shader = accentShader ?: return
        if (accentAlpha <= 0.01f) return
        canvas.save()
        canvas.translate(w * 0.5f, h * 0.32f)
        glowPaint.shader = shader
        // 传输中让光晕随进度变亮：越接近完成，背景越「热」
        glowPaint.alpha = (255 * accentAlpha * (0.75f + 0.45f * intensity))
            .toInt()
            .coerceIn(0, 255)
        canvas.drawCircle(0f, 0f, (w + h) * 0.55f, glowPaint)
        canvas.restore()
        glowPaint.alpha = 255
    }

    /**
     * 完成瞬间的环形冲击波：几个同心圆由内向外扩散并淡出。
     *
     * 只画描边不碰填充，所以不会盖住任何文字——它是「一波能量推出去」的感觉，
     * 和进度条闪光、触感一起构成完成时刻的节拍。
     */
    private fun drawRipple(canvas: Canvas, w: Float, h: Float) {
        if (rippleT < 0f) return
        val cx = w * 0.5f
        val cy = h * 0.42f
        val maxR = hypot(w, h) * 0.62f
        for (i in 0 until RIPPLE_RINGS) {
            // 每圈错开一点起跑时间，看起来是连续的波纹而不是一个粗环
            val t = rippleT - i * 0.14f
            if (t <= 0f || t >= 1f) continue
            val alpha = ((1f - t) * 0.55f * 255).toInt().coerceIn(0, 255)
            ripplePaint.color = (alpha shl 24) or (rippleColor and 0xFFFFFF)
            ripplePaint.strokeWidth = 1.5f + 5f * (1f - t)
            canvas.drawCircle(cx, cy, t * maxR, ripplePaint)
        }
    }

    /** 切换背景状态：粒子运动方式、整体节奏都跟着变 */
    fun setField(field: Field) {
        if (this.field == field) return
        this.field = field
        invalidate()
    }

    /**
     * 传输进度 0..1。
     *
     * 由调用方按百分比节流后传入（不要每帧调），这里只影响一层光晕的透明度，
     * 开销就是一次 invalidate。
     */
    fun setTransferIntensity(value: Float) {
        val v = value.coerceIn(0f, 1f)
        if (kotlin.math.abs(v - intensity) < 0.02f) return
        intensity = v
        invalidate()
    }

    /**
     * 播一次冲击波（传输完成、连接成功这类「时刻」）。
     *
     * 系统关闭动画时直接返回：画一帧静止的圆环只会让人以为是渲染错误。
     */
    fun burst(color: Int = 0xFFA855F7.toInt()) {
        if (animationsOff) return
        rippleColor = color
        rippleT = 0f
        if (!looping) startLoop()
        invalidate()
    }

    /**
     * 设置状态色调。
     *
     * @param color 目标颜色（ARGB，取 RGB 部分）；0 表示取消状态色
     * @param strength 最高不透明度，默认 0.30，太高会盖住列表文字
     */
    fun setAccent(color: Int, strength: Float = 0.30f) {
        if (accentColor == color) return
        accentColor = color
        accentShader = if (color != 0 && glowRadius > 0f) {
            RadialGradient(
                0f, 0f, glowRadius * 1.85f,
                intArrayOf((0x66 shl 24) or (color and 0xFFFFFF), 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            null
        }

        val target = if (color == 0) 0f else strength.coerceIn(0f, 1f)
        accentAnimator?.cancel()
        accentAnimator = android.animation.ValueAnimator.ofFloat(accentAlpha, target).apply {
            duration = 700L
            addUpdateListener {
                accentAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 四团柔光：位置随相位漂移，同时在两套配色之间交叉淡入淡出 */
    private fun drawGlows(canvas: Canvas, w: Float, h: Float) {
        val t = phase * 2f * Math.PI.toFloat()
        for (i in glowShadersA.indices) {
            val baseX = when (i) {
                0 -> 0.22f
                1 -> 0.78f
                2 -> 0.45f
                else -> 0.85f
            }
            val baseY = when (i) {
                0 -> 0.28f
                1 -> 0.20f
                2 -> 0.78f
                else -> 0.68f
            }
            val dir = if (i % 2 == 0) 1f else -1f
            val x = w * baseX + cos(t + i * 1.7f) * w * 0.26f * dir
            val y = h * baseY + sin(t * 1.15f + i * 2.3f) * h * 0.20f

            // 交叉淡入：i 号光在 A/B 两套配色间来回，相位错开，避免整体同步呼吸
            val blend = (sin(t + i * 1.3f) + 1f) / 2f

            canvas.save()
            canvas.translate(x, y)

            glowPaint.shader = glowShadersA[i]
            glowPaint.alpha = (255 * (1f - blend) * GLOW_ALPHA).toInt().coerceIn(0, 255)
            canvas.drawCircle(0f, 0f, glowRadius, glowPaint)

            glowPaint.shader = glowShadersB[i]
            glowPaint.alpha = (255 * blend * GLOW_ALPHA).toInt().coerceIn(0, 255)
            canvas.drawCircle(0f, 0f, glowRadius, glowPaint)

            canvas.restore()
        }
        glowPaint.alpha = 255
    }

    private fun drawAuroraBand(canvas: Canvas, w: Float, h: Float) {
        canvas.save()
        canvas.rotate(phase * 360f * 0.12f - 8f, w / 2f, h / 2f)
        bandPaint.alpha = 90
        canvas.drawRect(-w, h * 0.18f, w * 2f, h * 0.5f, bandPaint)
        canvas.restore()
        bandPaint.alpha = 255
    }

    private fun drawParticles(canvas: Canvas) {
        for (p in particles) {
            // 明暗呼吸：用 sin 让每颗的闪烁节奏错开
            val twinkle = (sin(p.phase * 2f * Math.PI.toFloat()) + 1f) / 2f
            val alpha = (40 + 150 * twinkle).toInt().coerceIn(0, 255)
            particlePaint.color = when (p.tint) {
                0 -> (alpha shl 24) or 0xBFDBFE
                1 -> (alpha shl 24) or 0xC4B5FD
                else -> (alpha shl 24) or 0x99F6E4
            }
            canvas.drawCircle(p.x, p.y, p.radius, particlePaint)
        }
    }

    private companion object {
        /** 光团整体不透明度：太高会盖住上面的文字 */
        const val GLOW_ALPHA = 0.85f

        /** 光点数量：再多就是无谓的绘制开销 */
        const val PARTICLE_COUNT = 34

        /** 一次冲击波的总时长（秒） */
        const val RIPPLE_SECONDS = 1.4f

        /** 冲击波的圈数 */
        const val RIPPLE_RINGS = 3
    }
}

/**
 * 状态场的方向规则。
 *
 * 单独抽出来是为了能单测：粒子往哪走是这套效果的核心逻辑，
 * 而 advance() 本身依赖 View 尺寸与绘制，在 JVM 单测里跑不起来。
 */
internal object FieldMotion {

    /** 给定状态下，位于 (x, y) 的粒子该往哪个方向走；返回单位向量 */
    fun direction(
        field: FlowBackgroundView.Field,
        x: Float,
        y: Float,
        cx: Float,
        cy: Float
    ): Pair<Float, Float> = when (field) {
        FlowBackgroundView.Field.IDLE -> 0f to -1f
        FlowBackgroundView.Field.TRANSFERRING -> 1f to 0f
        FlowBackgroundView.Field.SEARCHING -> {
            val dx = x - cx
            val dy = y - cy
            val len = hypot(dx, dy)
            // 正好落在中心时没有方向可言，给一个默认值而不是除零
            if (len < 0.001f) 1f to 0f else dx / len to dy / len
        }
    }
}
