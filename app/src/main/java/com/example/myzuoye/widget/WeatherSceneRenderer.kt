package com.example.myzuoye.widget

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import com.example.myzuoye.weather.WeatherCondition
import com.example.myzuoye.weather.WeatherCodes
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 桌面小组件的天气场景渲染器。
 *
 * RemoteViews 能动画的控件极其有限（没有 ValueAnimator、没有自定义 View），
 * 所以这里走「离屏把整张卡片画成 Bitmap，再交给 ImageView 显示」的路线：
 * 一次绘制就能堆出太阳光晕、云层、雨丝、雪花、闪电、星空这些元素，
 * 比用几个纯色 View 拼贴效果好得多，也不额外耗电。
 *
 * 几种天气各自的画面：
 * - 晴    : 暖色天空 + 太阳光晕 + 旋转光芒 + 闪烁星点
 * - 多云  : 淡蓝天空 + 太阳半掩 + 大片柔和云层
 * - 阴/雾 : 灰蓝天空 + 厚重云层 / 横向雾带
 * - 雨    : 冷色天空 + 低垂云 + 斜向雨丝 + 地面溅点
 * - 雷雨  : 雨 + 云下闪电（带外发光）
 * - 雪    : 冷白天空 + 大小不一的雪花颗粒
 * - 夜间  : 深蓝渐变 + 月亮 / 星空，文字会自动切成浅色（由 Provider 设置）
 *
 * 统一使用浅色系的日间配色，保证深色文字始终可读；夜间切换为深色底 + 浅色文字。
 */
object WeatherSceneRenderer {

    /** 渲染宽度上限：RemoteViews 走 Binder 传输，位图太大可能触发事务超限 */
    private const val MAX_WIDTH_PX = 480

    /** 像素总量上限，配合上面的宽度限制一起兜底 */
    private const val MAX_PIXELS = 220_000

    /** 只缓存最近几张（不同尺寸 / 昼夜 / 天气），避免反复重绘 */
    private val cache = object : LruCache<String, Bitmap>(6) {
        override fun sizeOf(key: String, value: Bitmap) = 1
    }

    /** 画面变体数量：太多会撑爆 LRU 缓存，4 帧轮换已经够「不像死图」了 */
    private const val VARIANT_COUNT = 4

    /**
     * 背景层：天空渐变 + 云 + 雨雪雾 + 光效。
     *
     * 这一层用 fitXY 铺满卡片也没关系 —— 渐变和云团本身就是模糊的，
     * 轻微纵向拉伸看不出来；只有太阳/月亮是正圆，会被拉成椭圆，
     * 所以天体单独用 [renderCelestial] 画进一个正方形 ImageView。
     */
    fun renderBackground(
        widthPx: Int,
        heightPx: Int,
        condition: WeatherCondition,
        isDay: Boolean,
        /**
         * 画面变体：云的形状是随机撒的，换一个变体就是另一朵云。
         *
         * RemoteViews 做不了逐帧动画（桌面不会替我们重绘），但每次刷新换一帧，
         * 用户每次瞥一眼卡片云的位置都不一样，画面就不会像一张死图。
         */
        variant: Int = 0
    ): Bitmap {
        val (w, h) = fitSize(widthPx, heightPx)
        val slot = ((variant % VARIANT_COUNT) + VARIANT_COUNT) % VARIANT_COUNT
        val key = "bg_${condition.name}_$isDay" + "_${w}x$h" + "_v$slot"
        cache.get(key)?.let { return it }

        val bitmap = drawBackground(w, h, condition, isDay, slot)
        cache.put(key, bitmap)
        return bitmap
    }

    /**
     * 天体层：太阳或月亮（含光晕与光芒），画在正方形位图里。
     *
     * 正方形位图 + 正方形 ImageView，无论卡片被拉成什么比例都不会变成椭圆。
     * 天气看不到天体时返回透明位图（调用方也可以直接把控件设为 GONE）。
     */
    fun renderCelestial(
        sizePx: Int,
        condition: WeatherCondition,
        isDay: Boolean
    ): Bitmap? {
        if (!hasCelestial(condition)) return null
        val size = sizePx.coerceIn(48, 384)
        val key = "sky_${condition.name}_$isDay" + "_$size"
        cache.get(key)?.let { return it }

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val half = size / 2f
        // 半径要留出光晕的空间：光晕最远画到 2.6r，必须小于 half，
        // 否则渐变会在方形位图边缘被切开，桌面上会出现一个方框
        if (isDay) {
            drawSun(canvas, half, half, size * 0.17f, if (condition == WeatherCondition.SUNNY) 1f else 0.62f)
        } else {
            drawMoon(canvas, half, half, size * 0.16f)
        }
        cache.put(key, bitmap)
        return bitmap
    }

    /** 这档天气能不能看到太阳/月亮 */
    private fun hasCelestial(condition: WeatherCondition): Boolean = when (condition) {
        WeatherCondition.CLOUDY, WeatherCondition.OVERCAST, WeatherCondition.FOG,
        WeatherCondition.RAIN, WeatherCondition.DRIZZLE,
        WeatherCondition.SHOWER, WeatherCondition.THUNDER,
        WeatherCondition.SNOW -> false

        else -> true
    }

    /** 把目标尺寸压到安全范围内，保持长宽比 */
    private fun fitSize(widthPx: Int, heightPx: Int): Pair<Int, Int> {
        val w = widthPx.coerceIn(160, 2000).toFloat()
        val h = heightPx.coerceIn(96, 1600).toFloat()
        val scale = min(
            1f,
            min(MAX_WIDTH_PX / w, sqrt(MAX_PIXELS / (w * h)))
        )
        return (w * scale).roundToInt().coerceAtLeast(1) to
            (h * scale).roundToInt().coerceAtLeast(1)
    }

    // ------------------------------------------------------------------ 主绘制

    private fun drawBackground(
        w: Int,
        h: Int,
        condition: WeatherCondition,
        isDay: Boolean,
        variant: Int = 0
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fw = w.toFloat()
        val fh = h.toFloat()

        // 圆角裁切：卡片本身是圆角，位图铺满后四角要跟着圆，否则会露出方形尖角
        val radius = min(fw, fh) * 0.17f
        canvas.save()
        canvas.clipPath(
            Path().apply {
                addRoundRect(RectF(0f, 0f, fw, fh), radius, radius, Path.Direction.CW)
            }
        )

        // 变体参与随机种子：同一个变体总是画同一朵云（缓存命中），换变体就换一片云
        val random = Random(condition.ordinal * 977 + (if (isDay) 13 else 29) + variant * 7919)

        drawSky(canvas, fw, fh, condition, isDay)
        if (!isDay) drawStars(canvas, fw, fh, random, if (hasCelestial(condition)) 46 else 26)
        drawClouds(canvas, fw, fh, condition, isDay, random)
        // 晴天散落几点小星芒，背景不至于太空
        if (isDay && condition == WeatherCondition.SUNNY) drawSparkles(canvas, fw, fh, random)
        drawPrecipitation(canvas, fw, fh, condition, isDay, random)
        if (condition == WeatherCondition.THUNDER) drawLightning(canvas, fw, fh)
        drawLightSweep(canvas, fw, fh, isDay)
        drawBottomVeil(canvas, fw, fh, isDay)

        canvas.restore()
        return bitmap
    }

    // ------------------------------------------------------------------ 天空

    private fun drawSky(
        canvas: Canvas,
        w: Float,
        h: Float,
        condition: WeatherCondition,
        isDay: Boolean
    ) {
        val colors = skyColors(condition, isDay)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w * 0.45f, h,
                colors, floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, paint)

        // 顶部一层低透明度白光，模拟天光从上打下来，让平面渐变有体积感
        val top = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.72f, -h * 0.15f, h * 1.5f,
                intArrayOf(argb(if (isDay) 70 else 28, 255, 255, 255), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, top)
    }

    private fun skyColors(condition: WeatherCondition, isDay: Boolean): IntArray {
        if (!isDay) {
            return when (condition) {
                WeatherCondition.SUNNY, WeatherCondition.PARTLY ->
                    intArrayOf(rgb(0x1E2A4A), rgb(0x35496F), rgb(0x6E7FA8))

                WeatherCondition.CLOUDY, WeatherCondition.OVERCAST ->
                    intArrayOf(rgb(0x2A3346), rgb(0x44506A), rgb(0x6B7891))

                WeatherCondition.FOG ->
                    intArrayOf(rgb(0x343C49), rgb(0x4E5766), rgb(0x77808E))

                WeatherCondition.THUNDER ->
                    intArrayOf(rgb(0x1C2230), rgb(0x333D50), rgb(0x5B677C))

                WeatherCondition.SNOW ->
                    intArrayOf(rgb(0x3A4761), rgb(0x55647F), rgb(0x8896AD))

                WeatherCondition.DRIZZLE, WeatherCondition.RAIN, WeatherCondition.SHOWER ->
                    intArrayOf(rgb(0x232C3D), rgb(0x3B4A61), rgb(0x64758C))
            }
        }
        return when (condition) {
            WeatherCondition.SUNNY ->
                intArrayOf(rgb(0xFFE1A0), rgb(0xFFF0CB), rgb(0xFFF9EA))

            WeatherCondition.PARTLY ->
                intArrayOf(rgb(0xBEDCFB), rgb(0xDDEBFC), rgb(0xF2F8FF))

            WeatherCondition.CLOUDY ->
                intArrayOf(rgb(0xC6D6E8), rgb(0xDDE8F4), rgb(0xF0F6FC))

            WeatherCondition.OVERCAST ->
                intArrayOf(rgb(0xB4C2D2), rgb(0xCCD7E3), rgb(0xE8EEF5))

            WeatherCondition.FOG ->
                intArrayOf(rgb(0xCDD6DF), rgb(0xE0E7EE), rgb(0xF3F6F9))

            WeatherCondition.THUNDER ->
                intArrayOf(rgb(0x93A6BC), rgb(0xB4C4D6), rgb(0xDDE6F0))

            WeatherCondition.SNOW ->
                intArrayOf(rgb(0xA6C2E2), rgb(0xC8DEF3), rgb(0xE8F3FF))

            WeatherCondition.DRIZZLE, WeatherCondition.RAIN, WeatherCondition.SHOWER ->
                intArrayOf(rgb(0xAEBFD2), rgb(0xC8D8E8), rgb(0xE4EDF6))
        }
    }

    // ------------------------------------------------------------------ 太阳 / 月亮

    // ------------------------------------------------------------------ 太阳 / 月亮（独立图层）

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, r: Float, strength: Float) {
        // 外层大光晕（2.6r 保证不会溢出方形位图边角）
        canvas.drawCircle(
            cx, cy, r * 2.6f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx, cy, r * 2.6f,
                    intArrayOf(argb((120 * strength).toInt(), 255, 232, 168), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP
                )
            }
        )

        // 旋转光芒
        val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = r * 0.16f
            color = argb((120 * strength).toInt(), 255, 214, 120)
            maskFilter = BlurMaskFilter(r * 0.22f, BlurMaskFilter.Blur.NORMAL)
        }
        val rays = 12
        for (i in 0 until rays) {
            val angle = Math.toRadians((i * 360.0 / rays) + 12.0)
            val inner = r * 1.45f
            val outer = r * (1.75f + if (i % 2 == 0) 0.36f else 0.12f)
            canvas.drawLine(
                cx + (cos(angle) * inner).toFloat(),
                cy + (sin(angle) * inner).toFloat(),
                cx + (cos(angle) * outer).toFloat(),
                cy + (sin(angle) * outer).toFloat(),
                rayPaint
            )
        }

        // 本体：左上偏亮，做出球面感
        canvas.drawCircle(
            cx, cy, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx - r * 0.28f, cy - r * 0.3f, r * 1.5f,
                    intArrayOf(rgb(0xFFFBDA), rgb(0xFFD34D), rgb(0xFFB93B)),
                    floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP
                )
            }
        )
    }

    private fun drawMoon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(
            cx, cy, r * 2.6f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx, cy, r * 2.6f,
                    intArrayOf(argb(70, 210, 226, 255), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP
                )
            }
        )
        canvas.drawCircle(
            cx, cy, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx - r * 0.3f, cy - r * 0.35f, r * 1.6f,
                    intArrayOf(rgb(0xFFFFFF), rgb(0xE8F1FF), rgb(0xC3D3E8)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
                )
            }
        )
        // 几个浅浅的环形山，避免月亮像一块白饼
        val crater = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(28, 120, 140, 170)
            maskFilter = BlurMaskFilter(r * 0.12f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(cx - r * 0.28f, cy + r * 0.12f, r * 0.22f, crater)
        canvas.drawCircle(cx + r * 0.24f, cy - r * 0.3f, r * 0.16f, crater)
        canvas.drawCircle(cx + r * 0.1f, cy + r * 0.42f, r * 0.12f, crater)
    }

    private fun drawStars(canvas: Canvas, w: Float, h: Float, random: Random, count: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(count) {
            val x = random.nextFloat() * w
            val y = random.nextFloat() * h * 0.8f
            val radius = (h * 0.004f) * (0.6f + random.nextFloat() * 1.6f)
            val alpha = (70 + random.nextFloat() * 150).toInt()
            paint.color = argb(alpha, 255, 255, 255)
            canvas.drawCircle(x, y, radius, paint)
            // 亮星加一圈光晕
            if (random.nextFloat() > 0.72f) {
                canvas.drawCircle(
                    x, y, radius * 3.2f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = RadialGradient(
                            x, y, radius * 3.2f,
                            intArrayOf(argb(60, 255, 255, 255), Color.TRANSPARENT),
                            null, Shader.TileMode.CLAMP
                        )
                    }
                )
            }
        }
    }

    private fun drawSparkles(canvas: Canvas, w: Float, h: Float, random: Random) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb(150, 255, 246, 210) }
        repeat(7) {
            val cx = random.nextFloat() * w
            val cy = random.nextFloat() * h * 0.85f
            val s = h * (0.012f + random.nextFloat() * 0.016f)
            val path = Path().apply {
                moveTo(cx, cy - s)
                lineTo(cx + s * 0.26f, cy - s * 0.26f)
                lineTo(cx + s, cy)
                lineTo(cx + s * 0.26f, cy + s * 0.26f)
                lineTo(cx, cy + s)
                lineTo(cx - s * 0.26f, cy + s * 0.26f)
                lineTo(cx - s, cy)
                lineTo(cx - s * 0.26f, cy - s * 0.26f)
                close()
            }
            canvas.drawPath(path, paint)
        }
    }

    // ------------------------------------------------------------------ 云

    private fun drawClouds(
        canvas: Canvas,
        w: Float,
        h: Float,
        condition: WeatherCondition,
        isDay: Boolean,
        random: Random
    ) {
        // 云量：0 几乎无云，1 满天云
        val coverage = when (condition) {
            WeatherCondition.SUNNY -> 0.16f
            WeatherCondition.PARTLY -> 0.40f
            WeatherCondition.CLOUDY -> 0.62f
            WeatherCondition.SNOW -> 0.70f
            WeatherCondition.FOG -> 0.55f
            WeatherCondition.OVERCAST -> 1f
            WeatherCondition.RAIN, WeatherCondition.DRIZZLE,
            WeatherCondition.SHOWER -> 0.92f

            WeatherCondition.THUNDER -> 1f
        }

        // 晴天只放两朵很淡的云，避免大面积纯色渐变显得空
        if (coverage <= 0.2f) {
            val paint = cloudPaint(isDay, cloudTone(condition, isDay), 46)
            drawCloud(canvas, w * 0.22f, h * 0.66f, w * 0.26f, paint)
            drawCloud(canvas, w * 0.84f, h * 0.46f, w * 0.20f, paint)
            return
        }

        val paint = cloudPaint(isDay, cloudTone(condition, isDay), cloudAlpha(condition, isDay))

        // 云分层：越靠上的层越大越淡，越往下越小越实，天空才有纵深
        val layers = (2 + coverage * 2.2f).toInt().coerceIn(2, 4)
        repeat(layers) { index ->
            val scale = 1f - index * 0.16f
            val cy = h * (0.06f + index * 0.12f) + random.nextFloat() * h * 0.05f
            drawCloud(
                canvas,
                w * (0.10f + random.nextFloat() * 0.80f),
                cy,
                w * (0.26f + random.nextFloat() * 0.16f) * scale,
                paint
            )
        }

        // 降水天气在云底压一条更深的阴影，天空层次更明显
        if (WeatherCodes.isWet(condition) || condition == WeatherCondition.OVERCAST) {
            val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = argb(if (isDay) 34 else 60, 40, 56, 82)
                maskFilter = BlurMaskFilter(h * 0.10f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawRect(0f, 0f, w, h * 0.34f, shade)
        }
    }

    /** 云的颜色：阴雨天偏灰蓝、雪天纯白、晴天接近白但更淡 */
    private fun cloudTone(condition: WeatherCondition, isDay: Boolean): Int {
        if (!isDay) {
            return when (condition) {
                WeatherCondition.SNOW -> rgb(0xD8E6FA)
                WeatherCondition.THUNDER -> rgb(0x7C8AA3)
                WeatherCondition.OVERCAST, WeatherCondition.RAIN,
                WeatherCondition.DRIZZLE, WeatherCondition.SHOWER -> rgb(0x7A889E)

                else -> rgb(0xBFCEE4)
            }
        }
        return when (condition) {
            WeatherCondition.OVERCAST -> rgb(0xC6D1DF)
            WeatherCondition.RAIN, WeatherCondition.DRIZZLE,
            WeatherCondition.SHOWER -> rgb(0xC2CFDE)

            WeatherCondition.THUNDER -> rgb(0xAEBDD0)
            WeatherCondition.SNOW -> rgb(0xFFFFFF)
            WeatherCondition.FOG -> rgb(0xF2F6FA)
            else -> rgb(0xFFFFFF)
        }
    }

    private fun cloudAlpha(condition: WeatherCondition, isDay: Boolean): Int {
        val base = when (condition) {
            WeatherCondition.PARTLY -> 110
            WeatherCondition.CLOUDY -> 128
            WeatherCondition.SNOW -> 170
            WeatherCondition.FOG -> 90
            WeatherCondition.OVERCAST -> 160
            WeatherCondition.RAIN, WeatherCondition.DRIZZLE,
            WeatherCondition.SHOWER -> 150

            WeatherCondition.THUNDER -> 175
            else -> 90
        }
        // 夜里云本来就看不太清，压两档，否则浅色云团在深色天上像一块污渍
        return if (isDay) base else (base * 0.42f).toInt()
    }

    private fun cloudPaint(isDay: Boolean, tone: Int, alpha: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (alpha.coerceIn(0, 255) shl 24) or (tone and 0x00FFFFFF)
            maskFilter = BlurMaskFilter(1f, BlurMaskFilter.Blur.NORMAL)
        }

    private fun drawCloud(canvas: Canvas, cx: Float, cy: Float, width: Float, paint: Paint) {
        val body = width * 0.5f
        val p = Paint(paint).apply {
            maskFilter = BlurMaskFilter(body * 0.11f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(
            cx - width * 0.5f, cy - body * 0.10f,
            cx + width * 0.5f, cy + body * 0.42f,
            body * 0.3f, body * 0.3f, p
        )
        canvas.drawCircle(cx - width * 0.24f, cy + body * 0.04f, body * 0.30f, p)
        canvas.drawCircle(cx + width * 0.02f, cy - body * 0.20f, body * 0.40f, p)
        canvas.drawCircle(cx + width * 0.28f, cy + body * 0.06f, body * 0.28f, p)
    }

    // ------------------------------------------------------------------ 降水

    private fun drawPrecipitation(
        canvas: Canvas,
        w: Float,
        h: Float,
        condition: WeatherCondition,
        isDay: Boolean,
        random: Random
    ) {
        when (condition) {
            WeatherCondition.SNOW -> drawSnow(canvas, w, h, random)
            WeatherCondition.RAIN, WeatherCondition.SHOWER -> drawRain(canvas, w, h, isDay, random, 46)
            WeatherCondition.DRIZZLE -> drawRain(canvas, w, h, isDay, random, 26)
            WeatherCondition.THUNDER -> drawRain(canvas, w, h, isDay, random, 58)
            WeatherCondition.FOG -> drawFogBands(canvas, w, h, isDay)
            else -> Unit
        }
    }

    private fun drawRain(
        canvas: Canvas,
        w: Float,
        h: Float,
        isDay: Boolean,
        random: Random,
        count: Int
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = h * 0.007f
            color = if (isDay) argb(150, 90, 130, 175) else argb(150, 200, 226, 255)
            maskFilter = BlurMaskFilter(h * 0.003f, BlurMaskFilter.Blur.NORMAL)
        }
        repeat(count) {
            val x = random.nextFloat() * w * 1.16f - w * 0.08f
            val y = random.nextFloat() * h
            val length = h * (0.05f + random.nextFloat() * 0.09f)
            // 斜度给得比「正常观感」更斜一点：背景是 fitXY 铺满的，纵向会被拉长，
            // 提前补偿后落到屏幕上刚好是自然的斜雨
            canvas.drawLine(x, y, x - length * 0.55f, y + length, paint)
        }

        // 地面附近的溅点，让雨看起来是落下来的而不是飘着的
        val splash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDay) argb(110, 90, 130, 175) else argb(120, 200, 226, 255)
        }
        repeat(count / 4) {
            val x = random.nextFloat() * w
            val y = h * (0.72f + random.nextFloat() * 0.26f)
            canvas.drawOval(
                RectF(x - h * 0.012f, y - h * 0.004f, x + h * 0.012f, y + h * 0.004f),
                splash
            )
        }
    }

    private fun drawSnow(canvas: Canvas, w: Float, h: Float, random: Random) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(52) {
            val x = random.nextFloat() * w
            val y = random.nextFloat() * h
            val r = h * (0.005f + random.nextFloat() * 0.011f)
            // 先画一圈冷色外晕再叠白色，纯白雪花在浅色天空上会糊没
            paint.color = argb(90, 120, 152, 190)
            paint.maskFilter = BlurMaskFilter(r * 1.1f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawCircle(x, y, r * 1.25f, paint)
            paint.color = argb((210 + random.nextFloat() * 45).toInt(), 255, 255, 255)
            paint.maskFilter = BlurMaskFilter(r * 0.55f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawCircle(x, y, r, paint)
            // 少量六角雪花用十字线示意，近看更像雪
            if (r > h * 0.012f) {
                paint.maskFilter = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = r * 0.34f
                paint.color = argb(140, 255, 255, 255)
                canvas.drawLine(x - r * 1.5f, y, x + r * 1.5f, y, paint)
                canvas.drawLine(x, y - r * 1.5f, x, y + r * 1.5f, paint)
                paint.style = Paint.Style.FILL
            }
        }
    }

    private fun drawFogBands(canvas: Canvas, w: Float, h: Float, isDay: Boolean) {
        // 先铺一层整体薄雾，否则雾天看起来就是「一张空卡片」
        canvas.drawRect(
            0f, h * 0.35f, w, h,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, h * 0.35f, 0f, h,
                    intArrayOf(
                        Color.TRANSPARENT,
                        if (isDay) argb(96, 255, 255, 255) else argb(56, 200, 214, 236)
                    ),
                    null, Shader.TileMode.CLAMP
                )
            }
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDay) argb(165, 255, 255, 255) else argb(88, 210, 222, 240)
            maskFilter = BlurMaskFilter(h * 0.045f, BlurMaskFilter.Blur.NORMAL)
        }
        for (i in 0 until 5) {
            val cy = h * (0.38f + i * 0.13f)
            val left = -w * 0.1f + i * w * 0.03f
            canvas.drawRoundRect(
                left, cy, left + w * (0.75f + i * 0.06f), cy + h * 0.08f,
                h * 0.04f, h * 0.04f, paint
            )
        }
    }

    private fun drawLightning(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.34f
        val top = h * 0.30f
        val path = Path().apply {
            moveTo(cx, top)
            lineTo(cx - w * 0.035f, top + h * 0.16f)
            lineTo(cx + w * 0.008f, top + h * 0.155f)
            lineTo(cx - w * 0.03f, top + h * 0.34f)
        }

        // 外发光：粗笔画 + 模糊，模拟电离空气的亮边
        canvas.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = h * 0.055f
                color = argb(90, 255, 246, 170)
                maskFilter = BlurMaskFilter(h * 0.03f, BlurMaskFilter.Blur.NORMAL)
            }
        )
        canvas.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = h * 0.012f
                color = rgb(0xFFFBEA)
            }
        )
    }

    // ------------------------------------------------------------------ 收尾光效

    /** 一道斜向高光，像玻璃反光，是让卡片「有质感」最省事的一笔 */
    private fun drawLightSweep(canvas: Canvas, w: Float, h: Float, isDay: Boolean) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                w * 0.1f, 0f, w * 0.75f, h,
                intArrayOf(
                    Color.TRANSPARENT,
                    argb(if (isDay) 42 else 26, 255, 255, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val path = Path().apply {
            moveTo(w * 0.05f, 0f)
            lineTo(w * 0.38f, 0f)
            lineTo(w * 0.62f, h)
            lineTo(w * 0.29f, h)
            close()
        }
        canvas.drawPath(path, paint)
    }

    /** 底部压一层薄纱，保证状态行文字在任何天气下都够清晰 */
    private fun drawBottomVeil(canvas: Canvas, w: Float, h: Float, isDay: Boolean) {
        val veil = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, h * 0.45f, 0f, h,
                intArrayOf(
                    Color.TRANSPARENT,
                    if (isDay) argb(56, 255, 255, 255) else argb(48, 12, 18, 30)
                ),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, h * 0.45f, w, h, veil)
    }

    // ------------------------------------------------------------------ 小工具

    private fun rgb(value: Int) = 0xFF000000.toInt() or (value and 0xFFFFFF)

    private fun argb(alpha: Int, r: Int, g: Int, b: Int) =
        Color.argb(alpha.coerceIn(0, 255), r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
}
