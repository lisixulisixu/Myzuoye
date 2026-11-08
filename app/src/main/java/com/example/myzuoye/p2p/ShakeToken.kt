package com.example.myzuoye.p2p

import kotlin.math.abs

/**
 * 摇一摇配对的「口令」规则。
 *
 * 两台手机在组网之前没有任何 IP 通路，唯一能携带自定义数据的官方通道是
 * Wi-Fi Direct 的服务发现（DNS-SD）：本机把服务实例名广播出去，对端能原样收到。
 * 所以实例名里塞两样东西：
 *
 * ```
 * wf-<口令>-<标识>        例：wf-14914718-a3f91c
 * ```
 *
 * - **口令**：摇的那一刻按固定时长分桶得到的序号。两边在同一个桶（或相邻桶）里，
 *   说明是「同时」摇的 —— 这就是「一个人摇没用，天然是双人动作」的技术落点。
 * - **标识**：设备随机生成的一小段十六进制，用来区分「谁是谁」。它也决定了谁去发起连接，
 *   否则两边同时 connect 会互相踩（Wi-Fi Direct 不允许双向同时协商）。
 *
 * 这里全是纯函数，方便单测；真实收发在 [P2pController] 里。
 */
object ShakeToken {

    /** DNS-SD 服务类型：自定义服务名，对端设备也按这个类型搜 */
    const val SERVICE_TYPE = "_wfshake._tcp"

    /** 口令分桶长度。1200ms 一个桶，允许相邻桶配对，实际容忍度约 2.4 秒 */
    const val BUCKET_MS = 1200L

    private const val PREFIX = "wf-"

    /** 允许相差几个桶：1 表示前后一个桶都算「同时」 */
    const val TOLERANCE = 1L

    /** 按时间戳算口令 */
    fun token(nowMs: Long): Long = nowMs / BUCKET_MS

    /**
     * 生成广播用的实例名。
     *
     * 只保留字母数字和下划线：实例名会被拼进 DNS-SD 的 PTR/SRV 记录，
     * 带空格或点会破坏记录格式。
     */
    fun instance(token: Long, id: String): String = PREFIX + token + "-" + sanitize(id)

    /** 解析对端广播的实例名；不是我们的格式就返回 null */
    fun parse(instance: String): Pair<Long, String>? {
        if (!instance.startsWith(PREFIX)) return null
        val rest = instance.removePrefix(PREFIX)
        val dash = rest.indexOf('-')
        if (dash <= 0 || dash == rest.length - 1) return null
        val token = rest.substring(0, dash).toLongOrNull() ?: return null
        val id = rest.substring(dash + 1)
        if (id.isEmpty()) return null
        return token to id
    }

    /** 两个口令算不算「同时摇的」 */
    fun matches(mine: Long, theirs: Long, tolerance: Long = TOLERANCE): Boolean =
        abs(mine - theirs) <= tolerance

    /**
     * 是否由本机发起连接。
     *
     * 规则必须**对称且确定**：两边拿到同一对标识时，必须得出「一个发起、一个等待」，
     * 不能两个都发起（互相踩），也不能两个都等（僵住）。比字符串大小就满足这个性质。
     */
    fun shouldInvite(selfId: String, peerId: String): Boolean = selfId > peerId

    /**
     * 从设备标识（通常是 ANDROID_ID）派生一个短标识。
     *
     * 不用型号或序列号：同型号两台手机型号相同，会撞在一起；
     * ANDROID_ID 在同一台设备上是稳定的，且每台不同。
     */
    fun deviceId(raw: String?): String {
        if (raw.isNullOrBlank()) return "anon"
        // 稳定的 32 位散列，取 6 位十六进制即可，够区分又不会撑长实例名
        var hash = 0x811C9DC5.toInt()
        for (ch in raw) {
            hash = hash xor ch.code
            hash *= 0x01000193
        }
        return (hash.toLong() and 0xFFFFFFL).toString(16).padStart(6, '0')
    }

    private fun sanitize(id: String): String =
        id.filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "anon" }
}
