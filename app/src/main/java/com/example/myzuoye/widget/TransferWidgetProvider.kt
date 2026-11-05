package com.example.myzuoye.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.example.myzuoye.MainActivity
import com.example.myzuoye.R
import com.example.myzuoye.weather.WeatherRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 桌面小组件：天气场景 + 连接状态 + 已接收文件数，点按任意位置进入应用。
 *
 * 更新时机：
 * - 系统按 [android:updatePeriodMillis] 定期回调 [onUpdate]（最小 30 分钟）；
 * - 应用内状态变化（连上/断开、开始/结束传输、收到文件）时主动调用 [refresh]；
 * - 天气数据拉取完成后由 [WeatherRepository] 主动调用 [refresh]。
 *
 * 卡片背景不是纯色，而是按控件实时尺寸离屏绘制的天气场景位图，
 * 具体画法见 [WeatherSceneRenderer]。
 */
class TransferWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 桌面回调是天然的「定期心跳」，顺手刷新一次天气（内部有 15 分钟节流）
        WeatherRepository.refreshAsync(context)

        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context, appWidgetManager, id))
        }
    }

    /** 用户拖动改变卡片大小：重新按新尺寸渲染场景，避免位图被拉伸糊掉 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, appWidgetManager, appWidgetId))
    }

    companion object {
        /** 已连接：绿色 */
        private const val COLOR_ONLINE = 0xFF34C759.toInt()

        /** 未连接：浅灰 */
        private const val COLOR_OFFLINE = 0xFFB0B8C4.toInt()

        /** 传输中：主题蓝 */
        private const val COLOR_TRANSFER = 0xFF2E7BD6.toInt()

        /** 日间文字：深墨蓝；夜间文字：近白 */
        private const val TEXT_DARK_MAIN = 0xFF22303F.toInt()
        private const val TEXT_DARK_SUB = 0xFF4E5A6C.toInt()
        private const val TEXT_DARK_WEAK = 0xFF66738A.toInt()

        private const val TEXT_LIGHT_MAIN = 0xFFFFFFFF.toInt()
        private const val TEXT_LIGHT_SUB = 0xFFD3DDEE.toInt()
        private const val TEXT_LIGHT_WEAK = 0xFFB9C6DC.toInt()

        /** 天体（太阳/月亮）图层边长，单位 dp，与 widget_transfer.xml 里的控件尺寸一致 */
        private const val CELESTIAL_SIZE_DP = 100

        /** 默认卡片尺寸（dp），与 widget_transfer_info.xml 的 minWidth/minHeight 对应 */
        private const val DEFAULT_WIDTH_DP = 250
        private const val DEFAULT_HEIGHT_DP = 110

        /** 主动刷新所有已放置的小组件实例 */
        fun refresh(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(
                ComponentName(appContext, TransferWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            ids.forEach { id ->
                manager.updateAppWidget(id, buildViews(appContext, manager, id))
            }
        }

        private fun buildViews(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_transfer)
            val snap = WidgetState.snapshot(context)
            val weather = WeatherRepository.cached(context)

            // ---------------- 天气场景 ----------------
            val options = runCatching { manager.getAppWidgetOptions(widgetId) }.getOrNull()
            val widthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_WIDTH_DP)
                ?: DEFAULT_WIDTH_DP
            val heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, DEFAULT_HEIGHT_DP)
                ?: DEFAULT_HEIGHT_DP
            val density = context.resources.displayMetrics.density
            val widthPx = (widthDp * density).toInt().coerceAtLeast(200)
            val heightPx = (heightDp * density).toInt().coerceAtLeast(120)

            // 没有数据时按「白天多云」画，保证是浅色底 — 否则文字颜色会和背景撞在一起
            val sceneDay = if (weather.hasData) weather.isDay else true
            views.setImageViewBitmap(
                R.id.widgetScene,
                WeatherSceneRenderer.renderBackground(widthPx, heightPx, weather.condition, sceneDay)
            )

            // 天体层：阴雨雪雾看不到太阳/月亮，直接隐藏
            val celestialSize = (CELESTIAL_SIZE_DP * density).toInt()
            val celestial = WeatherSceneRenderer.renderCelestial(
                celestialSize, weather.condition, sceneDay
            )
            if (celestial != null) {
                views.setViewVisibility(R.id.widgetCelestial, View.VISIBLE)
                views.setImageViewBitmap(R.id.widgetCelestial, celestial)
            } else {
                views.setViewVisibility(R.id.widgetCelestial, View.GONE)
            }

            // ---------------- 文字颜色 ----------------
            val night = !sceneDay
            val mainColor = if (night) TEXT_LIGHT_MAIN else TEXT_DARK_MAIN
            val subColor = if (night) TEXT_LIGHT_SUB else TEXT_DARK_SUB
            val weakColor = if (night) TEXT_LIGHT_WEAK else TEXT_DARK_WEAK
            views.setTextColor(R.id.widgetTitle, mainColor)
            views.setTextColor(R.id.widgetTemp, mainColor)
            views.setTextColor(R.id.widgetCondition, subColor)
            views.setTextColor(R.id.widgetCity, weakColor)
            views.setTextColor(R.id.widgetStatus, subColor)
            views.setTextColor(R.id.widgetReceived, weakColor)

            // ---------------- 温度 / 天气 / 城市 ----------------
            views.setTextViewText(
                R.id.widgetTemp,
                if (weather.tempC != null) {
                    context.getString(R.string.widget_temp, weather.tempC)
                } else {
                    context.getString(R.string.widget_temp_unknown)
                }
            )

            views.setTextViewText(
                R.id.widgetCondition,
                if (weather.hasData) {
                    weather.condition.label
                } else if (weather.permissionMissing) {
                    context.getString(R.string.widget_weather_no_permission)
                } else {
                    context.getString(R.string.widget_weather_loading)
                }
            )

            views.setTextViewText(R.id.widgetCity, cityLine(context, weather))

            // ---------------- 连接状态 ----------------
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

            // 传输中显示文件名，空闲时显示累计接收数量
            val secondLine = if (snap.transferring && snap.transferName.isNotBlank()) {
                context.getString(R.string.widget_receiving, snap.transferName)
            } else {
                context.getString(R.string.widget_received, snap.receivedCount)
            }
            views.setTextViewText(R.id.widgetReceived, secondLine)

            // ---------------- 点击进入应用 ----------------
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

        /** 城市行：有数据时「城市 · HH:mm」，没数据时给出可操作的提示 */
        private fun cityLine(context: Context, weather: WeatherRepository.Snapshot): String {
            if (!weather.hasData) {
                return if (weather.permissionMissing) {
                    context.getString(R.string.widget_weather_need_permission_short)
                } else {
                    context.getString(R.string.widget_weather_locating)
                }
            }
            val city = weather.city.ifBlank { context.getString(R.string.widget_weather_here) }
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(weather.updatedAt))
            return context.getString(R.string.widget_weather_city_time, city, time)
        }
    }
}
