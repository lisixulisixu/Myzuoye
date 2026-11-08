package com.example.myzuoye.ui

import android.provider.Settings
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * 滚动时按「条目到列表中心的距离」做轻微缩放与压暗，做出一点纵深。
 *
 * 只改 scale / alpha，不碰 layout 参数，所以不会触发重新测量；
 * 每帧只是遍历可见条目（实际也就几个到十几个）。
 *
 * 幅度刻意做得很小：4% 缩放是「有纵深」，到 8% 以上就会变成「整个列表在呼吸」，
 * 反而碍事。系统关掉动画时直接不挂，静态列表不该有这种效果。
 */
fun RecyclerView.attachScrollDepth(
    maxScaleLoss: Float = 0.045f,
    maxAlphaLoss: Float = 0.10f
) {
    val animationsOff = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }.getOrDefault(false)
    if (animationsOff) return

    val update = {
        val pivot = height / 2f
        if (pivot > 0f) {
            for (i in 0 until childCount) {
                val child = getChildAt(i) ?: continue
                val center = (child.top + child.bottom) / 2f
                val d = (abs(center - pivot) / pivot).coerceIn(0f, 1f)
                val scale = 1f - maxScaleLoss * d
                child.scaleX = scale
                child.scaleY = scale
                child.alpha = 1f - maxAlphaLoss * d
            }
        }
    }

    addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            update()
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            // 惯性滚动停下来之后再对齐一次，避免停在某个中间帧上
            if (newState == RecyclerView.SCROLL_STATE_IDLE) update()
        }
    })
    post { update() }
}
