package com.example.myzuoye.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import com.example.myzuoye.R

/**
 * Q 版人物形象组件：显示圆角贴纸风格的 Q 版猫耳女仆图片，点击有简单互动。
 *
 * 交互：
 * - 点击：人物弹跳一下（缩放动画），随机切到另一个表情，并弹出气泡文字；
 * - 传输中 / 传输结束：由调用方通过 [setMood] 切换表情并提示。
 *
 * 三种表情：
 * - [MOOD_IDLE]  无语（默认，平时）
 * - [MOOD_HAPPY] 开心（传输完成）
 * - [MOOD_ANGRY] 生气（传输失败）
 */
class MascotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val MOOD_IDLE = 0
        const val MOOD_HAPPY = 1
        const val MOOD_ANGRY = 2
    }

    private var mood = MOOD_IDLE
    private var transferring = false

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    // 气泡外框（描边）与填充
    private val bubbleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val bubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(180, 180, 195)
        strokeWidth = 2f * context.resources.displayMetrics.density
    }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(60, 60, 70)
        textSize = 28f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private var bitmap: Bitmap? = null
    private var bubbleText: String? = null
    private var bubbleTimer: Runnable? = null

    /** 气泡文字密度（按屏幕密度缩放，避免高分辨率下太小） */
    private val density = context.resources.displayMetrics.density

    init {
        loadBitmap()
    }

    private val idleLines = arrayOf(
        "喵~", "在呢在呢", "戳我干嘛", "加油鸭", "文件传好了吗", "今天也要元气满满"
    )

    private fun loadBitmap() {
        val res = when (mood) {
            MOOD_HAPPY -> R.drawable.mascot_happy
            MOOD_ANGRY -> R.drawable.mascot_angry
            else -> R.drawable.mascot_idle
        }
        // 回收旧图避免内存泄漏
        val old = bitmap
        bitmap = BitmapFactory.decodeResource(resources, res)
        if (old != null && old !== bitmap) {
            old.recycle()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 气泡区域占上部约 40%，人物贴纸靠底部绘制
        val bubbleZone = h * 0.42f
        val avatarTop = bubbleZone
        val avatarH = h - avatarTop

        // 等比绘制人物贴纸，贴底部
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val scale = (w / bw).coerceAtMost(avatarH / bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = (w - dw) / 2f
        val top = avatarTop + (avatarH - dh) / 2f
        canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), bitmapPaint)

        // 气泡（绘制在人物上方，独立于人物之外，尾巴指向下方人物）
        bubbleText?.let { drawBubble(canvas, it) }
    }

    private fun drawBubble(canvas: Canvas, text: String) {
        val w = width.toFloat()
        val textSize = 22f * density
        bubbleTextPaint.textSize = textSize
        val textW = bubbleTextPaint.measureText(text)
        val bubbleH = 42f * density
        val bubbleW = (textW + 28f * density).coerceAtLeast(64f * density)
        // 气泡水平居中，但钳制在 view 内，避免超出右边缘被裁切
        var cx = w / 2f
        val half = bubbleW / 2f
        if (cx - half < 0f) cx = half
        if (cx + half > w) cx = w - half
        // 气泡浮在人物上方（view 顶部区域）
        val top = 4f * density
        val rect = RectF(cx - half, top, cx + half, top + bubbleH)

        // 尾巴：从气泡底部中心指向下方的人物
        val tailTipY = top + bubbleH + 14f * density
        val tailPath = Path().apply {
            moveTo(rect.centerX() - 8f * density, rect.bottom)
            lineTo(rect.centerX() + 8f * density, rect.bottom)
            lineTo(rect.centerX(), tailTipY)
            close()
        }

        val bubblePath = Path().apply {
            addRoundRect(rect, 20f * density, 20f * density, Path.Direction.CW)
        }
        canvas.drawPath(bubblePath, bubbleFillPaint)
        canvas.drawPath(tailPath, bubbleFillPaint)
        // 描边让气泡框更接近“手绘框”效果
        canvas.drawPath(bubblePath, bubbleStrokePaint)

        val baseline = rect.centerY() - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) / 2f
        canvas.drawText(text, cx, baseline, bubbleTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onTap()
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun onTap() {
        val scaleX = ObjectAnimator.ofFloat(this, View.SCALE_X, 1f, 1.15f, 0.95f, 1f)
        val scaleY = ObjectAnimator.ofFloat(this, View.SCALE_Y, 1f, 1.15f, 0.95f, 1f)
        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 450L
            interpolator = OvershootInterpolator()
            start()
        }

        if (transferring) {
            showBubble("传输中，稍等一下~")
        } else {
            showBubble(idleLines.random())
            mood = if (mood == MOOD_IDLE) MOOD_HAPPY else MOOD_IDLE
            loadBitmap()
            invalidate()
        }
    }

    private fun showBubble(text: String) {
        bubbleText = text
        invalidate()
        bubbleTimer?.let { removeCallbacks(it) }
        bubbleTimer = Runnable {
            bubbleText = null
            invalidate()
        }
        postDelayed(bubbleTimer, 1800L)
    }

    /** 切换情绪表情；返回是否真的变化 */
    fun setMood(newMood: Int): Boolean {
        if (mood == newMood) return false
        mood = newMood
        loadBitmap()
        invalidate()
        return true
    }

    /** 标记传输状态（传输中点击只提示，不切表情） */
    fun setTransferring(active: Boolean) {
        transferring = active
    }

    /** 传输中的提醒气泡 */
    fun showTransferring() {
        setTransferring(true)
        setMood(MOOD_IDLE)
        showBubble("开始传输啦~")
    }

    /** 传输完成提醒 */
    fun showDone() {
        setTransferring(false)
        setMood(MOOD_HAPPY)
        showBubble("传输完成！")
    }

    /** 传输失败提醒 */
    fun showFail() {
        setTransferring(false)
        setMood(MOOD_ANGRY)
        showBubble("传输失败了…")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bubbleTimer?.let { removeCallbacks(it) }
    }
}
