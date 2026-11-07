package com.example.myzuoye.transfer

import android.os.SystemClock

/**
 * 简单的时间窗限流器。
 *
 * 起因：收 / 发文件时每处理一个 64KB 分片都会触发一次「更新通知」，
 * 而底层是一次 `startService(Intent)` 跨进程调用。10MB/s 就是每秒上百次，
 * 通知栏跟着抖、CPU 也白烧。进度条本身是系统画的，一秒刷两次肉眼完全看不出差别。
 *
 * 时钟做成可注入的（默认 [SystemClock.elapsedRealtime]），这样在纯 JVM 单测里
 * 可以喂假时间，不用等真实秒数，也不需要 Robolectric。
 */
internal class RateLimiter(
    private val intervalMs: Long,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {

    /** 上一次放行的时刻；初值取一个足够小的数，保证第一次一定放行 */
    private var lastAt = Long.MIN_VALUE / 2

    /** 距离上次放行够不够 [intervalMs]；够就放行并记下时刻 */
    fun tryAcquire(): Boolean {
        val now = clock()
        if (now - lastAt < intervalMs) return false
        lastAt = now
        return true
    }

    /** 换了新的传输/新的通知，重新计时 */
    fun reset() {
        lastAt = Long.MIN_VALUE / 2
    }
}

/**
 * 「链路是不是已经死了」的判定。
 *
 * 单独抽出来是因为这条规则很容易写错方向：
 * - 必须**只在真的在传文件时**判定——空闲挂着的连接可以一直连着聊天，那是正常状态；
 * - 用「多久没有字节流动」而不是 socket 读超时，才能同时覆盖「对端不发」和
 *   「对端不读导致本机 write 阻塞」两种情况。
 */
internal fun linkStalled(
    nowMs: Long,
    lastIoAt: Long,
    transferring: Boolean,
    idleTimeoutMs: Long
): Boolean = transferring && nowMs - lastIoAt >= idleTimeoutMs
