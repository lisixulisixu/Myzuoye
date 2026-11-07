package com.example.myzuoye.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * 传输进度条：替代系统 ProgressBar。
 *
 * 系统的 clip drawable 是一条「安静的线」，进度一格一格往上跳。
 * 这个视图做了三件事：
 * - **平滑插值**：目标进度更新（80ms 节流的回调）不直接上屏，而是 380ms 内
 *   匀速追上去，视觉上从「一格一格」变成「匀速流动」；
 * - **流光**：传输进行中，一条高光沿已填充的部分来回扫，让「正在传」这件事
 *   不用读文字也能感知；
 * - **完成闪光**：传输成功时整条渐变亮起再回落（一次性，约 700ms），
 *   和封面的爱心迸发、触觉反馈同一时刻发生。
 *
 * 纯绘制，不拦截触摸；动画只用一个 ValueAnimator，onDetachedFromWindow 时取消。
 */
class TransferProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val rect = RectF()

    /** 界面上正在显示的进度（0..100），向 [targetProgress] 平滑追赶 */
    private var displayedProgress = 0f

    /** 上一次回调给进来的目标进度（0..100） */
    private var targetProgress = 0

    private var chaseAnimator: ValueAnimator? = null

    /** 是否处于传输中（true 时跑流光） */
    private var busy = false

    /** 流光相位 0..1，由独立的循环动画驱动（进度到位后也要继续扫） */
    private var shimmerPhase = 0f
    private var shimmerAnimator: ValueAnimator? = null

    /** 完成闪光的强度 0..1，0 表示没有 */
    private var flash = 0f
    private var flashAnimator: ValueAnimator? = null

    private var fillShader: Shader? = null
    private var shimmerShader: Shader? = null
    private var flashShader: Shader? = null

    /**
     * 流光的平移矩阵：亮带画在视图 50% 处，每帧用 [Matrix.setTranslate] 把它
     * 挪到当前扫动位置。渐变本身不重建，每帧零分配。
     */
    private val shimmerMatrix = Matrix()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        val wf = w.toFloat()
        fillShader = LinearGradient(
            0f, 0f, wf, 0f,
            intArrayOf(0xFF22D3EE.toInt(), 0xFF3B82F6.toInt(), 0xFFA78BFA.toInt()),
            null, Shader.TileMode.CLAMP
        )
        // 高光带：一小段亮色，扫过填充区域
        shimmerShader = LinearGradient(
            0f, 0f, wf, 0f,
            intArrayOf(
                0x00FFFFFF, 0x00FFFFFF, 0x66FFFFFF,
                0x00FFFFFF, 0x00FFFFFF
            ),
            floatArrayOf(0f, 0.40f, 0.5f, 0.60f, 1f),
            Shader.TileMode.CLAMP
        )
        flashShader = LinearGradient(
            0f, 0f, wf, 0f,
            intArrayOf(
                0x00FFFFFF, 0xAAFFFFFF.toInt(), 0x00FFFFFF, 0xAAFFFFFF.toInt(), 0x00FFFFFF
            ),
            floatArrayOf(0f, 0.28f, 0.5f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /**
     * 更新目标进度。
     * 往前进（target > displayed）时平滑追赶；往回跳（比如传输结束归零）直接落位，
     * 不做「进度条倒车」这种奇怪的动画。
     */
    var progress: Int
        get() = targetProgress
        set(value) {
            val target = value.coerceIn(0, 100)
            targetProgress = target
            if (target >= displayedProgress) {
                startChase(target.toFloat())
            } else {
                chaseAnimator?.cancel()
                displayedProgress = target.toFloat()
                invalidate()
            }
        }

    /** 传输中打开流光；传输结束关掉 */
    fun setBusy(active: Boolean) {
        if (busy == active) return
        busy = active
        if (active) {
            startShimmer()
        } else {
            shimmerAnimator?.cancel()
            shimmerAnimator = null
            shimmerPhase = 0f
        }
        invalidate()
    }

    /** 仅测试用：直接注入流光相位（测试环境动画时序不稳定，用确定性相位渲染对照） */
    internal fun debugShimmerPhase(value: Float) {
        shimmerPhase = value
        invalidate()
    }

    /** 传输成功：一次性闪光（亮起再回落），跟爱心迸发同一时刻发生 */
    fun celebrate() {
        flashAnimator?.cancel()
        flashAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                // 亮起后回落：强度取 sin 曲线的形状
                val k = it.animatedValue as Float
                flash = (k * (2f - k))
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    flash = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f
        rect.set(0f, 0f, w, h)

        trackPaint.shader = null
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        val filled = w * displayedProgress / 100f
        if (filled > 0f) {
            rect.set(0f, 0f, filled.coerceAtLeast(h), h)
            fillPaint.shader = fillShader
            canvas.drawRoundRect(rect, radius, radius, fillPaint)

            // 流光只在传输中显示：亮带中心沿已填充区域从左扫到右
            if (busy && filled > h) {
                val sweep = filled * shimmerPhase - w * 0.5f
                shimmerMatrix.setTranslate(sweep, 0f)
                shimmerShader?.setLocalMatrix(shimmerMatrix)
                shimmerPaint.shader = shimmerShader
                canvas.save()
                canvas.clipRect(0f, 0f, filled, h)
                rect.set(0f, 0f, w, h)
                canvas.drawRoundRect(rect, radius, radius, shimmerPaint)
                canvas.restore()
            }
        }

        // 完成闪光：整条 bar 上叠一层亮色扫带
        if (flash > 0.01f) {
            flashPaint.shader = flashShader
            flashPaint.alpha = (255 * flash).toInt().coerceIn(0, 255)
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius, radius, flashPaint)
        }
    }

    private fun startChase(target: Float) {
        chaseAnimator?.cancel()
        val start = displayedProgress
        chaseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val k = it.animatedFraction
                displayedProgress = start + (target - start) * k
                invalidate()
            }
            start()
        }
    }

    /** 流光的循环动画：约 1.9s 扫一轮，无限重复 */
    private fun startShimmer() {
        if (animationsOff || shimmerAnimator != null) return
        shimmerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                shimmerPhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private val animationsOff: Boolean by lazy {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        chaseAnimator?.cancel()
        shimmerAnimator?.cancel()
        flashAnimator?.cancel()
        shimmerAnimator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 从后台回来：还挂着追赶就续上，还在传输就重启流光循环
        if (targetProgress > 0 && displayedProgress < targetProgress) {
            startChase(targetProgress.toFloat())
        }
        if (busy) startShimmer()
    }
}
