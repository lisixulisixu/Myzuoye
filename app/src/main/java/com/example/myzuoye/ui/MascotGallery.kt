package com.example.myzuoye.ui

import android.app.Activity
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.myzuoye.R

/**
 * 「小人表情」预览：把图片库里所有贴纸摆出来看看。
 *
 * 做这个入口的原因很实际：表情库扩充之后，用户（包括我们自己）需要一个地方
 * 确认「到底有哪些表情、分别叫什么」，否则新图只会在某个偶发场景里闪一下，
 * 谁也不知道它存在。顺带也是个小小的收集感。
 */
object MascotGallery {

    /** 表情 → 展示名，顺序与 [MascotView.ALL_MOODS] 对齐 */
    private val NAMES = mapOf(
        MascotView.MOOD_IDLE to "平时",
        MascotView.MOOD_HAPPY to "开心",
        MascotView.MOOD_CHEER to "欢呼",
        MascotView.MOOD_SURPRISED to "惊讶",
        MascotView.MOOD_SHY to "害羞",
        MascotView.MOOD_SMUG to "得意",
        MascotView.MOOD_SLEEPY to "困了",
        MascotView.MOOD_SAD to "委屈",
        MascotView.MOOD_ANGRY to "生气"
    )

    fun show(activity: Activity) {
        if (activity.isFinishing) return
        val density = activity.resources.displayMetrics.density
        val gap = (8 * density).toInt()

        val grid = GridLayout(activity).apply {
            columnCount = 3
            setPadding(0, gap, 0, 0)
        }

        MascotView.ALL_MOODS.forEach { mood ->
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
                }
            }
            val size = (84 * density).toInt()
            cell.addView(ImageView(activity).apply {
                setImageResource(MascotView.drawableForMood(mood))
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(size, size)
                contentDescription = NAMES[mood]
            })
            cell.addView(TextView(activity).apply {
                text = NAMES[mood].orEmpty()
                textSize = 12f
                setTextColor(0xFF9FB0CC.toInt())
                gravity = Gravity.CENTER
            })
            grid.addView(cell)
        }

        val scroll = ScrollView(activity).apply {
            addView(grid)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (340 * density).toInt()
            )
        }

        AppDialog.builder(activity)
            .setTitle(R.string.tools_mascot_gallery)
            .setView(scroll)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }
}
