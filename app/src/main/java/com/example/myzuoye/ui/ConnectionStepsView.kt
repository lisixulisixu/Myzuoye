package com.example.myzuoye.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.example.myzuoye.R

/**
 * 连接进度指示：发现 → 协商 → 组网 → 通道。
 *
 * 之前整个连接过程只有一行文字在变（「正在搜索」→「正在连接」→「已连接」），
 * 用户看不出「卡在哪一步」。这里把四个阶段画成一排小圆点：
 * - 已完成：实心 + 勾；
 * - 进行中：实心 + 一圈呼吸光晕；
 * - 未开始：空心；
 * - 失败：红点，配合下方文字说明原因。
 *
 * 纯绘制，无子 View，不拦截触摸（它只是状态显示）。
 */
class ConnectionStepsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val STEP_DISCOVER = 0
        const val STEP_NEGOTIATE = 1
        const val STEP_GROUP = 2
        const val STEP_CHANNEL = 3

        /** 全部完成 */
        const val STEP_DONE = 4
    }

    private val density = resources.displayMetrics.density
    private val labels = arrayOf("发现", "协商", "组网", "通道")

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10.5f * density
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }

    /** 沿线段流动的「能量点」：外圈晕 + 内芯，两层圆都是预建 Paint，每帧零分配 */
    private val flowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 等待线段的流动虚线，相位动画用 canvas 平移实现，不用每帧新建 DashPathEffect */
    private val dashEffect = DashPathEffect(
        floatArrayOf(4f * density, 5f * density),
        0f
    )

    /** 当前进行到第几步（0..4，4 表示全部完成） */
    private var step = 0

    /** 当前步是否失败 */
    private var failed = false

    /** 呼吸光晕相位 */
    private var pulse = 0f

    private var animator: ValueAnimator? = null

    private val animationsOff: Boolean by lazy {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }

    private val accent = context.getColor(R.color.primary_light)
    private val doneColor = 0xFF34C759.toInt()
    private val failColor = 0xFFE06C75.toInt()
    private val idleColor = 0xFF5A6780.toInt()
    private val labelIdle = context.getColor(R.color.text_secondary)
    private val labelActive = context.getColor(R.color.text_primary)

    init {
        // 纯状态显示，别让 TalkBack 在这里停留太久
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "连接进度"
    }

    /**
     * @param step 0..4，4 表示全部完成
     * @param failed 当前步是否失败（失败时该步变红）
     */
    fun setProgress(step: Int, failed: Boolean = false) {
        val newStep = step.coerceIn(0, STEP_DONE)
        if (newStep == this.step && failed == this.failed) return
        this.step = newStep
        this.failed = failed
        contentDescription = buildDescription()
        invalidate()
    }

    private fun buildDescription(): String {
        val doneCount = step.coerceAtMost(STEP_DONE)
        return if (failed) {
            "连接进度：${labels.getOrElse(step) { "" }}失败"
        } else if (doneCount >= STEP_DONE) {
            "连接进度：已完成"
        } else {
            "连接进度：第 ${doneCount + 1} 步，${labels.getOrElse(doneCount) { "" }}"
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startPulse()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }

    private fun startPulse() {
        if (animationsOff || animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 仅测试用：直接注入呼吸相位（测试环境没有窗口附着，动画器跑不起来） */
    internal fun debugPulse(value: Float) {
        pulse = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val slot = w / labels.size
        val cy = 11f * density
        val r = 5.5f * density

        for (i in labels.indices) {
            val cx = slot * (i + 0.5f)
            val isDone = i < step
            val isActive = i == step

            // 连接线：已完成的部分用亮的，未完成用暗的；
            // 正在等待的那一段画流动虚线，刚被点亮的那一段有能量点流向活动步
            if (i < labels.size - 1) {
                val startX = cx + r + 3f * density
                val endX = slot * (i + 1.5f) - r - 3f * density
                linePaint.strokeWidth = 2f * density
                linePaint.pathEffect = null
                linePaint.color = if (i < step) doneColor else idleColor
                linePaint.alpha = if (i < step) 200 else 110
                canvas.drawLine(startX, cy, endX, cy, linePaint)

                if (!failed) {
                    when {
                        // 刚走完的线段：一颗小光点从上一个点流向当前活动点
                        i == step - 1 && step in 1 until STEP_DONE -> {
                            val k = pulse
                            val x = startX + (endX - startX) * k
                            flowPaint.color = accent
                            flowPaint.alpha = (60 * (1f - k)).toInt().coerceIn(0, 255)
                            canvas.drawCircle(x, cy, 3.2f * density, flowPaint)
                            flowPaint.alpha = 210
                            canvas.drawCircle(x, cy, 1.4f * density, flowPaint)
                        }

                        // 还没走到的线段：虚线缓慢向前蠕动，示意「马上轮到」
                        i == step && step < STEP_DONE -> {
                            val offset = pulse * 9f * density
                            canvas.save()
                            canvas.clipRect(startX, cy - 3f * density, endX, cy + 3f * density)
                            canvas.translate(offset, 0f)
                            linePaint.color = accent
                            linePaint.alpha = 80
                            linePaint.pathEffect = dashEffect
                            canvas.drawLine(
                                startX - 18f * density, cy,
                                endX + 9f * density, cy,
                                linePaint
                            )
                            linePaint.pathEffect = null
                            canvas.restore()
                        }
                    }
                }
            }

            when {
                isActive && failed -> {
                    dotPaint.color = failColor
                    canvas.drawCircle(cx, cy, r, dotPaint)
                    drawCross(canvas, cx, cy, r * 0.55f)
                }

                isActive -> {
                    // 呼吸光晕
                    val halo = r + (2f + 5f * pulse) * density
                    ringPaint.color = accent
                    ringPaint.alpha = ((1f - pulse) * 150).toInt().coerceIn(0, 255)
                    canvas.drawCircle(cx, cy, halo, ringPaint)
                    dotPaint.color = accent
                    canvas.drawCircle(cx, cy, r, dotPaint)
                }

                isDone -> {
                    dotPaint.color = doneColor
                    canvas.drawCircle(cx, cy, r, dotPaint)
                    drawCheck(canvas, cx, cy, r)
                }

                else -> {
                    dotPaint.color = idleColor
                    dotPaint.alpha = 140
                    canvas.drawCircle(cx, cy, r - 0.5f * density, dotPaint)
                    dotPaint.alpha = 255
                }
            }

            textPaint.color = when {
                isActive && failed -> failColor
                isActive -> labelActive
                isDone -> doneColor
                else -> labelIdle
            }
            canvas.drawText(labels[i], cx, cy + r + 13f * density, textPaint)
        }
    }

    /** 已完成的小勾 */
    private fun drawCheck(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = android.graphics.Path().apply {
            moveTo(cx - r * 0.45f, cy + r * 0.02f)
            lineTo(cx - r * 0.08f, cy + r * 0.38f)
            lineTo(cx + r * 0.48f, cy - r * 0.36f)
        }
        canvas.drawPath(path, p)
    }

    /** 失败的红点里画个叉 */
    private fun drawCross(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(cx - r, cy - r, cx + r, cy + r, p)
        canvas.drawLine(cx + r, cy - r, cx - r, cy + r, p)
    }

    /** 给外部留一个矩形用于测试点击/布局对齐 */
    fun dotBounds(index: Int): RectF {
        val slot = width.toFloat() / labels.size
        val cx = slot * (index + 0.5f)
        val r = 5.5f * density
        return RectF(cx - r, 11f * density - r, cx + r, 11f * density + r)
    }
}
