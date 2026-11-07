package com.example.myzuoye.widget

import android.content.Context

/**
 * 桌面小组件读取的状态快照。
 *
 * 小组件由桌面进程通过 RemoteViews 渲染，拿不到应用内存里的 [com.example.myzuoye.transfer.TransferManager]
 * 状态，所以关键状态由应用侧写进 SharedPreferences，小组件更新时再读出来画。
 *
 * 这里刻意保持极轻量：只有几个标量，读写都是内存级的 SharedPreferences 操作，
 * 不会给主线程带来可感知的负担。
 *
 * 写入侧做了两层节流，避免「每个 64KB 分片都跨进程刷新一次卡片」：
 * - 传输进度只在百分比跨越 5% 时才写盘；
 * - 状态（连接 / 传输 / 文件名）没变时直接返回。
 */
object WidgetState {

    private const val PREFS = "widget_state"
    private const val KEY_CONNECTED = "connected"
    private const val KEY_TRANSFERRING = "transferring"
    private const val KEY_RECEIVED_COUNT = "received_count"
    private const val KEY_TRANSFER_NAME = "transfer_name"
    private const val KEY_PROGRESS = "progress"
    private const val KEY_PEER = "peer"

    /** 传输速度（字节/秒），0 表示还没采到样本 */
    private const val KEY_SPEED = "speed"

    /** 预计剩余秒数，0 表示算不出来（总量未知或速度为 0） */
    private const val KEY_ETA = "eta"

    data class Snapshot(
        val connected: Boolean,
        val transferring: Boolean,
        val receivedCount: Int,
        val transferName: String,
        /** 0..100，仅在传输中有效 */
        val progress: Int,
        /** 已连接的对端名称，没有则为空 */
        val peer: String,
        /** 传输速度（字节/秒），没有在传时为 0 */
        val speed: Int = 0,
        /** 预计剩余秒数，算不出来时为 0 */
        val etaSeconds: Int = 0
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun snapshot(context: Context): Snapshot {
        val p = prefs(context)
        return Snapshot(
            connected = p.getBoolean(KEY_CONNECTED, false),
            transferring = p.getBoolean(KEY_TRANSFERRING, false),
            receivedCount = p.getInt(KEY_RECEIVED_COUNT, 0),
            transferName = p.getString(KEY_TRANSFER_NAME, "").orEmpty(),
            progress = p.getInt(KEY_PROGRESS, 0).coerceIn(0, 100),
            peer = p.getString(KEY_PEER, "").orEmpty(),
            speed = p.getInt(KEY_SPEED, 0),
            etaSeconds = p.getInt(KEY_ETA, 0)
        )
    }

    /** P2P / 文本通道是否已连接 */
    fun setConnected(context: Context, connected: Boolean) {
        val p = prefs(context)
        if (p.getBoolean(KEY_CONNECTED, false) == connected) {
            // 状态没变就不必重绘小组件，省一次跨进程更新
            return
        }
        p.edit().putBoolean(KEY_CONNECTED, connected).apply()
        TransferWidgetProvider.refresh(context)
    }

    /** 记住对端名称，卡片上显示「已连接 Pixel 9」比「已连接」信息量大得多 */
    fun setPeer(context: Context, name: String) {
        val p = prefs(context)
        val current = p.getString(KEY_PEER, "").orEmpty()
        if (current == name) return
        p.edit().putString(KEY_PEER, name).apply()
        TransferWidgetProvider.refresh(context)
    }

    /**
     * 是否正在传输。
     *
     * [name] 用于传输中显示文件名；key 里带上名字，
     * 这样连续多个文件时文件名变化也能刷新，但同一文件的进度回调不会反复刷 widget。
     */
    fun setTransferring(
        context: Context,
        transferring: Boolean,
        name: String = "",
        progress: Int = 0,
        speed: Long = 0L,
        etaSeconds: Long = 0L
    ) {
        val p = prefs(context)
        val current = p.getBoolean(KEY_TRANSFERRING, false)
        val currentName = p.getString(KEY_TRANSFER_NAME, "").orEmpty()
        val safeName = if (transferring) name else ""
        val safeProgress = if (transferring) progress.coerceIn(0, 100) else 0
        val safeSpeed = if (transferring) speed.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() else 0
        val safeEta = if (transferring) etaSeconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() else 0

        val sameState = current == transferring && currentName == safeName
        // 同一文件的高频进度：只有跨过 5% 才值得刷新一次
        val progressDelta = kotlin.math.abs(safeProgress - p.getInt(KEY_PROGRESS, 0))
        if (sameState && progressDelta < 5) return

        p.edit()
            .putBoolean(KEY_TRANSFERRING, transferring)
            .putString(KEY_TRANSFER_NAME, safeName)
            .putInt(KEY_PROGRESS, safeProgress)
            .putInt(KEY_SPEED, safeSpeed)
            .putInt(KEY_ETA, safeEta)
            .apply()
        TransferWidgetProvider.refresh(context)
    }

    /** 成功接收一个文件，累计计数 +1 */
    fun addReceivedFile(context: Context) {
        val p = prefs(context)
        p.edit().putInt(KEY_RECEIVED_COUNT, p.getInt(KEY_RECEIVED_COUNT, 0) + 1).apply()
        TransferWidgetProvider.refresh(context)
    }
}
