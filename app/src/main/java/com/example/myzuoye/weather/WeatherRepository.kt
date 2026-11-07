package com.example.myzuoye.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.myzuoye.widget.TransferWidgetProvider
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 天气数据仓库：定位 → 调接口 → 落 SharedPreferences → 刷新桌面小组件。
 *
 * 为什么走 SharedPreferences 而不是内存单例：
 * 小组件由桌面进程渲染，读不到本进程的内存状态，必须把结果落盘成纯标量。
 *
 * 数据源（都不需要 API Key）：
 * - 天气：[Open-Meteo](https://open-meteo.com/) `current=temperature_2m,weather_code,is_day`
 * - 城市名：BigDataCloud 的反向地理编码；失败就退回上一次的城市名或「当前位置」
 *
 * 网络失败不会清空旧数据，卡片继续显示上一次的结果 + 旧时间戳，
 * 避免弱网时小组件突然变成「--°」。
 */
object WeatherRepository {

    private const val TAG = "WeatherRepository"

    private const val PREFS = "weather_state"
    private const val KEY_TEMP = "temp"
    private const val KEY_CODE = "code"
    private const val KEY_CITY = "city"
    private const val KEY_IS_DAY = "is_day"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_NO_PERMISSION = "no_permission"
    private const val KEY_ERROR = "error"

    // 以下是为了让小组件一屏能提供更多信息（体感、今日高低温、降水概率），
    // 与主温度同一次请求返回，不额外发网络请求
    private const val KEY_APPARENT = "apparent"
    private const val KEY_TEMP_MAX = "temp_max"
    private const val KEY_TEMP_MIN = "temp_min"
    private const val KEY_PRECIP = "precip"
    private const val KEY_HOURLY = "hourly_temps"
    private const val KEY_HOURLY_START = "hourly_start_hour"
    private const val KEY_SUNRISE = "sunrise"
    private const val KEY_SUNSET = "sunset"
    private const val KEY_UV = "uv_index"

    /** 逐小时曲线要画多少个点：12 个足够看出趋势，再多字就挤成一团 */
    private const val HOURLY_COUNT = 12

    /** 「没取到温度」的哨兵值（SharedPreferences 没有 putNull） */
    private const val TEMP_UNKNOWN = Int.MIN_VALUE

    /** 两次网络请求的最小间隔：小组件 30 分钟刷一次，应用内也按这个节流 */
    private const val MIN_INTERVAL_MS = 15 * 60 * 1000L

    /** 超过这个时长的定位视为过期，需要重新取一次 */
    private const val FRESH_FIX_MS = 30 * 60 * 1000L

    private const val HTTP_TIMEOUT_MS = 6000

    data class Snapshot(
        val tempC: Int?,
        val condition: WeatherCondition,
        val city: String,
        val isDay: Boolean,
        val updatedAt: Long,
        val permissionMissing: Boolean,
        val error: String?,
        /** 体感温度；接口没给时为 null */
        val apparentC: Int? = null,
        /** 今日最高 / 最低温 */
        val tempMaxC: Int? = null,
        val tempMinC: Int? = null,
        /** 今日最大降水概率（0..100） */
        val precipProb: Int? = null,
        /**
         * 未来 12 小时逐小时温度（摄氏），从 [hourlyStart] 那小时开始。
         *
         * 放在快照里而不是单独接口，是因为小组件的逐小时曲线要和当前温湿度同源，
         * 否则会出现「现在 12°C，曲线第一个点却是 9°C」这种自相矛盾。
         */
        val hourlyTemps: List<Int> = emptyList(),
        /** 逐小时数据的起始小时（0..23），用于标注刻度 */
        val hourlyStartHour: Int = 0,
        /** 日出 / 日落，形如 "06:12"；接口没给时为空串 */
        val sunrise: String = "",
        val sunset: String = "",
        /** 今日紫外线指数（越大越晒） */
        val uvIndex: Int? = null
    ) {
        val hasData: Boolean get() = tempC != null

        /** 是否已经太旧（超过 3 小时），小组件上会把时间标灰提示 */
        val stale: Boolean
            get() = hasData && System.currentTimeMillis() - updatedAt > 3 * 60 * 60 * 1000L
    }

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "weather-io").apply { isDaemon = true }
    }

    /** 回调线程不能复用单线程 io（会和定位回调互相阻塞），单独一个池 */
    private val callbackExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "weather-location-cb").apply { isDaemon = true }
    }

    @Volatile
    private var running = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 读缓存

    fun cached(context: Context): Snapshot {
        val p = prefs(context)
        val temp = p.getInt(KEY_TEMP, TEMP_UNKNOWN)
        return Snapshot(
            tempC = if (temp == TEMP_UNKNOWN) null else temp,
            condition = WeatherCodes.of(p.getInt(KEY_CODE, 3)),
            city = p.getString(KEY_CITY, "").orEmpty(),
            isDay = p.getBoolean(KEY_IS_DAY, true),
            updatedAt = p.getLong(KEY_UPDATED_AT, 0L),
            permissionMissing = p.getBoolean(KEY_NO_PERMISSION, false),
            error = p.getString(KEY_ERROR, null),
            apparentC = p.getInt(KEY_APPARENT, TEMP_UNKNOWN).let { if (it == TEMP_UNKNOWN) null else it },
            tempMaxC = p.getInt(KEY_TEMP_MAX, TEMP_UNKNOWN).let { if (it == TEMP_UNKNOWN) null else it },
            tempMinC = p.getInt(KEY_TEMP_MIN, TEMP_UNKNOWN).let { if (it == TEMP_UNKNOWN) null else it },
            precipProb = p.getInt(KEY_PRECIP, TEMP_UNKNOWN).let { if (it == TEMP_UNKNOWN) null else it },
            hourlyTemps = p.getString(KEY_HOURLY, "").orEmpty()
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() },
            hourlyStartHour = p.getInt(KEY_HOURLY_START, 0),
            sunrise = p.getString(KEY_SUNRISE, "").orEmpty(),
            sunset = p.getString(KEY_SUNSET, "").orEmpty(),
            uvIndex = p.getInt(KEY_UV, TEMP_UNKNOWN).let { if (it == TEMP_UNKNOWN) null else it }
        )
    }

    private fun save(context: Context, snapshot: Snapshot) {
        prefs(context).edit()
            .putInt(KEY_TEMP, snapshot.tempC ?: TEMP_UNKNOWN)
            .putInt(KEY_CODE, snapshot.condition.ordinalCode())
            .putString(KEY_CITY, snapshot.city)
            .putBoolean(KEY_IS_DAY, snapshot.isDay)
            .putLong(KEY_UPDATED_AT, snapshot.updatedAt)
            .putBoolean(KEY_NO_PERMISSION, snapshot.permissionMissing)
            .putString(KEY_ERROR, snapshot.error)
            .putInt(KEY_APPARENT, snapshot.apparentC ?: TEMP_UNKNOWN)
            .putInt(KEY_TEMP_MAX, snapshot.tempMaxC ?: TEMP_UNKNOWN)
            .putInt(KEY_TEMP_MIN, snapshot.tempMinC ?: TEMP_UNKNOWN)
            .putInt(KEY_PRECIP, snapshot.precipProb ?: TEMP_UNKNOWN)
            .putString(KEY_HOURLY, snapshot.hourlyTemps.joinToString(","))
            .putInt(KEY_HOURLY_START, snapshot.hourlyStartHour)
            .putString(KEY_SUNRISE, snapshot.sunrise)
            .putString(KEY_SUNSET, snapshot.sunset)
            .putInt(KEY_UV, snapshot.uvIndex ?: TEMP_UNKNOWN)
            .apply()
    }

    /**
     * 保存时存原始天气代码而不是枚举序号，避免以后往枚举里插值导致历史缓存串味。
     * 这里反过来把枚举映射成一个稳定的代码：直接取 WMO 的代表值。
     */
    private fun WeatherCondition.ordinalCode(): Int = when (this) {
        WeatherCondition.SUNNY -> 0
        WeatherCondition.PARTLY -> 1
        WeatherCondition.CLOUDY -> 2
        WeatherCondition.OVERCAST -> 3
        WeatherCondition.FOG -> 45
        WeatherCondition.DRIZZLE -> 51
        WeatherCondition.RAIN -> 63
        WeatherCondition.SHOWER -> 80
        WeatherCondition.THUNDER -> 95
        WeatherCondition.SNOW -> 71
    }

    // ------------------------------------------------------------------ 刷新

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * 异步刷新天气。
     *
     * [force] = true 时跳过时间节流（用户刚授权定位、或刚从应用内触发）。
     * 有请求在跑时直接返回，避免桌面每 30 分钟回调 + 应用前台叠加成并发请求。
     */
    fun refreshAsync(context: Context, force: Boolean = false) {
        val app = context.applicationContext

        if (!hasLocationPermission(app)) {
            // 没权限时不要把旧数据抹掉，只标记状态让卡片显示提示文案
            val old = cached(app)
            if (!old.permissionMissing) {
                save(app, old.copy(permissionMissing = true, error = null))
                TransferWidgetProvider.refresh(app)
            }
            return
        }

        if (!force) {
            val old = cached(app)
            val fresh = old.hasData && System.currentTimeMillis() - old.updatedAt < MIN_INTERVAL_MS
            if (fresh && !old.permissionMissing) return
        }

        if (running) return
        running = true

        io.execute {
            try {
                fetch(app)
            } catch (t: Throwable) {
                Log.w(TAG, "刷新天气失败", t)
                val old = cached(app)
                save(app, old.copy(error = t.message ?: "网络错误"))
            } finally {
                running = false
                TransferWidgetProvider.refresh(app)
            }
        }
    }

    private fun fetch(context: Context) {
        val location = locate(context)
        if (location == null) {
            val old = cached(context)
            save(
                context,
                old.copy(
                    permissionMissing = false,
                    error = "无法获取定位（请确认系统定位开关已打开）"
                )
            )
            return
        }

        val result = requestWeather(location.latitude, location.longitude)
        if (result == null) {
            val old = cached(context)
            save(context, old.copy(permissionMissing = false, error = "天气接口请求失败"))
            return
        }

        val old = cached(context)
        // 城市名变化很慢，6 小时内不重复请求反查接口，省一次网络
        val city = if (old.city.isNotBlank() &&
            System.currentTimeMillis() - old.updatedAt < 6 * 60 * 60 * 1000L
        ) {
            old.city
        } else {
            reverseGeocode(location.latitude, location.longitude) ?: old.city.ifBlank { "当前位置" }
        }

        save(
            context,
            Snapshot(
                tempC = result.tempC,
                condition = result.condition,
                city = city,
                isDay = result.isDay,
                updatedAt = System.currentTimeMillis(),
                permissionMissing = false,
                error = null,
                apparentC = result.apparentC,
                tempMaxC = result.tempMaxC,
                tempMinC = result.tempMinC,
                precipProb = result.precipProb,
                hourlyTemps = result.hourlyTemps,
                hourlyStartHour = result.hourlyStartHour,
                sunrise = result.sunrise,
                sunset = result.sunset,
                uvIndex = result.uvIndex
            )
        )
    }

    // ------------------------------------------------------------------ 定位

    @SuppressLint("MissingPermission")
    private fun locate(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        var best: Location? = null
        for (provider in listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )) {
            val candidate = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || candidate.time > best.time) best = candidate
        }

        // 缓存定位够新就直接用：电池友好，也避免弱信号下干等
        if (best != null && System.currentTimeMillis() - best.time < FRESH_FIX_MS) return best

        val fresh = runCatching { requestSingleFix(lm) }.getOrNull()
        return fresh ?: best
    }

    @SuppressLint("MissingPermission")
    private fun requestSingleFix(lm: LocationManager): Location? {
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null

        val latch = CountDownLatch(1)
        // 回调在别的线程写、本线程读，用单元素数组代替 @Volatile 局部变量（Kotlin 不允许注解局部 var）
        val holder = arrayOfNulls<Location>(1)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cancel = CancellationSignal()
            lm.getCurrentLocation(provider, cancel, callbackExecutor) { location ->
                holder[0] = location
                latch.countDown()
            }
        } else {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, { location ->
                holder[0] = location
                latch.countDown()
            }, Looper.getMainLooper())
        }

        latch.await(5, TimeUnit.SECONDS)
        return holder[0]
    }

    // ------------------------------------------------------------------ 网络

    private class WeatherResult(
        val tempC: Int,
        val condition: WeatherCondition,
        val isDay: Boolean,
        val apparentC: Int?,
        val tempMaxC: Int?,
        val tempMinC: Int?,
        val precipProb: Int?,
        val hourlyTemps: List<Int> = emptyList(),
        val hourlyStartHour: Int = 0,
        val sunrise: String = "",
        val sunset: String = "",
        val uvIndex: Int? = null
    )

    private fun requestWeather(lat: Double, lon: Double): WeatherResult? {
        // 一次请求把实况、今日概况、往后 12 小时的温度、日出日落一起拿回来。
        // 都是同一个接口的不同字段，多要几个不会多一次网络往返。
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=%.4f&longitude=%.4f".format(lat, lon) +
            "&current=temperature_2m,apparent_temperature,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max," +
            "sunrise,sunset,uv_index_max" +
            "&hourly=temperature_2m" +
            // 2 天是为了让「夜里 23 点」也能凑满往后 12 小时，跨零点不用再请求一次
            "&timezone=auto&forecast_days=2"
        val body = httpGet(url) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val current = json.optJSONObject("current") ?: return null
        val temp = current.optDouble("temperature_2m", Double.NaN)
        if (temp.isNaN()) return null

        // daily 是数组，取下标 0（今天）
        val daily = json.optJSONObject("daily")
        fun firstOf(name: String): Double? {
            val arr = daily?.optJSONArray(name) ?: return null
            if (arr.length() == 0) return null
            val v = arr.optDouble(0, Double.NaN)
            return if (v.isNaN()) null else v
        }
        fun firstStringOf(name: String): String {
            val arr = daily?.optJSONArray(name) ?: return ""
            if (arr.length() == 0) return ""
            // "2026-11-07T06:12" → "06:12"
            return arr.optString(0).substringAfter('T').take(5)
        }

        // 逐小时：从「当前这个整点」开始取 12 个，ISO 字符串直接比较即可（格式统一）
        val hourly = json.optJSONObject("hourly")
        val hours = hourly?.optJSONArray("time")
        val temps = hourly?.optJSONArray("temperature_2m")
        val nowIso = current.optString("time")
        var start = 0
        if (hours != null && nowIso.isNotEmpty()) {
            for (i in 0 until hours.length()) {
                if (hours.optString(i) >= nowIso) {
                    start = i
                    break
                }
            }
        }
        val hourlyTemps = mutableListOf<Int>()
        if (hours != null && temps != null) {
            val end = minOf(start + HOURLY_COUNT, temps.length(), hours.length())
            for (i in start until end) {
                val v = temps.optDouble(i, Double.NaN)
                // 遇到空洞就停：宁可少画一段，也不要让曲线和小时间隔错位
                if (v.isNaN()) break
                hourlyTemps += Math.round(v).toInt()
            }
        }
        val startHour = hours?.optString(start)?.substringAfter('T')?.take(2)?.toIntOrNull() ?: 0

        return WeatherResult(
            tempC = Math.round(temp).toInt(),
            condition = WeatherCodes.of(current.optInt("weather_code", 3)),
            isDay = current.optInt("is_day", 1) == 1,
            apparentC = current.optDouble("apparent_temperature", Double.NaN)
                .takeIf { !it.isNaN() }?.let { Math.round(it).toInt() },
            tempMaxC = firstOf("temperature_2m_max")?.let { Math.round(it).toInt() },
            tempMinC = firstOf("temperature_2m_min")?.let { Math.round(it).toInt() },
            precipProb = firstOf("precipitation_probability_max")?.let {
                Math.round(it).toInt().coerceIn(0, 100)
            },
            hourlyTemps = hourlyTemps,
            hourlyStartHour = startHour,
            sunrise = firstStringOf("sunrise"),
            sunset = firstStringOf("sunset"),
            uvIndex = firstOf("uv_index_max")?.let { Math.round(it).toInt().coerceIn(0, 15) }
        )
    }

    private fun reverseGeocode(lat: Double, lon: Double): String? {
        // localityLanguage 必须写 zh-Hans：只写 zh 时接口会返回繁体（"聖荷西"），
        // 和界面上的简体文案不搭
        val url = "https://api.bigdatacloud.net/data/reverse-geocode-client" +
            "?latitude=%.4f&longitude=%.4f&localityLanguage=zh-Hans".format(lat, lon)
        val body = httpGet(url) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return sequenceOf("city", "locality", "principalSubdivision")
            .map { json.optString(it).trim() }
            .firstOrNull { it.isNotEmpty() }
    }

    private fun httpGet(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Myzuoye-Widget/1.0 (Android)")
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${connection.responseCode} for $url")
                null
            } else {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "请求失败：$url", t)
            null
        } finally {
            connection?.disconnect()
        }
    }
}
