package com.example.myzuoye.util

import android.Manifest
import android.os.Build

/**
 * 按系统版本推导「建立本地连接必需的运行时权限」。
 *
 * 抽成纯函数是因为这段分支最容易出错，而它一旦错就直接表现为
 * 「找不到设备」或「连上了却怎么也传不动」——必须有单测盯着。
 *
 * 版本要点：
 * - API 36（Android 16）起多了 `ACCESS_LOCAL_NETWORK`：它是本地网络访问的总开关。
 *   没有它，发往局域网 / Wi-Fi Direct 网段（192.168.49.x）/ 模拟器宿主的连接会被
 *   系统静默丢包，现象就是「组网成功、ping 通、TCP 一定超时」。
 * - API 33（Android 13）起用 `NEARBY_WIFI_DEVICES`，不再需要定位；
 *   早期版本只判断它，导致 Android 12 及以下永远「无权限」。
 * - API 29（Android 10）起写下载目录走 MediaStore，不再要存储权限。
 */
object AppPermissions {

    /** 本地网络访问权限的字符串常量（低版本系统不认识，会直接忽略） */
    const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    fun required(apiLevel: Int = Build.VERSION.SDK_INT): List<String> = when {
        apiLevel >= API_LOCAL_NETWORK -> listOf(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            ACCESS_LOCAL_NETWORK
        )

        apiLevel >= API_NEARBY_WIFI -> listOf(Manifest.permission.NEARBY_WIFI_DEVICES)

        apiLevel >= API_MEDIA_STORE -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)

        else -> listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    /** Android 16 / API 36：本地网络访问权限 */
    const val API_LOCAL_NETWORK = 36

    /** Android 13 / API 33：NEARBY_WIFI_DEVICES */
    const val API_NEARBY_WIFI = 33

    /** Android 10 / API 29：MediaStore 写公共目录 */
    const val API_MEDIA_STORE = 29
}
