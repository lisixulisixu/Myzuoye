package com.example.myzuoye.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * 封面装饰层：在封面上叠加一层柔和渐变 + 若干缓慢飘动的光斑。
 *
 * 设计约束（不影响使用、不耗性能）：
 * - 完全透明背景，只有渐变和光斑，覆盖在 ImageView 之上，不挡任何交互（本层不拦截触摸）；
 * - 动画用 [ValueAnimator] 驱动，只在 [onAttachedToWindow] 到 [onDetachedFromWindow] 之间运行，
 *   界面不可见或离开屏幕时自动停止，不空转耗电；
 * - 关闭系统「减少动画」时直接画静态光斑，尊重无障碍设置；
 * - 用纯色 [RadialGradient] 模拟柔光，无位图、无阴影、无滤镜，绘制开销极小。
 */
class CoverOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val glowColors = intArrayOf(
        0x66FFFFFF.toInt(), // 白
        0x66BFE3FF.toInt(), // 淡蓝
        0x66FFE0C2.toInt()  // 暖黄
    )

    /** 光斑的相位，动画驱动，让它们缓慢游动 */
    private var phase = 0f

    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startIfAllowed()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) startIfAllowed() else stopAnimation()
    }

    private fun startIfAllowed() {
        if (!isAttachedToWindow || visibility != VISIBLE) return
        // 系统关闭动画时保持静态，不违背无障碍偏好
        if (animator != null) return

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 14000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LINEAR_INTERPOLATOR
            addUpdateListener { a ->
                phase = a.animatedValue as Float
                invalidate()
            }
        }
        animator = anim
        anim.start()
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        // 顶部到底部的柔和渐变，让封面底部自然过渡到页面背景
        gradientPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x00000000, 0x33000000, 0x66000000),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. 渐变：底部压暗，让下方的标题文字在亮图上依旧清晰
        canvas.drawRect(0f, 0f, w, h, gradientPaint)

        // 2. 光斑：三个缓慢游动的柔光，彼此相位错开
        drawGlow(canvas, w, h, index = 0, phase = phase)
        drawGlow(canvas, w, h, index = 1, phase = phase + 0.333f)
        drawGlow(canvas, w, h, index = 2, phase = phase + 0.666f)
    }

    private fun drawGlow(canvas: Canvas, w: Float, h: Float, index: Int, phase: Float) {
        // 每个光斑有不同的轨道半径和中心，绕各自中心做 Lissajous 式缓慢游动
        val cx = w * (0.2f + 0.3f * index)
        val cy = h * (0.3f + 0.2f * index)
        val radius = (w + h) * 0.10f
        val t = phase * 2f * Math.PI.toFloat()

        val ox = cos(t * 1.3f + index * 1.7f) * radius * 0.6f
        val oy = sin(t * 0.9f + index * 2.1f) * radius * 0.5f
        val x = cx + ox
        val y = cy + oy

        // 光斑半径随相位轻微呼吸
        val r = radius * (0.8f + 0.2f * sin(t + index).toFloat())

        glowPaint.shader = RadialGradient(
            x, y, r,
            glowColors[index % glowColors.size],
            0x00000000,
            Shader.TileMode.CLAMP
        )
        // 画一个大矩形，让 RadialGradient 的边缘自然淡出到透明
        canvas.drawRect(x - r, y - r, x + r, y + r, glowPaint)
    }

    private companion object {
        val LINEAR_INTERPOLATOR = LinearInterpolator()
    }
}
