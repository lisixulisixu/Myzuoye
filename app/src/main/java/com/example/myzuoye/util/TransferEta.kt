package com.example.myzuoye.util

import android.content.Context
import com.example.myzuoye.R

/**
 * 传输速度 / 剩余时间的文案工具，从 MainActivity 拆出来的纯逻辑。
 *
 * [etaSeconds] 是纯函数，JVM 单测直接覆盖；[speedSuffix] 只是把字符串资源拼起来。
 */
object TransferEta {

    /**
     * 「速度 + 剩余时间」后缀，拼在传输进度文案后面。
     *
     * 速度还没采到样本时返回空串，调用方就退回不带速度的文案，
     * 而不是显示 `-- /s` 这种废信息。
     */
    fun speedSuffix(context: Context, speed: Long, totalBytes: Long, progress: Float): String {
        if (speed <= 0L) return ""
        val remaining = (totalBytes * (1f - progress.coerceIn(0f, 1f))).toLong()
        return if (remaining > 0L && totalBytes > 0L) {
            context.getString(
                R.string.transfer_speed_eta,
                Formatters.speed(speed),
                Formatters.duration(remaining / speed)
            )
        } else {
            context.getString(R.string.transfer_speed_only, Formatters.speed(speed))
        }
    }

    /**
     * 估算剩余秒数，供桌面小组件显示。
     *
     * 速度还没采到样本、或总量未知（SAF 不给 SIZE）时返回 0，
     * 卡片那边就不会硬凑出一个「剩 -- 秒」。
     */
    fun etaSeconds(speed: Long, totalBytes: Long, progress: Float): Long {
        if (speed <= 0L || totalBytes <= 0L) return 0L
        val remaining = (totalBytes * (1f - progress.coerceIn(0f, 1f))).toLong()
        return if (remaining > 0L) remaining / speed else 0L
    }
}
