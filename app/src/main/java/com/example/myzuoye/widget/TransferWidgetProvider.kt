package com.example.myzuoye.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.example.myzuoye.MainActivity
import com.example.myzuoye.R
import com.example.myzuoye.ReceivedFilesActivity
import com.example.myzuoye.util.Formatters
import com.example.myzuoye.weather.WeatherCondition
import com.example.myzuoye.weather.WeatherRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * 桌面小组件：天气场景 + 连接状态 + 传输进度 + 已接收文件数。
 *
 * 更新时机：
 * - 系统按 [android:updatePeriodMillis] 定期回调 [onUpdate]（最小 30 分钟）；
 * - 应用内状态变化（连上/断开、开始/结束传输、收到文件）时主动调用 [refresh]；
 * - 天气数据拉取完成后由 [WeatherRepository] 主动调用 [refresh]；
 * - 点卡片上的城市行（带 ↻）会发一个广播，立刻强制刷新天气，不等 15 分钟节流。
 *
 * 三个关键实现点：
 * 1. **尺寸自适应**：卡片被拖小时隐藏标题/城市/天体，温度字号放大，避免文字被挤没；
 * 2. **部分更新**：天气与昼夜没变时，只提交文字与进度的 RemoteViews，
 *    不再把几百 KB 的场景位图通过 Binder 传一遍（[AppWidgetManager.partiallyUpdateAppWidget]）；
 * 3. **系统深色**：夜间模式下即使当地是白天，也切到夜间调色板，避免半夜被一张亮白卡片闪到。
 */
class TransferWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 桌面回调是天然的「定期心跳」，顺手刷新一次天气（内部有 15 分钟节流）
        WeatherRepository.refreshAsync(context)
        appWidgetIds.forEach { id -> updateOne(context, appWidgetManager, id) }
    }

    /** 用户拖动改变卡片大小：重新按新尺寸渲染场景，避免位图被拉伸糊掉 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        // 尺寸变了，缓存里的场景 key 一定失效，这里清掉保证走完整渲染
        renderPrefs(context).edit().remove(sceneKeyOf(appWidgetId)).apply()
        updateOne(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_WEATHER) {
            // 用户点了城市行：跳过节流强制刷新，卡片上会显示新的时间
            WeatherRepository.refreshAsync(context, force = true)
            refresh(context)
        }
    }

    companion object {
        /** 点城市行触发的手动刷新 */
        const val ACTION_REFRESH_WEATHER = "com.example.myzuoye.widget.REFRESH_WEATHER"

        /** 已连接：绿色 */
        private const val COLOR_ONLINE = 0xFF34C759.toInt()

        /** 未连接：浅灰 */
        private const val COLOR_OFFLINE = 0xFFB0B8C4.toInt()

        /** 传输中：主题紫 */
        private const val COLOR_TRANSFER = 0xFFA855F7.toInt()

        /** 日间文字：深墨蓝；夜间文字：近白 */
        private const val TEXT_DARK_MAIN = 0xFF22303F.toInt()
        // 白天文字压在浅色天空和太阳光晕上，颜色要比「看起来好看」更深一档才够对比
        private const val TEXT_DARK_SUB = 0xFF3C4757.toInt()
        private const val TEXT_DARK_WEAK = 0xFF4E5A6C.toInt()

        private const val TEXT_LIGHT_MAIN = 0xFFFFFFFF.toInt()
        private const val TEXT_LIGHT_SUB = 0xFFD3DDEE.toInt()
        private const val TEXT_LIGHT_WEAK = 0xFFB9C6DC.toInt()

        /** 天体（太阳/月亮）图层边长，单位 dp，与 widget_transfer.xml 里的控件尺寸一致 */
        private const val CELESTIAL_SIZE_DP = 100

        /** 默认卡片尺寸（dp），与 widget_transfer_info.xml 的 minWidth/minHeight 对应 */
        private const val DEFAULT_WIDTH_DP = 250
        private const val DEFAULT_HEIGHT_DP = 110

        /** 小于这个尺寸就进紧凑模式：藏起标题与城市，把空间留给温度和状态 */
        private const val COMPACT_WIDTH_DP = 210
        private const val COMPACT_HEIGHT_DP = 96

        /** 两个方向都小于这个值 → 1×1 极简卡（只留温度与天气图标） */
        private const val TINY_MAX_DP = 110

        /** 够大就上 4×4 大卡：逐小时曲线 + 天文信息 + 快捷按钮 */
        private const val LARGE_MIN_WIDTH_DP = 250
        private const val LARGE_MIN_HEIGHT_DP = 200

        /** 画面变体计数（伪动效，见 WeatherSceneRenderer.renderBackground 的 variant 参数） */
        private const val KEY_RENDER_VARIANT = "render_variant"

        private const val RENDER_PREFS = "widget_render"

        private fun renderPrefs(context: Context) =
            context.applicationContext.getSharedPreferences(RENDER_PREFS, Context.MODE_PRIVATE)

        private fun sceneKeyOf(id: Int) = "scene_$id"

        /** 主动刷新所有已放置的小组件实例 */
        fun refresh(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(
                ComponentName(appContext, TransferWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            ids.forEach { id -> updateOne(appContext, manager, id) }
        }

        private fun updateOne(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int
        ) {
            val model = buildModel(context, manager, widgetId)
            val prefs = renderPrefs(context)
            val key = sceneKeyOf(widgetId)
            val last = prefs.getString(key, null)

            if (last == model.sceneKey) {
                // 场景没变：只提交文字/进度，避免整张位图再走一次 Binder
                manager.partiallyUpdateAppWidget(widgetId, buildViews(context, model, full = false))
            } else {
                manager.updateAppWidget(widgetId, buildViews(context, model, full = true))
                prefs.edit()
                    .putString(key, model.sceneKey)
                    // 换下一帧画面变体：下次完整刷新时云会长得不一样
                    .putInt(KEY_RENDER_VARIANT, (model.sceneVariant + 1) % 4)
                    .apply()
            }
        }

        // -------------------------------------------------------------- 数据

        /** 卡片三档形态：1×1 极简、4×2 标准、4×4 大卡 */
        internal enum class CardSize { TINY, STANDARD, LARGE }

        internal data class Model(
            val compact: Boolean,
            val size: CardSize,
            val condition: WeatherCondition,
            val sceneDay: Boolean,
            val sceneVariant: Int,
            val mainColor: Int,
            val subColor: Int,
            val weakColor: Int,
            val widthPx: Int,
            val heightPx: Int,
            val sceneKey: String,
            val tempText: String,
            val conditionText: String,
            val cityText: String,
            val statusText: String,
            val dotColor: Int,
            val secondLine: String,
            /** 大卡第二行细节：日出日落 / 紫外线 / 体感 */
            val detailText: String,
            val transferring: Boolean,
            val progress: Int,
            val showTitle: Boolean,
            val showCity: Boolean,
            val celestialSizePx: Int,
            /** 逐小时温度，大卡上画曲线用；不足两个点就隐藏图表 */
            val hourlyTemps: List<Int> = emptyList(),
            val hourlyStartHour: Int = 0
        )

        internal fun buildModel(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int
        ): Model {
            val options = runCatching { manager.getAppWidgetOptions(widgetId) }.getOrNull()
            val widthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_WIDTH_DP)
                ?: DEFAULT_WIDTH_DP
            val heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, DEFAULT_HEIGHT_DP)
                ?: DEFAULT_HEIGHT_DP
            return buildModel(context, widthDp, heightDp)
        }

        /**
         * 按给定尺寸构建视图模型。
         *
         * 单独拆出来是为了能测：桌面的真实尺寸来自 [AppWidgetManager.getAppWidgetOptions]，
         * 单元/仪器测试里没有真实卡片，只能直接给宽高来验证紧凑模式。
         */
        internal fun buildModel(
            context: Context,
            widthDp: Int,
            heightDp: Int
        ): Model {
            val snap = WidgetState.snapshot(context)
            val weather = WeatherRepository.cached(context)
            val density = context.resources.displayMetrics.density
            val widthPx = (widthDp * density).toInt().coerceAtLeast(200)
            val heightPx = (heightDp * density).toInt().coerceAtLeast(120)
            val compact = widthDp < COMPACT_WIDTH_DP || heightDp < COMPACT_HEIGHT_DP
            val size = when {
                widthDp < TINY_MAX_DP || heightDp < TINY_MAX_DP -> CardSize.TINY
                widthDp >= LARGE_MIN_WIDTH_DP && heightDp >= LARGE_MIN_HEIGHT_DP -> CardSize.LARGE
                else -> CardSize.STANDARD
            }

            // 系统深色时按夜间调色板画，哪怕当地是白天
            val systemNight = isSystemNight(context)
            val sceneDay = if (weather.hasData) weather.isDay && !systemNight else !systemNight
            val night = !sceneDay

            val mainColor = if (night) TEXT_LIGHT_MAIN else TEXT_DARK_MAIN
            val subColor = if (night) TEXT_LIGHT_SUB else TEXT_DARK_SUB
            val weakColor = if (night) TEXT_LIGHT_WEAK else TEXT_DARK_WEAK

            // 温度
            val tempText = weather.tempC?.let { context.getString(R.string.widget_temp, it) }
                ?: context.getString(R.string.widget_temp_unknown)

            // 天气 + 今日高低温 + （降水概率高时）降水提示
            val conditionText = when {
                weather.hasData -> {
                    val label = weather.condition.label
                    val high = weather.tempMaxC
                    val low = weather.tempMinC
                    buildString {
                        if (high != null && low != null) {
                            append(context.getString(R.string.widget_condition_detail, label, high, low))
                        } else {
                            append(label)
                        }
                        val precip = weather.precipProb
                        if (precip != null && precip >= 50 && !compact) {
                            append(" · ")
                            append(context.getString(R.string.widget_precip, precip))
                        }
                    }
                }

                weather.permissionMissing -> context.getString(R.string.widget_weather_no_permission)
                else -> context.getString(R.string.widget_weather_loading)
            }

            // 城市 + 时间 + 刷新记号（点了就刷新天气）
            val cityText = cityLine(context, weather)

            // 连接状态：传输中 > 已连接（带对端名）> 等待连接
            val statusText: String
            val dotColor: Int
            when {
                snap.transferring -> {
                    statusText = context.getString(
                        R.string.widget_status_receiving_short, snap.progress
                    )
                    dotColor = COLOR_TRANSFER
                }

                snap.connected -> {
                    statusText = if (snap.peer.isNotBlank()) {
                        context.getString(R.string.widget_status_connected_peer, snap.peer)
                    } else {
                        context.getString(R.string.widget_status_connected)
                    }
                    dotColor = COLOR_ONLINE
                }

                else -> {
                    statusText = context.getString(R.string.widget_status_disconnected)
                    dotColor = COLOR_OFFLINE
                }
            }

            val secondLine = if (snap.transferring && snap.transferName.isNotBlank()) {
                buildString {
                    append(snap.transferName)
                    if (snap.speed > 0) {
                        append(" · ").append(Formatters.speed(snap.speed.toLong()))
                        if (snap.etaSeconds > 0) {
                            append(" · 剩 ").append(Formatters.duration(snap.etaSeconds.toLong()))
                        }
                    }
                }
            } else {
                context.getString(R.string.widget_received, snap.receivedCount)
            }

            // 大卡的第二行细节：日出日落 / 紫外线 / 体感，没有数据就不占地方
            val detailText = buildString {
                if (weather.sunrise.isNotBlank() && weather.sunset.isNotBlank()) {
                    append(context.getString(R.string.widget_sun, weather.sunrise, weather.sunset))
                }
                weather.uvIndex?.let {
                    if (isNotEmpty()) append(" · ")
                    append(context.getString(R.string.widget_uv, it))
                }
                weather.apparentC?.let {
                    if (isNotEmpty()) append(" · ")
                    append(context.getString(R.string.widget_feels, it))
                }
            }

            // 天体大小跟着卡片走：2×2 卡片上给 56dp，4×2 封顶 100dp，
            // 避免小卡片里半颗太阳占掉一半宽度
            val celestialSizePx = (min(widthDp, heightDp) * density * 0.52f)
                .coerceIn(56f * density, CELESTIAL_SIZE_DP * density)
                .toInt()

            // 每次完整刷新换一帧画面变体（云的位置不同），让卡片不像一张死图。
            // 只在真正重绘时递增，部分更新不会动它。
            val variant = renderPrefs(context).getInt(KEY_RENDER_VARIANT, 0)

            val sceneKey = buildString {
                append(weather.condition.name).append('_').append(sceneDay)
                append('_').append(widthPx).append('x').append(heightPx)
                append('_').append(compact)
                append("_v").append(variant)
            }

            return Model(
                compact = compact,
                size = size,
                condition = weather.condition,
                sceneDay = sceneDay,
                sceneVariant = variant,
                mainColor = mainColor,
                subColor = subColor,
                weakColor = weakColor,
                widthPx = widthPx,
                heightPx = heightPx,
                sceneKey = sceneKey,
                tempText = tempText,
                conditionText = conditionText,
                cityText = cityText,
                statusText = statusText,
                dotColor = dotColor,
                secondLine = secondLine,
                detailText = detailText,
                transferring = snap.transferring,
                progress = snap.progress,
                showTitle = !compact,
                showCity = !compact && heightDp >= 100,
                celestialSizePx = celestialSizePx,
                hourlyTemps = weather.hourlyTemps,
                hourlyStartHour = weather.hourlyStartHour
            )
        }

        private fun isSystemNight(context: Context): Boolean {
            val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return mode == Configuration.UI_MODE_NIGHT_YES
        }

        // -------------------------------------------------------------- 渲染

        /**
         * @param full true 时设置场景位图（整张卡片重建）；
         *             false 时只提交文字/进度/可见性（部分更新）。
         *
         * 三种形态各用一套布局：1×1 只留温度和天气，4×2 是常规卡，
         * 4×4 才摊开逐小时曲线和快捷按钮——小卡片塞不下就别硬塞。
         */
        internal fun buildViews(context: Context, model: Model, full: Boolean): RemoteViews =
            when (model.size) {
                CardSize.TINY -> buildTiny(context, model, full)
                CardSize.LARGE -> buildLarge(context, model, full)
                CardSize.STANDARD -> buildStandard(context, model, full)
            }

        /** 场景位图 + 天体层，三套布局共用 */
        private fun applyScene(
            views: RemoteViews,
            model: Model,
            full: Boolean,
            showCelestial: Boolean
        ) {
            if (!full) return
            views.setImageViewBitmap(
                R.id.widgetScene,
                WeatherSceneRenderer.renderBackground(
                    model.widthPx, model.heightPx, model.condition, model.sceneDay,
                    model.sceneVariant
                )
            )
            val celestial = if (!showCelestial) {
                null
            } else {
                WeatherSceneRenderer.renderCelestial(
                    model.celestialSizePx, model.condition, model.sceneDay
                )
            }
            if (celestial != null) {
                views.setViewVisibility(R.id.widgetCelestial, View.VISIBLE)
                views.setImageViewBitmap(R.id.widgetCelestial, celestial)
            } else {
                views.setViewVisibility(R.id.widgetCelestial, View.GONE)
            }
        }

        /** 4×2 常规卡 */
        private fun buildStandard(context: Context, model: Model, full: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_transfer)
            // 紧凑模式不放天体：2×2 的卡片上一颗太阳会把文字挤没
            applyScene(views, model, full, showCelestial = !model.compact)

            views.setViewVisibility(
                R.id.widgetTitle, if (model.showTitle) View.VISIBLE else View.GONE
            )
            views.setTextViewText(R.id.widgetTitle, context.getString(R.string.widget_title))
            views.setTextColor(R.id.widgetTitle, model.mainColor)

            views.setTextViewText(R.id.widgetTemp, model.tempText)
            views.setTextColor(R.id.widgetTemp, model.mainColor)
            // 紧凑模式温度放大，一眼能看到
            views.setTextViewTextSize(
                R.id.widgetTemp,
                TypedValue.COMPLEX_UNIT_SP,
                if (model.compact) 26f else 22f
            )

            views.setTextViewText(R.id.widgetCondition, model.conditionText)
            views.setTextColor(R.id.widgetCondition, model.subColor)

            views.setViewVisibility(
                R.id.widgetCity, if (model.showCity) View.VISIBLE else View.GONE
            )
            views.setTextViewText(R.id.widgetCity, model.cityText)
            views.setTextColor(R.id.widgetCity, model.weakColor)

            views.setTextViewText(R.id.widgetStatus, model.statusText)
            views.setTextColor(R.id.widgetStatus, model.subColor)
            views.setTextColor(R.id.widgetReceived, model.weakColor)
            views.setTextViewText(R.id.widgetReceived, model.secondLine)
            views.setInt(R.id.widgetDot, "setColorFilter", model.dotColor)

            setProgress(views, model)

            // 整张卡片进应用；状态行直接进「已收文件」；城市行刷新天气
            views.setOnClickPendingIntent(R.id.widgetRoot, activityIntent(context, 1, null))
            views.setOnClickPendingIntent(
                R.id.widgetStatusRow,
                activityIntent(context, 2, ReceivedFilesActivity::class.java)
            )
            views.setOnClickPendingIntent(R.id.widgetCity, refreshIntent(context))
            return views
        }

        /** 4×4 大卡：温度 + 逐小时曲线 + 天文信息 + 三个快捷按钮 */
        private fun buildLarge(context: Context, model: Model, full: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_transfer_large)
            applyScene(views, model, full, showCelestial = true)

            views.setTextViewText(R.id.widgetTitle, context.getString(R.string.widget_title))
            views.setTextColor(R.id.widgetTitle, model.mainColor)

            views.setTextViewText(R.id.widgetTemp, model.tempText)
            views.setTextColor(R.id.widgetTemp, model.mainColor)

            views.setTextViewText(R.id.widgetCondition, model.conditionText)
            views.setTextColor(R.id.widgetCondition, model.subColor)

            views.setTextViewText(R.id.widgetCity, model.cityText)
            views.setTextColor(R.id.widgetCity, model.weakColor)

            views.setTextViewText(R.id.widgetDetail, model.detailText)
            views.setTextColor(R.id.widgetDetail, model.weakColor)
            views.setViewVisibility(
                R.id.widgetDetail, if (model.detailText.isBlank()) View.GONE else View.VISIBLE
            )

            // 逐小时曲线：数据不够就整块隐藏，不留一个空白条
            if (full) {
                val density = context.resources.displayMetrics.density
                val chartW = (model.widthPx - 26f * density).toInt()
                val chartH = (model.heightPx * 0.30f)
                    .toInt()
                    .coerceIn((54f * density).toInt(), (104f * density).toInt())
                val chart = HourlyChartRenderer.render(
                    widthPx = chartW,
                    heightPx = chartH,
                    temps = model.hourlyTemps,
                    startHour = model.hourlyStartHour,
                    lineColor = model.mainColor,
                    textColor = model.weakColor,
                    dotColor = model.mainColor
                )
                if (chart != null) {
                    views.setViewVisibility(R.id.widgetChart, View.VISIBLE)
                    views.setImageViewBitmap(R.id.widgetChart, chart)
                } else {
                    views.setViewVisibility(R.id.widgetChart, View.GONE)
                }
            }

            views.setTextViewText(R.id.widgetStatus, model.statusText)
            views.setTextColor(R.id.widgetStatus, model.subColor)
            views.setTextViewText(R.id.widgetReceived, model.secondLine)
            views.setTextColor(R.id.widgetReceived, model.weakColor)
            views.setInt(R.id.widgetDot, "setColorFilter", model.dotColor)

            setProgress(views, model)

            // 快捷按钮：刷新天气 / 已收文件 / 打开应用
            views.setOnClickPendingIntent(R.id.widgetBtnRefresh, refreshIntent(context, 3))
            views.setOnClickPendingIntent(
                R.id.widgetBtnFiles,
                activityIntent(context, 4, ReceivedFilesActivity::class.java)
            )
            views.setOnClickPendingIntent(R.id.widgetBtnOpen, activityIntent(context, 5, null))
            views.setOnClickPendingIntent(R.id.widgetRoot, activityIntent(context, 1, null))
            return views
        }

        /** 1×1 极简卡：只有温度和天气 */
        private fun buildTiny(context: Context, model: Model, full: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_transfer_tiny)
            if (full) {
                views.setImageViewBitmap(
                    R.id.widgetScene,
                    WeatherSceneRenderer.renderBackground(
                        model.widthPx, model.heightPx, model.condition, model.sceneDay,
                        model.sceneVariant
                    )
                )
            }
            views.setTextViewText(R.id.widgetTemp, model.tempText)
            views.setTextColor(R.id.widgetTemp, model.mainColor)
            views.setTextViewText(R.id.widgetCondition, model.condition.label)
            views.setTextColor(R.id.widgetCondition, model.subColor)
            // 小卡也带上状态点：连着的时候一眼能看出来
            views.setInt(R.id.widgetDot, "setColorFilter", model.dotColor)
            views.setOnClickPendingIntent(R.id.widgetRoot, activityIntent(context, 1, null))
            return views
        }

        private fun setProgress(views: RemoteViews, model: Model) {
            if (model.transferring) {
                views.setViewVisibility(R.id.widgetProgress, View.VISIBLE)
                views.setProgressBar(R.id.widgetProgress, 100, model.progress, false)
            } else {
                views.setViewVisibility(R.id.widgetProgress, View.GONE)
            }
        }

        private fun activityIntent(context: Context, requestCode: Int, target: Class<*>?): PendingIntent {
            val intent = Intent(context, target ?: MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun refreshIntent(context: Context, requestCode: Int = 3): PendingIntent {
            val intent = Intent(context, TransferWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_WEATHER
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** 城市行：有数据时「城市 · HH:mm ↻」，↻ 提示点一下可以刷新 */
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
            return context.getString(R.string.widget_weather_city_time, city, time) + " ↻"
        }
    }
}
