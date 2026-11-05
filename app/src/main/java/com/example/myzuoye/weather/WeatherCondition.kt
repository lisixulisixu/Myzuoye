package com.example.myzuoye.weather

/**
 * 天气现象分类。
 *
 * 只保留小组件真正需要区分的几档：晴 / 多云 / 阴 / 雾 / 雨 / 雪 / 雷。
 * 每一档在 [com.example.myzuoye.widget.WeatherSceneRenderer] 里对应一套配色与粒子特效，
 * 所以这里不做更细的划分（毛毛雨和暴雨在 4x2 的小卡片上画不出区别，反而徒增分支）。
 */
enum class WeatherCondition(val label: String) {

    /** 晴 */
    SUNNY("晴"),

    /** 多云间晴 */
    PARTLY("多云间晴"),

    /** 多云 */
    CLOUDY("多云"),

    /** 阴 */
    OVERCAST("阴"),

    /** 雾 / 霾 */
    FOG("雾"),

    /** 毛毛雨 / 冻雨 */
    DRIZZLE("小雨"),

    /** 小到中雨 */
    RAIN("雨"),

    /** 阵雨 */
    SHOWER("阵雨"),

    /** 雷阵雨 */
    THUNDER("雷阵雨"),

    /** 雪 */
    SNOW("雪")
}

/**
 * WMO 4677 天气代码 → [WeatherCondition]。
 *
 * Open-Meteo 的 `weather_code` 就是这套编码，单独的 object 便于写单元测试
 * （不依赖任何 Android API，纯 Kotlin）。
 */
object WeatherCodes {

    fun of(code: Int): WeatherCondition = when (code) {
        0 -> WeatherCondition.SUNNY
        1 -> WeatherCondition.PARTLY
        2 -> WeatherCondition.CLOUDY
        3 -> WeatherCondition.OVERCAST

        // 45/48：雾、雾凇
        45, 48 -> WeatherCondition.FOG

        // 51~57：毛毛雨、冻毛毛雨
        51, 53, 55, 56, 57 -> WeatherCondition.DRIZZLE

        // 61~67：小到大雨、冻雨
        61, 63, 65, 66, 67 -> WeatherCondition.RAIN

        // 71~77：小到中雪、雪粒
        71, 73, 75, 77 -> WeatherCondition.SNOW

        // 80~82：阵雨；85/86：阵雪
        80, 81, 82 -> WeatherCondition.SHOWER
        85, 86 -> WeatherCondition.SNOW

        // 95~99：雷暴（96/99 伴冰雹）
        95, 96, 99 -> WeatherCondition.THUNDER

        // 未知代码按多云处理，至少不会画出「晴天」这种明显错误的画面
        else -> WeatherCondition.CLOUDY
    }

    /** 是否属于降水类天气（渲染时决定要不要画云和落到地面的粒子） */
    fun isWet(condition: WeatherCondition): Boolean = when (condition) {
        WeatherCondition.DRIZZLE,
        WeatherCondition.RAIN,
        WeatherCondition.SHOWER,
        WeatherCondition.THUNDER,
        WeatherCondition.SNOW -> true

        else -> false
    }
}
