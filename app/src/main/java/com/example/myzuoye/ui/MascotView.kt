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
import kotlin.math.max
import kotlin.math.sin

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
 *
 * 气泡排版（[layoutBubble]）：
 * 气泡画在人物上方的独立区域，宽度受控件宽度限制。文字先按宽度自动折行，
 * 折成两行还放不下就逐级缩小字号，实在不行才截断加省略号 —— 保证任何长度的
 * 提示语都能完整显示在气泡里，不会被控件边缘裁掉。
 * 气泡变高时人物区域会自动让位（等比缩放后仍然贴底），不会和气泡叠在一起。
 */
class MascotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val MOOD_IDLE = 0
        const val MOOD_HAPPY = 1
        const val MOOD_ANGRY = 2

        /** 气泡字号（dp）与缩放下限 */
        private const val BASE_TEXT_DP = 21f
        private const val MIN_TEXT_DP = 13f

        /** 尽量压在这么多行以内；超过才开始缩字号 */
        private const val PREFERRED_LINES = 2

        /** 缩到最小字号还放不下时的硬上限 */
        private const val MAX_LINES = 3

        /** 完成/失败台词：随机抽一条，连续传几个文件不会每次都一样 */
        private val DONE_LINES = arrayOf(
            "传输完成！",
            "送达啦~",
            "这次超快！",
            "收到！一个字节都没少~",
            "稳稳送达！"
        )

        private val FAIL_LINES = arrayOf(
            "传输失败了…",
            "中断了，再试一次吧",
            "连接好像断了…"
        )

        /** 打字机每字间隔（毫秒）：再快就看不清，再慢会等得烦躁 */
        private const val TYPE_INTERVAL_MS = 30L
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

    /** 打字光标 */
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(60, 60, 70)
    }

    /** 系统是否关闭了动画（无障碍 / 开发者选项）。关掉时直接整句显示，不做逐字效果 */
    private val animationsOff: Boolean by lazy {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }

    private var bitmap: Bitmap? = null
    private var bubbleText: String? = null
    private var bubbleTimer: Runnable? = null

    /**
     * 打字机效果：气泡按整句排版（宽度不跳），但只画前 [visibleChars] 个字。
     *
     * 逐字显现比一次性弹出更像「在说话」；排版仍然基于全文，
     * 所以气泡尺寸从第一帧起就是最终大小，不会边打字边变宽。
     */
    private var bubbleLayout: Bubble? = null
    private var visibleChars = 0
    private var typingRunnable: Runnable? = null

    /** 气泡文字密度（按屏幕密度缩放，避免高分辨率下太小） */
    private val density = context.resources.displayMetrics.density

    init {
        loadBitmap()
    }

    /** 待机浮动是否在跑，避免重复启动 */
    private var floating = false

    /**
     * 待机时轻轻上下浮动，像在呼吸。
     *
     * 幅度只有 3dp：动得太多会让人分心，也太像「广告条」。
     * 动画挂在 ViewPropertyAnimator 上，系统关掉动画时会自动不生效。
     * 气泡和人物在同一个 View 里，跟着一起浮动，反而更像角色在说话。
     *
     * 注意：这里以前写的是 `if (floatAnimator != null)`，而字段是 Boolean，
     * 与 null 比较恒为 true，导致浮动从来没跑起来。现在改用明确的布尔值判断。
     */
    private fun startIdleFloat() {
        if (floating) return
        floating = true
        val dy = 3f * resources.displayMetrics.density
        animate().translationY(-dy)
            .setDuration(1600L)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction { floatDown(dy) }
            .start()
    }

    private fun floatDown(dy: Float) {
        if (!floating) return
        animate().translationY(0f)
            .setDuration(1600L)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction { if (floating) startIdleFloat() }
            .start()
    }

    private fun stopIdleFloat() {
        floating = false
        animate().cancel()
        translationY = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startIdleFloat()
    }

    override fun onDetachedFromWindow() {
        stopIdleFloat()
        bubbleTimer?.let { removeCallbacks(it) }
        typingRunnable?.let { removeCallbacks(it) }
        super.onDetachedFromWindow()
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 控件尺寸变了，之前排好的气泡不能再用
        bubbleLayout = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1) 先给气泡排版，算出它占多高。排版基于全文，打字过程中尺寸不变
        val bubble = bubbleText?.let { text ->
            bubbleLayout ?: layoutBubble(w, h, text).also { bubbleLayout = it }
        }

        // 2) 人物区域：平时固定留出上部 40%，气泡更高时继续往下让
        val avatarTop = max(h * 0.40f, bubble?.bottom ?: 0f)
        val avatarH = (h - avatarTop).coerceAtLeast(h * 0.22f)

        // 3) 等比绘制人物贴纸，水平居中、贴底
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val scale = (w / bw).coerceAtMost(avatarH / bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = (w - dw) / 2f
        val top = avatarTop + (avatarH - dh)
        canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), bitmapPaint)

        // 4) 气泡画在人物上方（后画，保证压在人物之上也不会被裁）
        bubble?.let { drawBubble(canvas, it) }
    }

    // ------------------------------------------------------------------ 气泡排版

    /** 气泡的排版结果：折好的行、字号、行高与外框矩形 */
    private class Bubble(
        val lines: List<String>,
        val textSize: Float,
        val lineHeight: Float,
        val rect: RectF,
        val tailTipY: Float
    ) {
        /** 气泡（含尾巴）底边，人物区域从这里往下开始 */
        val bottom: Float get() = tailTipY
    }

    /**
     * 按控件宽度给气泡排版。
     *
     * 策略：从基准字号开始折行，超过 [PREFERRED_LINES] 行就缩 1dp 再试，
     * 直到行数达标或到达最小字号；仍然超行就截断加省略号。
     */
    private fun layoutBubble(viewW: Float, viewH: Float, text: String): Bubble {
        val padH = 9f * density
        val padV = 7f * density
        val maxBubbleW = (viewW - 10f * density).coerceAtLeast(96f * density)
        val maxTextW = maxBubbleW - padH * 2

        var size = BASE_TEXT_DP * density
        val minSize = MIN_TEXT_DP * density
        var lines: List<String>
        while (true) {
            bubbleTextPaint.textSize = size
            lines = wrapText(text, maxTextW)
            if (lines.size <= PREFERRED_LINES || size <= minSize) break
            size -= 1f * density
        }
        if (lines.size > MAX_LINES) {
            lines = ellipsize(lines, maxTextW)
            bubbleTextPaint.textSize = size
        }

        val lineHeight = size * 1.26f
        var contentH = lines.size * lineHeight + padV * 2

        // 气泡最多占控件的 62%，否则人物会被挤没
        val maxBubbleH = viewH * 0.62f
        if (contentH > maxBubbleH) {
            contentH = maxBubbleH
        }

        val widest = lines.maxOfOrNull { bubbleTextPaint.measureText(it) } ?: 0f
        val bubbleW = (widest + padH * 2).coerceIn(72f * density, maxBubbleW)

        // 水平居中，但整体钳制在控件内，避免左右被裁
        val half = bubbleW / 2f
        var cx = viewW / 2f
        if (cx - half < 0f) cx = half
        if (cx + half > viewW) cx = viewW - half
        if (cx - half < 0f) cx = half

        val top = 2f * density
        val rect = RectF(cx - half, top, cx + half, top + contentH)
        val tailHeight = 11f * density
        return Bubble(lines, size, lineHeight, rect, rect.bottom + tailHeight)
    }

    /** 按最大宽度折行；中文没有空格，用 breakText 逐次截断即可 */
    private fun wrapText(text: String, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val result = ArrayList<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            val count = bubbleTextPaint.breakText(rest, true, maxWidth, null).coerceAtLeast(1)
            val line = rest.substring(0, count).trimEnd()
            // 纯空白行（比如折行点正好落在空格上）并到上一行，避免出现空行
            if (line.isNotEmpty()) {
                result += line
            } else if (result.isNotEmpty()) {
                result[result.size - 1] = result[result.size - 1] + rest.substring(0, count)
            }
            rest = rest.substring(count)
            if (result.size > MAX_LINES + 2) break
        }
        return result.ifEmpty { listOf(text) }
    }

    /** 超过 [MAX_LINES] 时，把余下的内容压到最后一行的省略号里 */
    private fun ellipsize(lines: List<String>, maxWidth: Float): List<String> {
        val kept = lines.take(MAX_LINES).toMutableList()
        if (kept.isEmpty()) return kept
        var last = kept.last()
        val ellipsis = "…"
        while (last.isNotEmpty() &&
            bubbleTextPaint.measureText(last + ellipsis) > maxWidth
        ) {
            last = last.dropLast(1)
        }
        kept[kept.size - 1] = last + ellipsis
        return kept
    }

    // ------------------------------------------------------------------ 气泡绘制

    private fun drawBubble(canvas: Canvas, bubble: Bubble) {
        val rect = bubble.rect

        // 尾巴：从气泡底部中心指向下方的人物
        val tailPath = Path().apply {
            moveTo(rect.centerX() - 8f * density, rect.bottom - 1f)
            lineTo(rect.centerX() + 8f * density, rect.bottom - 1f)
            lineTo(rect.centerX(), bubble.tailTipY)
            close()
        }
        val bubblePath = Path().apply {
            addRoundRect(rect, 18f * density, 18f * density, Path.Direction.CW)
        }

        canvas.drawPath(bubblePath, bubbleFillPaint)
        canvas.drawPath(tailPath, bubbleFillPaint)
        // 描边让气泡框更接近“手绘框”效果；尾巴也补一条边，接缝更自然
        canvas.drawPath(bubblePath, bubbleStrokePaint)
        canvas.drawPath(tailPath, bubbleStrokePaint)

        bubbleTextPaint.textSize = bubble.textSize
        var baseline = rect.top + 7f * density - bubbleTextPaint.ascent()
        // 打字机：只画前 visibleChars 个字；visibleChars 为 Int.MAX_VALUE 时等于整句
        var remaining = visibleChars
        for (line in bubble.lines) {
            if (remaining <= 0) break
            val shown = if (remaining >= line.length) line else line.substring(0, remaining)
            remaining -= line.length
            // 气泡被压缩高度时，行距按实际可用高度均分，避免文字溢出气泡底边
            canvas.drawText(shown, rect.centerX(), baseline, bubbleTextPaint)
            baseline += bubble.lineHeight
            if (baseline - bubbleTextPaint.descent() > rect.bottom) break
        }

        // 还在打字时，在最后一个字后面画一根闪烁的竖线，读起来更像「正在说话」
        val full = bubbleText
        if (full != null && visibleChars < full.length) {
            drawTypingCaret(canvas, bubble)
        }
    }

    /** 打字光标：定位到当前最后一个字右侧，明暗按时间缓慢呼吸 */
    private fun drawTypingCaret(canvas: Canvas, bubble: Bubble) {
        val rect = bubble.rect
        // 找出光标所在行与最后一个可见字
        var lineIndex = 0
        var used = 0
        for ((index, line) in bubble.lines.withIndex()) {
            if (used + line.length >= visibleChars) {
                lineIndex = index
                break
            }
            used += line.length
        }
        val line = bubble.lines.getOrNull(lineIndex) ?: return
        val shownLen = (visibleChars - used).coerceIn(0, line.length)
        val shown = line.substring(0, shownLen)
        val textW = bubbleTextPaint.measureText(shown)
        val x = rect.centerX() - bubbleTextPaint.measureText(line) / 2f + textW + 1.5f * density
        val top = rect.top + 7f * density + lineIndex * bubble.lineHeight
        val bottom = top + bubble.textSize * 1.1f

        val blink = ((sin(System.currentTimeMillis() / 260.0) + 1) / 2).toFloat()
        caretPaint.alpha = (90 + 130 * blink).toInt()
        canvas.drawRoundRect(x, top, x + 1.8f * density, bottom, 1f * density, 1f * density, caretPaint)
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

    /** 让角色说一句话（外部也可以直接调用，方便以后扩展剧情） */
    fun say(text: String) {
        if (text.isBlank()) return
        showBubble(text)
    }

    private fun showBubble(text: String) {
        bubbleText = text
        // 换了文案就重新排版（尺寸按全文算，打字过程中不会变宽）
        bubbleLayout = null
        typingRunnable?.let { removeCallbacks(it) }
        visibleChars = if (animationsOff) text.length else 0

        if (visibleChars < text.length) {
            val step = object : Runnable {
                override fun run() {
                    if (bubbleText != text) return
                    visibleChars = (visibleChars + 1).coerceAtMost(text.length)
                    invalidate()
                    if (visibleChars < text.length) postDelayed(this, TYPE_INTERVAL_MS)
                }
            }
            typingRunnable = step
            postDelayed(step, TYPE_INTERVAL_MS)
        }

        invalidate()
        bubbleTimer?.let { removeCallbacks(it) }
        bubbleTimer = Runnable {
            bubbleText = null
            bubbleLayout = null
            visibleChars = 0
            invalidate()
        }
        // 文字越长停留越久，长句子来不及看完就消失会显得很敷衍；
        // 打字本身也花时间，所以额外把打字耗时补进停留时长
        val typingCost = text.length.coerceAtMost(40) * TYPE_INTERVAL_MS
        val duration = 1800L + typingCost + text.length * 70L
        postDelayed(bubbleTimer, duration.coerceAtMost(5200L))
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

    /** 传输完成提醒：台词随机，连续传几个文件不会每次都一样 */
    fun showDone() {
        setTransferring(false)
        setMood(MOOD_HAPPY)
        showBubble(DONE_LINES.random())
    }

    /** 传输失败提醒 */
    fun showFail() {
        setTransferring(false)
        setMood(MOOD_ANGRY)
        showBubble(FAIL_LINES.random())
    }
}
