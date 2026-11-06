package com.example.myzuoye.ui

import kotlin.math.exp
import kotlin.math.sin

/**
 * 「女仆长发在飘」的形变场。
 *
 * 之所以把这件事抽成纯函数，有两个原因：
 * 1. 网格形变的参数（权重范围、摆幅、相位）很难在真机上一点点试，抽出来就能写单测卡住；
 * 2. 权重函数里全是「凭截图比出来的魔数」，集中在一个文件里改起来才不容易碰坏别处。
 *
 * 坐标系一律归一化到原图（u = x / 宽，v = y / 高，0..1），这样和控件尺寸、缩放都无关。
 *
 * 范围是照着 p1.jpg 反复比对定下来的：
 * - 左侧 0.455 以左是男生的白衬衫，必须完全不动（否则衣服会跟着「呼吸」，非常怪）；
 * - 0.52 一带是她脸和头饰的位置，只让脸颊右侧的长发参与，避免五官被拽歪；
 * - 真正摆起来的是 0.5 ~ 0.72、0.28 ~ 0.78 这团垂到腰际的长发，越靠发梢幅度越大。
 */
internal object HairField {

    /** 中段主体：从肩后垂到腰的长发，摆幅最直观 */
    private const val CX_MAIN = 0.580f
    private const val CY_MAIN = 0.465f
    private const val SX_MAIN = 0.105f
    private const val SY_MAIN = 0.115f

    /** 下摆：贴着裙子的那几缕 */
    private const val CX_LOW = 0.560f
    private const val CY_LOW = 0.650f
    private const val SX_LOW = 0.080f
    private const val SY_LOW = 0.095f

    /** 最外侧翘起的发梢（靠近门框那一边），飘得最明显 */
    private const val CX_TIP = 0.668f
    private const val CY_TIP = 0.470f
    private const val SX_TIP = 0.062f
    private const val SY_TIP = 0.078f

    /**
     * 平滑阶跃：在 [edge0, edge1] 之间从 0 过渡到 1。
     *
     * 用 smoothstep 而不是线性插值，是因为网格形变对「位移场的导数」很敏感：
     * 线性过渡会在边界留下一条折痕（像素被拉出明显的一条缝），平滑阶跃能消掉。
     */
    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge0 == edge1) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun blob(u: Float, v: Float, cx: Float, cy: Float, sx: Float, sy: Float): Float {
        val dx = (u - cx) / sx
        val dy = (v - cy) / sy
        return exp(-(dx * dx + dy * dy))
    }

    /**
     * 某个像素点参与形变的权重，0 表示不动、1 表示满摆幅。
     *
     * 两次 gate 相乘：横向卡住左边界（衬衫）与右边界（门框），纵向卡住头顶与裙摆，
     * 中间再乘三个高斯「团块」，这样头发主体权重接近 1，边缘自然衰减到 0。
     */
    fun weight(u: Float, v: Float): Float {
        val gx = smoothstep(0.455f, 0.548f, u) * (1f - smoothstep(0.700f, 0.768f, u))
        if (gx <= 0f) return 0f
        val gy = smoothstep(0.270f, 0.362f, v) * (1f - smoothstep(0.730f, 0.802f, v))
        if (gy <= 0f) return 0f
        val mass = blob(u, v, CX_MAIN, CY_MAIN, SX_MAIN, SY_MAIN) +
            blob(u, v, CX_LOW, CY_LOW, SX_LOW, SY_LOW) +
            blob(u, v, CX_TIP, CY_TIP, SX_TIP, SY_TIP)
        return (mass.coerceAtMost(1f) * gx * gy).coerceIn(0f, 1f)
    }

    /**
     * t 秒时刻的摆动量，大致落在 -1.1 ~ 1.1。
     *
     * 两条正弦叠加：
     * - 主波 `1.25 rad/s` 且带 `-u * 6.2` 的相位，等于「发根先动、发梢后动」，
     *   看起来是风从画面左侧吹过去，而不是整块头发原地抖；
     * - 副波慢一半、方向相反，用来打散主波的机械感（只有一条波会像节拍器）。
     */
    fun sway(u: Float, v: Float, t: Float): Float =
        0.78f * sin(t * 1.25f - u * 6.2f - v * 1.6f) +
            0.30f * sin(t * 0.55f + u * 2.1f + v * 3.3f)
}
