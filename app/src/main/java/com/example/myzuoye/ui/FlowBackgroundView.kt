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
 * - 只在自己可见时跑（onAttached/onDetached 控制），不可见时零开销；
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

    private val particles = mutableListOf<Particle>()

    private data class Particle(
        var x: Float,
        var y: Float,
        var radius: Float,
        var speed: Float,
        var phase: Float,
        var tint: Int
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
        // 约 20 秒走完一个周期：足够慢，不会让人分心
        phase = (phase + dt * 0.05f) % 1f

        val w = width.toFloat()
        val h = height.toFloat()
        for (p in particles) {
            p.y -= p.speed * dt * h
            p.phase = (p.phase + dt * 0.35f) % 1f
            if (p.y < -p.radius * 2f) {
                p.y = h + p.radius * 2f
                p.x = Random.nextFloat() * w
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
        drawParticles(canvas)

        canvas.drawRect(0f, 0f, w, h, vignettePaint)
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
    }
}
