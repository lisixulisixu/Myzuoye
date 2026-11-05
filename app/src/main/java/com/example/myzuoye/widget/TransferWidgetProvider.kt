package com.example.myzuoye.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.myzuoye.MainActivity
import com.example.myzuoye.R

/**
 * 桌面小组件：显示连接状态与已接收文件数，点按任意位置进入应用。
 *
 * 更新时机：
 * - 系统按 [android:updatePeriodMillis] 定期回调 [onUpdate]（最小 30 分钟）；
 * - 应用内状态变化（连上/断开、开始/结束传输、收到文件）时主动调用 [refresh]，
 *   这是让小组件「看起来实时」的主要手段。
 */
class TransferWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val views = buildViews(context)
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    companion object {
        /** 已连接：绿色 */
        private const val COLOR_ONLINE = 0xFF34C759.toInt()

        /** 未连接：浅灰 */
        private const val COLOR_OFFLINE = 0xFFB0B8C4.toInt()

        /** 传输中：主题蓝 */
        private const val COLOR_TRANSFER = 0xFF2E7BD6.toInt()

        /** 主动刷新所有已放置的小组件实例 */
        fun refresh(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(
                ComponentName(appContext, TransferWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val views = buildViews(appContext)
            ids.forEach { id ->
                manager.updateAppWidget(id, views)
            }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_transfer)
            val snap = WidgetState.snapshot(context)

            // 状态优先级：传输中 > 已连接 > 未连接
            val statusText: String
            val dotColor: Int
            when {
                snap.transferring -> {
                    statusText = context.getString(R.string.widget_status_receiving)
                    dotColor = COLOR_TRANSFER
                }

                snap.connected -> {
                    statusText = context.getString(R.string.widget_status_connected)
                    dotColor = COLOR_ONLINE
                }

                else -> {
                    statusText = context.getString(R.string.widget_status_disconnected)
                    dotColor = COLOR_OFFLINE
                }
            }
            views.setTextViewText(R.id.widgetStatus, statusText)
            views.setInt(R.id.widgetDot, "setColorFilter", dotColor)

            // 第二行：传输中显示文件名，空闲时显示累计接收数量
            val secondLine = if (snap.transferring && snap.transferName.isNotBlank()) {
                context.getString(R.string.widget_receiving, snap.transferName)
            } else {
                context.getString(R.string.widget_received, snap.receivedCount)
            }
            views.setTextViewText(R.id.widgetReceived, secondLine)

            // 点按整个卡片进入应用
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)

            return views
        }
    }
}
