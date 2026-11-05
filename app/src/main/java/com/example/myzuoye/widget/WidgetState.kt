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
 */
object WidgetState {

    private const val PREFS = "widget_state"
    private const val KEY_CONNECTED = "connected"
    private const val KEY_TRANSFERRING = "transferring"
    private const val KEY_RECEIVED_COUNT = "received_count"
    private const val KEY_TRANSFER_NAME = "transfer_name"

    data class Snapshot(
        val connected: Boolean,
        val transferring: Boolean,
        val receivedCount: Int,
        val transferName: String
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun snapshot(context: Context): Snapshot {
        val p = prefs(context)
        return Snapshot(
            connected = p.getBoolean(KEY_CONNECTED, false),
            transferring = p.getBoolean(KEY_TRANSFERRING, false),
            receivedCount = p.getInt(KEY_RECEIVED_COUNT, 0),
            transferName = p.getString(KEY_TRANSFER_NAME, "").orEmpty()
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

    /**
     * 是否正在传输。
     *
     * [name] 用于传输中显示文件名；key 里带上名字，
     * 这样连续多个文件时文件名变化也能刷新，但同一文件的进度回调不会反复刷 widget。
     */
    fun setTransferring(context: Context, transferring: Boolean, name: String = "") {
        val p = prefs(context)
        val current = p.getBoolean(KEY_TRANSFERRING, false)
        val currentName = p.getString(KEY_TRANSFER_NAME, "").orEmpty()
        val safeName = if (transferring) name else ""
        if (current == transferring && currentName == safeName) return
        p.edit()
            .putBoolean(KEY_TRANSFERRING, transferring)
            .putString(KEY_TRANSFER_NAME, safeName)
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
