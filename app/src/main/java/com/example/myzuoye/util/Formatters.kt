package com.example.myzuoye.util

import java.util.Locale

/**
 * 容量、速度、剩余时间的统一格式化。
 *
 * 这些文案在传输状态栏、已收文件列表、游戏弹窗里都要用，
 * 分散写三份最容易出现「这里 1.5 MB、那里 1.5MB」的不一致。
 *
 * 全部按 1024 进制（和系统文件管理器一致）；小数位随量级收缩，
 * 免得出现 `1023.98 KB` 这种既长又没意义的数字。
 */
object Formatters {

    /** 字节数 → 适合人读的容量，如 `1.5 MB`、`812 KB`、`37 B` */
    fun size(bytes: Long): String = when {
        bytes < 0 -> "--"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.0f KB".format(Locale.US, bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(Locale.US, bytes / 1024.0 / 1024.0)
        else -> "%.2f GB".format(Locale.US, bytes / 1024.0 / 1024.0 / 1024.0)
    }

    /** 速度，单位固定带 `/s` */
    fun speed(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0) "--" else "${size(bytesPerSecond)}/s"

    /** 秒数 → `8 秒` / `1 分 20 秒` / `1 小时 5 分` */
    fun duration(seconds: Long): String {
        if (seconds <= 0) return "0 秒"
        if (seconds < 60) return "$seconds 秒"
        if (seconds < 3600) {
            val m = seconds / 60
            val s = seconds % 60
            return if (s == 0L) "$m 分" else "$m 分 $s 秒"
        }
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (m == 0L) "$h 小时" else "$h 小时 $m 分"
    }

    /** 时间戳 → `14:08` 这样的气泡时间标签 */
    fun clockTime(epochMillis: Long): String =
        java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(epochMillis))
}
