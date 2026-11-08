package com.example.myzuoye.net

/**
 * 客户端要尝试的组主地址。
 *
 * Wi-Fi Direct 的组主地址固定是 192.168.49.1，但 [android.net.wifi.p2p.WifiP2pInfo]
 * 在个别 ROM 上会给出空值或 0.0.0.0（组刚形成、地址还没写进 info 时最明显）。
 * 以前这种情况直接记一句「组主地址未知」就放弃了，实际上直连固定地址往往就能通。
 *
 * 这里把「系统报的地址」和「固定地址」合成候选列表，去重后交给传输层逐个尝试。
 */
object ChannelTargets {

    /** 组主的固定地址：P2P 组的网关 */
    const val GROUP_OWNER_ADDRESS = "192.168.49.1"

    /** 系统偶尔会报 0.0.0.0，等同于没拿到 */
    private const val UNKNOWN_HOST = "0.0.0.0"

    /** 默认监听 / 连接端口 */
    const val DEFAULT_PORT = 8988

    fun forGroupOwner(reportedHost: String?, port: Int = DEFAULT_PORT): List<Pair<String, Int>> {
        val reported = reportedHost
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != UNKNOWN_HOST }

        val hosts = buildList {
            reported?.let { add(it) }
            if (reported != GROUP_OWNER_ADDRESS) add(GROUP_OWNER_ADDRESS)
        }
        return hosts.map { it to port }
    }
}
