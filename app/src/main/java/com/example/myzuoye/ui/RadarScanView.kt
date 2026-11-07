package com.example.myzuoye.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.content.ContextCompat
import com.example.myzuoye.R
import kotlin.math.min
import kotlin.math.sin

/**
 * 「正在搜索附近设备」时的雷达扫描动效。
 *
 * 原来空态只有一张静态图标，搜索时屏幕一片安静，用户不知道程序到底在不在干活。
 * 这里画三圈同心环 + 一圈旋转的扫掠光 + 中心呼吸点：
 * - 扫掠扇区 1.8 秒转一圈（比常见雷达慢，避免抢眼）；
 * - 中心点 2.4 秒呼吸一次，和外面「搜索中」的文案节奏对上；
 * - [setScanning] 关闭时只留静态同心环，不空转耗电。
 *
 * 纯装饰：不处理触摸（`isClickable=false`），也不拦截父容器的点击。
 */
class RadarScanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val accent = ContextCompat.getColor(context, R.color.primary_light)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = accent
    }

    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accent
    }

    /** 旋转角度（弧度制累计，避免 mod 时跳变） */
    private var sweepAngle = 0f

    /** 0..1 的呼吸相位 */
    private var pulse = 0f

    private var scanning = false
    private var looping = false
    private var lastFrameNanos = 0L

    /** 尺寸变化时重建渐变（渐变中心依赖画布中心） */
    private val sweepMatrix = Matrix()
    private var sweepShader: Shader? = null
    private var dotShader: Shader? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!looping) return
            val dt = if (lastFrameNanos == 0L) 0f
            else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastFrameNanos = frameTimeNanos

            sweepAngle = (sweepAngle + dt * SWEEP_SPEED) % (2f * Math.PI.toFloat())
            pulse = (pulse + dt * PULSE_SPEED) % 1f
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** 搜索开始/结束时调用；关闭后停在静态画面，不再请求下一帧 */
    fun setScanning(on: Boolean) {
        if (scanning == on) return
        scanning = on
        if (on && isShown && isAttachedToWindow) startLoop() else stopLoop()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (scanning) startLoop() else invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopLoop()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (scanning && visibility == VISIBLE && isAttachedToWindow) startLoop() else stopLoop()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (scanning && visibility == VISIBLE && isAttachedToWindow) startLoop() else stopLoop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f
        // 扫掠：从透明渐入到 accent，转到起点时自然收尾
        val transparent = accent and 0x00FFFFFF
        val hint = (accent and 0x00FFFFFF) or 0x40000000
        sweepShader = SweepGradient(
            cx, cy,
            intArrayOf(transparent, transparent, hint, accent),
            floatArrayOf(0f, 0.55f, 0.98f, 1f)
        )
        dotShader = RadialGradient(cx, cy, r * 0.22f, hint, transparent, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f

        // 同心环，越靠外越淡，营造纵深。尺寸小的时候只画两圈——
        // 46dp 里塞三圈环，线和线的间距不到 4dp，看着像一团毛刺
        val rings = if (maxR < 34f * resources.displayMetrics.density) 2 else 3
        for (i in 1..rings) {
            val ratio = i / rings.toFloat()
            val r = maxR * (0.30f + 0.52f * ratio)
            ringPaint.alpha = (54 + 40 * ratio).toInt()
            canvas.drawCircle(cx, cy, r, ringPaint)
        }

        if (scanning) {
            // 扫掠扇区：用旋转过的渐变画满圆，只在渐变的前 1/4 段有颜色
            sweepShader?.let { shader ->
                sweepMatrix.setRotate(
                    Math.toDegrees(sweepAngle.toDouble()).toFloat(),
                    cx, cy
                )
                shader.setLocalMatrix(sweepMatrix)
                sweepPaint.shader = shader
                canvas.drawCircle(cx, cy, maxR * 0.82f, sweepPaint)
                sweepPaint.shader = null
            }

            // 中心呼吸点：直径 0.05~0.13r 起伏，外圈光晕跟着明暗
            val breath = (sin(pulse * 2f * Math.PI.toFloat()) + 1f) / 2f
            dotPaint.alpha = (150 + 90 * breath).toInt()
            canvas.drawCircle(cx, cy, maxR * (0.045f + 0.02f * breath), dotPaint)

            dotShader?.let { shader ->
                dotPaint.alpha = (40 + 40 * breath).toInt()
                dotPaint.shader = shader
                canvas.drawCircle(cx, cy, maxR * 0.22f, dotPaint)
                dotPaint.shader = null
            }
        } else {
            // 静止态：只有中心一个静点，表示「待搜索」
            dotPaint.alpha = 120
            canvas.drawCircle(cx, cy, maxR * 0.05f, dotPaint)
        }
    }

    private fun startLoop() {
        if (looping) return
        looping = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        if (!looping) return
        looping = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private companion object {
        /** 弧度/秒：1.8 秒转一圈 */
        const val SWEEP_SPEED = 3.5f

        /** 呼吸周期约 2.4 秒 */
        const val PULSE_SPEED = 0.42f
    }
}
