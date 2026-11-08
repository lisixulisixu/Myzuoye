package com.example.myzuoye.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.sin

/**
 * 给按钮加「按下缩放 + 轻触觉」反馈。
 *
 * 涟漪（ripple）只有颜色变化，手指按住时感受不到「按下去了」；
 * 这里在 ACTION_DOWN 时缩到 0.96、抬起弹回，并触发一次系统轻触觉。
 * 触觉走 [View.performHapticFeedback]，会尊重用户关闭触感的设置，不强行震动。
 *
 * 关键点：OnTouchListener 一律返回 false，让点击事件继续冒泡给 View 自己处理，
 * 涟漪与 OnClickListener 都不受影响。禁用的按钮不会收到触摸事件，自然也没有反馈。
 */
fun View.attachPressFeedback(scale: Float = 0.96f) {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().cancel()
                v.animate().scaleX(scale).scaleY(scale).setDuration(80L).start()
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().cancel()
                v.animate().scaleX(1f).scaleY(1f).setDuration(130L).start()
            }
        }
        false
    }
}

/** 传输成功时的触感。CONFIRM 是 API 30 才有的语义化触感，旧版本退回短促的按键感。 */
fun View.hapticSuccess() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    } else {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
}

/** 传输失败时的触感：REJECT 是两段短促的「拒绝」脉冲，锁屏放兜里也能感知到出错。 */
fun View.hapticError() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        performHapticFeedback(HapticFeedbackConstants.REJECT)
    } else {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}

/**
 * 出错时的横向抖动：正弦衰减，左右各晃三下就停。
 *
 * 只改 translationX，不动 layout 参数，所以不会触发重新测量，也不会改变点击区域。
 * 「错」这件事用位移表达比只把文字变红有效得多——余光里也能看见，
 * 而且和 hapticError() 搭配起来是「看见 + 摸到」的同一件事。
 *
 * 系统关掉动画时直接返回：一帧静止的偏移看起来就像布局错位。
 */
fun View.shake(amplitudeDp: Float = 6f) {
    val animationsOff = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }.getOrDefault(false)
    if (animationsOff) return

    val amplitude = amplitudeDp * resources.displayMetrics.density
    animate().cancel()
    translationX = 0f
    ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 280L
        addUpdateListener { animator ->
            val t = animator.animatedValue as Float
            // sin(3πt) 在 0..1 内正好三次来回，再乘 (1-t) 让幅度逐渐收住
            translationX = (sin(t * 3.0 * Math.PI) * (1.0 - t)).toFloat() * amplitude
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                translationX = 0f
            }
        })
        start()
    }
}
