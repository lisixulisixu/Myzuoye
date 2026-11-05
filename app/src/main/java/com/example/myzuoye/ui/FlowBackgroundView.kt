package com.example.myzuoye.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * 流动背景：竖向柔和渐变打底，叠加若干缓慢漂移的柔光波纹。
 *
 * 设计约束（不影响使用、不耗性能）：
 * - 作为布局最底层的纯装饰，不拦截任何触摸；
 * - 用 [Choreographer] 帧同步驱动，仅在 [onDetachedFromWindow] 时停止，不空转耗电；
 * - 纯色渐变 + 柔光，无位图、无阴影、无滤镜，绘制开销极小；
 * - 尊重系统「减少动画」：动画关闭时画静态背景。
 */
class FlowBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var phase = 0f
    private var looping = false
    private var lastFrameNanos = 0L

    /** 光斑颜色（柔和、低饱和，与浅色渐变底协调） */
    private val glowColors = intArrayOf(
        0x4D9BC4E8.toInt(), // 淡蓝
        0x4DF0C4A8.toInt(), // 淡橙
        0x4DC4A8E8.toInt()  // 淡紫
    )

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!looping) return
            val dt = ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 1f / 30f)
            lastFrameNanos = frameTimeNanos
            phase = (phase + dt * 0.08f) % 1f   // 很慢地推进，约 12 秒一个周期
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startLoop()
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        // 竖向柔和渐变打底
        bgPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                Color.parseColor("#EAF3FC"),
                Color.parseColor("#F2EFFA"),
                Color.parseColor("#FBF3EC")
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 三个缓慢漂移的柔光，覆盖整屏制造"流动"感
        drawGlow(canvas, w, h, 0, phase)
        drawGlow(canvas, w, h, 1, phase + 0.333f)
        drawGlow(canvas, w, h, 2, phase + 0.666f)
    }

    private fun drawGlow(canvas: Canvas, w: Float, h: Float, index: Int, p: Float) {
        val t = p * 2f * Math.PI.toFloat()
        // 各光斑沿不同方向缓慢漂移
        val baseX = w * (0.25f + 0.5f * index % 1f)
        val baseY = h * (0.3f + 0.25f * (index % 2))
        val radius = (w + h) * 0.22f

        val ox = cos(t + index * 2.1f) * w * 0.28f
        val oy = sin(t * 1.3f + index * 1.5f) * h * 0.22f
        val x = baseX + ox
        val y = baseY + oy
        val r = radius * (0.85f + 0.15f * sin(t + index).toFloat())

        glowPaint.shader = RadialGradient(
            x, y, r,
            glowColors[index % glowColors.size],
            0x00000000,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(x - r, y - r, x + r, y + r, glowPaint)
    }
}
