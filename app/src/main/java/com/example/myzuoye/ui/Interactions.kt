package com.example.myzuoye.ui

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

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
