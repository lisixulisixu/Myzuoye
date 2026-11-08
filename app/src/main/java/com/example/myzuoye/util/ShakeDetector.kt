package com.example.myzuoye.util

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 摇一摇识别。
 *
 * 输入是加速度计的原始三轴读数，输出「刚刚摇了一下」。
 * 判定用「去掉重力后的峰值个数」而不是简单的瞬时阈值：
 * 走路、放桌上、拿起来看时间都会产生单次超过阈值的大加速度，
 * 只有连续几次反向摆动才是真的在摇。
 *
 * 全部逻辑与 Android 无关（只吃 float + 时间戳），便于单元测试。
 */
class ShakeDetector(
    /** 超过「重力 ± 这个值」才算一次摆动，单位 m/s² */
    private val threshold: Float = 2.6f,
    /** 一个窗口内要几个峰值才算摇 */
    private val peaksNeeded: Int = 3,
    /**
     * 峰值统计窗口。
     *
     * 定 1.3 秒而不是更短：人手摇一下的摆动间隔大约 150~400ms（越轻越慢），
     * 三个峰凑在 0.8 秒内要求摇得相当快，很多人第一下会「没反应」。
     * 1.3 秒仍然远小于两次无关动作（拿起手机、放下手机）的间隔，不会误触发。
     */
    private val windowMs: Long = 1300L,
    /** 一次「摇」之后的冷却，避免手一抖连着触发两次 */
    private val cooldownMs: Long = 2200L
) {

    private val peaks = ArrayDeque<Long>()

    /**
     * 是否已经「离开」峰值区。
     *
     * 没有它的话，一次持续的甩动（加速度连续几十毫秒都高于阈值）会被切成一串假峰值，
     * 一秒就能凑满 [peaksNeeded]。用回滞（低于阈值的一半才重新武装）保证一次摆动只算一个峰。
     */
    private var armed = true

    private var lastTrigger = 0L

    /** 喂一个采样，返回 true 表示「这一次采样凑齐了一次摇」 */
    fun feed(x: Float, y: Float, z: Float, nowMs: Long): Boolean {
        val magnitude = sqrt(x * x + y * y + z * z)
        val deviation = abs(magnitude - GRAVITY)

        if (deviation > threshold) {
            if (armed) {
                armed = false
                peaks.addLast(nowMs)
            }
        } else if (deviation < threshold * REARM_RATIO) {
            armed = true
        }

        while (peaks.isNotEmpty() && nowMs - peaks.first() > windowMs) {
            peaks.removeFirst()
        }

        if (peaks.size >= peaksNeeded && nowMs - lastTrigger >= cooldownMs) {
            lastTrigger = nowMs
            peaks.clear()
            return true
        }
        return false
    }

    /** 重新开始计数（比如界面切走再回来） */
    fun reset() {
        peaks.clear()
        armed = true
        lastTrigger = 0L
    }

    private companion object {
        /** 标准重力加速度 */
        const val GRAVITY = 9.80665f

        /** 回滞比例：要掉到阈值的这个比例以下才算「离开峰值」 */
        const val REARM_RATIO = 0.45f
    }
}
