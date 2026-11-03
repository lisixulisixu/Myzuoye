package com.example.myzuoye.p2p

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager

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
