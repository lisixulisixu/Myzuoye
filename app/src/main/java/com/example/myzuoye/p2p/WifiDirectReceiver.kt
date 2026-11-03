package com.example.myzuoye.p2p

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.MacAddress
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build

/**
 * Wi-Fi Direct 系统广播接收器。
 *
 * 只负责把广播翻译成回调，不持有 Activity、不做业务判断。
 * 所有 WifiP2pManager 的调用都需要 NEARBY_WIFI_DEVICES 权限，调用方已经在拿到权限后才会注册本接收器。
 */
@SuppressLint("MissingPermission")
class WifiDirectReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val callbacks: Callbacks
) : BroadcastReceiver() {

    interface Callbacks {
        /** Wi-Fi P2P 硬件开关状态变化 */
        fun onP2pStateChanged(enabled: Boolean)

        /** 附近设备列表更新 */
        fun onPeersChanged(peers: List<WifiP2pDevice>)

        /** 组已建立，拿到组信息（角色 / 组主地址） */
        fun onConnectionInfo(info: WifiP2pInfo)

        /** 组已断开 */
        fun onDisconnected()

        /** 本机设备信息变化（名称 / 状态） */
        fun onThisDeviceChanged(device: WifiP2pDevice)

        /** 发现流程开始 / 结束 */
        fun onDiscoveryChanged(discovering: Boolean)

        /**
         * 收到对端的连接请求（邀请）。requestingDevice 为发起邀请的设备，
         * 调用方需要决定接受 / 拒绝，然后回调 [acceptConnectionRequest] / [rejectConnectionRequest]。
         */
        fun onConnectionRequest(requestingDevice: WifiP2pDevice)
    }

    /** 接受连接请求（自动，WPS PBC 无密码） */
    fun acceptConnectionRequest(requestingDevice: WifiP2pDevice) {
        setConnectionRequestResult(requestingDevice, true)
    }

    /** 拒绝连接请求 */
    fun rejectConnectionRequest(requestingDevice: WifiP2pDevice) {
        setConnectionRequestResult(requestingDevice, false)
    }

    private fun setConnectionRequestResult(requestingDevice: WifiP2pDevice, accept: Boolean) {
        val result = if (accept) {
            WifiP2pManager.CONNECTION_REQUEST_ACCEPT
        } else {
            WifiP2pManager.CONNECTION_REQUEST_REJECT
        }

        // MacAddress 类自 API 28 引入；setConnectionRequestResult 的 String 重载在 API 36 被移除，
        // 所以统一走 MacAddress 版本。低于 API 28 的设备无法自动受理，只能靠用户手动点设备连接。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        val mac = MacAddress.fromString(requestingDevice.deviceAddress) ?: return
        runCatching {
            manager.setConnectionRequestResult(channel, mac, result, null)
        }
    }

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                callbacks.onP2pStateChanged(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                // 列表变化后必须主动去问一次，广播本身不带设备列表
                runCatching {
                    manager.requestPeers(channel) { peers ->
                        callbacks.onPeersChanged(peers?.deviceList?.toList().orEmpty())
                    }
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo = intent.getParcelableExtra(
                    WifiP2pManager.EXTRA_NETWORK_INFO,
                    NetworkInfo::class.java
                )

                // 关键：连接请求（邀请）广播带 EXTRA_WIFI_P2P_DEVICE，表示有设备请求接入，
                // 此时 NetworkInfo 可能尚未 isConnected，需要优先受理邀请。
                val requestingDevice = intent.getParcelableExtra(
                    WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                    WifiP2pDevice::class.java
                )

                if (requestingDevice != null) {
                    callbacks.onConnectionRequest(requestingDevice)
                    return
                }

                if (networkInfo?.isConnected == true) {
                    runCatching {
                        manager.requestConnectionInfo(channel) { info ->
                            if (info != null && info.groupFormed) {
                                callbacks.onConnectionInfo(info)
                            } else {
                                callbacks.onDisconnected()
                            }
                        }
                    }
                } else {
                    callbacks.onDisconnected()
                }
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                intent.getParcelableExtra(
                    WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                    WifiP2pDevice::class.java
                )?.let(callbacks::onThisDeviceChanged)
            }

            WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)
                callbacks.onDiscoveryChanged(state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED)
            }
        }
    }
}
