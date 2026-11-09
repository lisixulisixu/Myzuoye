package com.example.myzuoye.transfer

/**
 * 断线自动重连的策略，全部纯函数，方便 JVM 单测。
 *
 * 之前只允许自动重连一次（一个布尔标志），Wi-Fi Direct 组偶尔抖两下就用完了额度，
 * 第二次断线用户就得自己动手。放宽成多轮，同时防住两个坑：
 *
 * - 「连上就被踢」死循环：连接每次都活不过一分钟就不重置计数，
 *   连续三轮耗尽后停下来等用户手动连；
 * - 「额度太抠」：连接稳定超过 [STREAK_RESET_MS] 才断的，说明这不是连环抖动，
 *   计数清零重新给满额度。
 */
object ReconnectPolicy {

    /** 自动重连的最大轮数（连续快速断线的上限） */
    const val MAX_ROUNDS = 3

    /**
     * 连接稳定判定期：一条连接活了超过这么久才断，
     * 下次断线时重连计数清零（不是连环抖动，是偶发）。
     */
    const val STREAK_RESET_MS = 60_000L

    /** 连接存活多久后断线可以重置重连计数 */
    fun shouldResetStreak(connectionDurationMs: Long): Boolean =
        connectionDurationMs >= STREAK_RESET_MS

    /** 还能不能再自动重连一轮 */
    fun canAttempt(roundsUsed: Int): Boolean = roundsUsed < MAX_ROUNDS

    /**
     * 每轮的连接尝试次数。
     *
     * 首轮给最多：Wi-Fi Direct 组如果是整体掉线，重建要好几秒；
     * 后面逐轮收敛，避免对着一个已经消失的组空转太久。
     */
    fun attemptsForRound(round: Int): Int = when (round) {
        1 -> 12
        2 -> 10
        else -> 8
    }
}
