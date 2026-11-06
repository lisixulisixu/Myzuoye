package com.example.myzuoye.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 本机的局域网地址。
 *
 * 用途：Wi-Fi Direct 在不少国产 ROM 上会被限制（搜索不到、建组失败、连上分不到地址），
 * 这时候「两台设备连同一个 Wi-Fi，直接输入对方 IP」是最可靠的兜底通道。
 * 现有 TCP 层完全复用，只是换个地址连。
 */
object LanAddress {

    /**
     * 本机可用的 IPv4 列表，按「适合用来连」排序。
     *
     * 排序理由：
     * - `wlan*` 是真正接路由器的网卡，优先；
     * - `p2p*` 是 Wi-Fi Direct 的虚拟网卡（192.168.49.x），只有在已经建组时才有意义，排后面；
     * - `ap*`/`swlan*` 是热点网卡，可以作为第三种选择；
     * - `tun*`/`rmnet*` 是 VPN 与移动数据，局域网连不通，直接排除。
     */
    fun localIpv4Candidates(): List<String> {
        val found = mutableListOf<Pair<Int, String>>()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.orEmpty().lowercase()
                val score = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("ap") || name.startsWith("swlan") -> 1
                    name.startsWith("p2p") -> 2
                    name.startsWith("tun") || name.startsWith("rmnet") ||
                        name.startsWith("ccmni") -> -1

                    else -> 3
                }
                if (score < 0) continue
                for (address in nif.inetAddresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        found += score to address.hostAddress.orEmpty()
                    }
                }
            }
        }
        return found.sortedBy { it.first }.map { it.second }.filter { it.isNotBlank() }.distinct()
    }

    /** 最适合展示给用户的那个地址（没有则 null，比如没连 Wi-Fi） */
    fun primaryIpv4(): String? = localIpv4Candidates().firstOrNull()
}
