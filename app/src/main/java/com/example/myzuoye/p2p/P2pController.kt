package com.example.myzuoye.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.net.MacAddress
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Wi-Fi Direct 控制层：把 WifiP2pManager 的一堆异步调用包成一套简单的接口。
 *
 * 约定：
 * - 所有回调都在主线程（initialize 用的 mainLooper），可以直接更新 UI。
 * - 需要 NEARBY_WIFI_DEVICES 权限的操作，调用方必须先确认已授权；内部仍会兜底捕获 SecurityException。
 * - 不持有 Activity / Context 的强引用以外的东西，[release] 之后不可再用。
 */
@SuppressLint("MissingPermission")
class P2pController(context: Context) {

    /** 日志回调，用于界面上的滚动日志区 */
    var onLog: ((String) -> Unit)? = null

    /** Wi-Fi P2P 开关状态变化 */
    var onP2pEnabled: ((Boolean) -> Unit)? = null

    /** 附近设备列表更新 */
    var onPeersChanged: ((List<WifiP2pDevice>) -> Unit)? = null

    /** 组已建立 */
    var onConnectionInfo: ((WifiP2pInfo) -> Unit)? = null

    /** 组已断开 */
    var onDisconnected: (() -> Unit)? = null

    /** 主动建组成功（本机确定成为组主），可用于提前启动 TCP 监听 */
    var onGroupCreated: (() -> Unit)? = null

    /** 本机设备信息更新 */
    var onThisDeviceChanged: ((WifiP2pDevice) -> Unit)? = null

    /** 发现流程状态 */
    var onDiscoveryChanged: ((Boolean) -> Unit)? = null

    private val appContext = context.applicationContext
    private val manager: WifiP2pManager? =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: WifiDirectReceiver? = null
    private var receiverRegistered = false

    val isSupported: Boolean get() = manager != null

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
    }

    private val receiverCallbacks = object : WifiDirectReceiver.Callbacks {
        override fun onP2pStateChanged(enabled: Boolean) {
            log(if (enabled) "Wi-Fi P2P 已开启" else "Wi-Fi P2P 已关闭")
            onP2pEnabled?.invoke(enabled)
        }

        override fun onPeersChanged(peers: List<WifiP2pDevice>) {
            onPeersChanged?.invoke(peers)
        }

        override fun onConnectionInfo(info: WifiP2pInfo) {
            onConnectionInfo?.invoke(info)
        }

        override fun onDisconnected() {
            onDisconnected?.invoke()
        }

        override fun onThisDeviceChanged(device: WifiP2pDevice) {
            onThisDeviceChanged?.invoke(device)
        }

        override fun onDiscoveryChanged(discovering: Boolean) {
            log(if (discovering) "正在搜索附近设备…" else "搜索已停止")
            onDiscoveryChanged?.invoke(discovering)
        }
    }

    /** 在 onStart 里调用：建立通道 + 注册广播 */
    fun start() {
        val mgr = manager ?: run {
            log("本机不支持 Wi-Fi Direct")
            return
        }

        if (channel == null) {
            channel = mgr.initialize(appContext, Looper.getMainLooper()) {
                // 通道断开通常意味着 Wi-Fi 被关掉或系统回收了资源
                log("P2P 通道已断开，请重新进入页面")
                channel = null
            }
        }
        val ch = channel ?: run {
            log("P2P 通道初始化失败")
            return
        }

        if (!receiverRegistered) {
            val r = WifiDirectReceiver(mgr, ch, receiverCallbacks)
            receiver = r
            try {
                ContextCompat.registerReceiver(
                    appContext,
                    r,
                    intentFilter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                receiverRegistered = true
            } catch (e: Exception) {
                log("注册广播失败：${e.message}")
            }
        }

        // 回到前台时同步一次当前连接状态（旋转屏幕后可恢复显示）
        requestConnectionInfo()
    }

    /** 在 onStop 里调用：注销广播，通道留着给下一次 onStart 复用 */
    fun stop() {
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }

    /** 在 onDestroy 里调用：彻底释放 */
    fun release() {
        stop()
        runCatching { channel?.close() }
        channel = null
        receiver = null
    }

    /** 开始搜索。内部先 stop 再 discover，避免第二次搜索直接返回 BUSY */
    fun startDiscovery() {
        val (mgr, ch) = requireChannel() ?: return
        try {
            mgr.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = discover(mgr, ch)
                override fun onFailure(reason: Int) = discover(mgr, ch)
            })
        } catch (e: SecurityException) {
            log("缺少附近设备权限，无法搜索：${e.message}")
        }
    }

    fun stopDiscovery() {
        val (mgr, ch) = requireChannel() ?: return
        runCatching { mgr.stopPeerDiscovery(ch, null) }
    }

    private fun discover(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        runCatching {
            mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("开始搜索附近设备…")
                }

                override fun onFailure(reason: Int) {
                    log("搜索失败：${reasonText(reason)}")
                }
            })
        }.onFailure { log("搜索异常：${it.message}") }
    }

    /** 主动刷新一次设备列表 */
    fun requestPeers() {
        val (mgr, ch) = requireChannel() ?: return
        runCatching {
            mgr.requestPeers(ch) { peers ->
                onPeersChanged?.invoke(peers?.deviceList?.toList().orEmpty())
            }
        }
    }

    /** 连接指定设备，谁当组主由系统协商决定 */
    fun connect(device: WifiP2pDevice) {
        val (mgr, ch) = requireChannel() ?: return

        val config = try {
            buildConfig(device.deviceAddress)
        } catch (e: IllegalArgumentException) {
            log("设备地址无效，无法连接：${e.message}")
            return
        } catch (e: Exception) {
            log("创建连接配置失败：${e.message}")
            return
        }

        runCatching {
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("已向对方发起连接请求，等待协商…")
                }

                override fun onFailure(reason: Int) {
                    log("连接失败：${reasonText(reason)}")
                    if (reason == WifiP2pManager.BUSY) {
                        log("提示：可能已有残留分组，请点“断开”后重试")
                    }
                }
            })
        }.onFailure { log("连接异常：${it.message}") }
    }

    /** 自己建组，本机成为组主（GO），适合“我是接收端”的场景 */
    fun createGroup() {
        val (mgr, ch) = requireChannel() ?: return
        runCatching {
            mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("已创建分组，本机为组主，等待对方连接…")
                    onGroupCreated?.invoke()
                    requestConnectionInfo()
                }

                override fun onFailure(reason: Int) {
                    log("创建分组失败：${reasonText(reason)}")
                }
            })
        }.onFailure { log("创建分组异常：${it.message}") }
    }

    /** 断开：取消正在进行的连接 + 移除分组 */
    fun disconnect() {
        val (mgr, ch) = requireChannel() ?: return
        runCatching {
            mgr.cancelConnect(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit
                override fun onFailure(reason: Int) = Unit
            })
        }
        runCatching {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("已断开并移除分组")
                }

                override fun onFailure(reason: Int) {
                    // 本来就没分组时返回 BUSY/ERROR 很正常，不当成错误
                    log("移除分组返回：${reasonText(reason)}")
                }
            })
        }.onFailure { log("断开异常：${it.message}") }
    }

    /**
     * API 36（Android 16）起 [WifiP2pConfig.Builder] 的 setDeviceAddress 改为接收 MacAddress，
     * 并且移除了 setWpsConfig。低版本仍需走公开字段 + WPS PBC，所以这里按版本分两条路，
     * 避免在旧机型上调用 API 36 才有的方法。
     */
    @Suppress("DEPRECATION")
    private fun buildConfig(address: String): WifiP2pConfig =
        if (Build.VERSION.SDK_INT >= API_36) {
            WifiP2pConfig.Builder()
                .setDeviceAddress(MacAddress.fromString(address))
                .build()
        } else {
            WifiP2pConfig().apply {
                deviceAddress = address
                wps.setup = WpsInfo.PBC
            }
        }

    private fun requestConnectionInfo() {
        val (mgr, ch) = requireChannel() ?: return
        runCatching {
            mgr.requestConnectionInfo(ch) { info ->
                if (info != null && info.groupFormed) {
                    onConnectionInfo?.invoke(info)
                } else {
                    onDisconnected?.invoke()
                }
            }
        }
    }

    private fun requireChannel(): Pair<WifiP2pManager, WifiP2pManager.Channel>? {
        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) {
            log("P2P 尚未就绪，请稍后重试")
            return null
        }
        return mgr to ch
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "本机不支持 Wi-Fi Direct"
        WifiP2pManager.BUSY -> "系统忙（已有连接或正在搜索）"
        WifiP2pManager.ERROR -> "内部错误（可尝试关闭再打开 Wi-Fi）"
        else -> "未知错误（$reason）"
    }

    private fun log(message: String) {
        onLog?.invoke(message)
    }

    private companion object {
        /** Android 16 / API 36 */
        const val API_36 = 36
    }
}
