package com.example.myzuoye.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean

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

    /** 收到对端连接请求（邀请）。返回 true 表示接受，false 表示拒绝 */
    var onConnectionRequest: ((WifiP2pDevice) -> Boolean)? = null

    /** 连接最终失败（含重试后仍失败），用于清掉界面上的“连接中”标记 */
    var onConnectFailed: ((String) -> Unit)? = null

    /** 搜索失败（reason 为 WifiP2pManager 的错误码），界面据此做退避重试 */
    var onDiscoveryFailed: ((Int) -> Unit)? = null

    /** P2P 网络绑定完成，参数是绑定到的 [Network]，供传输层做 socket 级绑定 */
    var onP2pNetworkBound: ((Network) -> Unit)? = null

    private val appContext = context.applicationContext
    private val manager: WifiP2pManager? =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: WifiDirectReceiver? = null
    private var receiverRegistered = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 是否正在执行 discoverPeers。由 DISCOVERY_CHANGED 广播与 discover 回调共同维护：
     * 搜索中再调 discoverPeers 会被系统判 BUSY，先 stop 再 discover 又要多等一次异步往返，
     * 所以这个状态决定了「重新发起」还是「只刷列表」。
     */
    @Volatile
    private var discovering = false

    /** 当前是否处于搜索状态（界面用，避免重复触发） */
    val isDiscovering: Boolean get() = discovering

    /** connect 前 stopPeerDiscovery 的兜底回调，防止个别 ROM 不回调导致点设备没反应 */
    private var connectFallback: Runnable? = null

    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)

    /** 当前绑定到的 P2P 网络（用于断开时解绑） */
    private var p2pNetwork: Network? = null

    /** 轮询等待 P2P 网络注册的 Runnable（用于取消） */
    private var networkPollRunnable: Runnable? = null

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
            this@P2pController.discovering = discovering
            log(if (discovering) "正在搜索附近设备…" else "搜索已停止")
            onDiscoveryChanged?.invoke(discovering)
        }

        override fun onConnectionRequest(requestingDevice: WifiP2pDevice) {
            val accept = onConnectionRequest?.invoke(requestingDevice) ?: true
            val receiver = this@P2pController.receiver
            if (accept) {
                log("接受来自 ${requestingDevice.deviceName} 的连接请求")
                receiver?.acceptConnectionRequest(requestingDevice)
            } else {
                log("拒绝来自 ${requestingDevice.deviceName} 的连接请求")
                receiver?.rejectConnectionRequest(requestingDevice)
            }
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
        connectFallback?.let { mainHandler.removeCallbacks(it) }
        connectFallback = null
        networkPollRunnable?.let { mainHandler.removeCallbacks(it) }
        networkPollRunnable = null
        unbindP2pNetwork()
        stop()
        runCatching { channel?.close() }
        channel = null
        receiver = null
    }

    /**
     * 开始搜索附近设备。
     *
     * 速度上做两件事：
     * 1. 已经在搜索时不再重复 discoverPeers（系统会返回 BUSY），直接拉一次列表即可；
     * 2. 未在搜索时直接 discoverPeers，不再固定先 stopPeerDiscovery ——
     *    少一次异步往返，在多数机型上能省 0.3~1 秒；只有真的返回 BUSY 时才走「停一次再重试」。
     */
    fun startDiscovery() {
        val (mgr, ch) = requireChannel() ?: return

        if (discovering) {
            requestPeers()
            return
        }

        try {
            mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    discovering = true
                    log("开始搜索附近设备…")
                    // 立刻拉一次：系统缓存里可能已有上次发现的设备，能马上上屏
                    requestPeers()
                }

                override fun onFailure(reason: Int) {
                    discovering = false
                    if (reason == WifiP2pManager.BUSY) {
                        // 系统仍在处理上一次搜索/连接。这里**不要**再 stop→discover 硬撞：
                        // 那会让系统一直停在“重置中”，越试越忙。交给界面按退避策略稍后重试。
                        log("系统忙，搜索稍后自动重试")
                    } else {
                        log("搜索失败：${reasonText(reason)}")
                    }
                    onDiscoveryFailed?.invoke(reason)
                }
            })
        } catch (e: SecurityException) {
            log("缺少附近设备权限，无法搜索：${e.message}")
        }
    }

    fun stopDiscovery() {
        val (mgr, ch) = requireChannel() ?: return
        discovering = false
        runCatching { mgr.stopPeerDiscovery(ch, null) }
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
            onConnectFailed?.invoke("设备地址无效")
            return
        } catch (e: Exception) {
            log("创建连接配置失败：${e.message}")
            onConnectFailed?.invoke("创建连接配置失败")
            return
        }

        // 这里**不要**先 stopPeerDiscovery。
        //
        // 实测（Android 17 模拟器）日志：
        //   P2P-DEVICE-LOST p2p_dev_addr=...
        //   WifiP2pService: Dropping connect request
        // 停搜索会把已发现的设备从系统缓存里清掉，紧接着的 connect 会因为
        // “目标设备不在已知列表”被系统直接丢弃，表现就是「内部错误」。
        //
        // 标准写法是 requestPeers → connect，搜索过程本身不阻碍连接；
        // 只有真的返回 BUSY（多是残留分组）才需要清理后重试。
        doConnect(mgr, ch, config)
    }

    private fun doConnect(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        config: WifiP2pConfig,
        retried: Boolean = false
    ) {
        runCatching {
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("已向对方发起连接请求，等待协商…")
                }

                override fun onFailure(reason: Int) {
                    // BUSY 多半是上一次失败留下残连接，自动清理后重试一次，
                    // 免得用户还得手动点“断开”再来一遍。
                    // 注意这里不能再 stopPeerDiscovery —— 那会把设备缓存清掉，
                    // 重试的 connect 又会被系统判定为“未知设备”而丢弃。
                    if (reason == WifiP2pManager.BUSY && !retried) {
                        log("系统忙，清理残留连接后重试…")
                        runCatching {
                            mgr.cancelConnect(ch, object : WifiP2pManager.ActionListener {
                                override fun onSuccess() = retryConnectSoon(mgr, ch, config)
                                override fun onFailure(r: Int) = retryConnectSoon(mgr, ch, config)
                            })
                        }.onFailure { retryConnectSoon(mgr, ch, config) }
                        return
                    }

                    val text = reasonText(reason)
                    log("连接失败：$text")
                    onConnectFailed?.invoke(text)
                }
            })
        }.onFailure {
            log("连接异常：${it.message}")
            onConnectFailed?.invoke(it.message ?: "连接异常")
        }
    }

    /** 清完残留连接后稍微等一下再重试，让系统状态落定 */
    private fun retryConnectSoon(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        config: WifiP2pConfig
    ) {
        mainHandler.postDelayed(
            { doConnect(mgr, ch, config, retried = true) },
            CONNECT_RETRY_DELAY_MS
        )
    }

    /**
     * 先停掉正在进行的设备发现，再执行 [action]。
     *
     * 只用于 [createGroup] 这类「不需要目标设备」的操作 —— 停搜索会把已发现的
     * 设备从系统缓存里清掉，紧接着按地址 connect 会被系统当成未知设备丢弃
     * （日志里的 `Dropping connect request`），所以连接路径绝对不能用它。
     *
     * 为什么要有超时兜底：个别 ROM 的 stopPeerDiscovery 回调不返回，
     * 一直等就表现为「点了没反应」，超时后照常执行 [action]。
     */
    private fun withDiscoveryStopped(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        timeoutLog: String,
        action: () -> Unit
    ) {
        val started = AtomicBoolean(false)

        fun begin() {
            if (!started.compareAndSet(false, true)) return
            connectFallback?.let { mainHandler.removeCallbacks(it) }
            connectFallback = null
            action()
        }

        val fallback = Runnable {
            log(timeoutLog)
            begin()
        }
        connectFallback = fallback
        mainHandler.postDelayed(fallback, STOP_DISCOVERY_TIMEOUT_MS)

        runCatching {
            mgr.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    discovering = false
                    begin()
                }

                override fun onFailure(reason: Int) = begin()
            })
        }.onFailure {
            log("停止搜索失败：${it.message}")
            begin()
        }
    }

    /** 自己建组，本机成为组主（GO），适合“我是接收端”的场景 */
    fun createGroup() {
        val (mgr, ch) = requireChannel() ?: return
        // 建组前同样先停发现，否则系统可能直接返回 BUSY
        withDiscoveryStopped(mgr, ch, "停止搜索超时，直接建组") {
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
    }

    /**
     * 把本进程绑定到 P2P 网络，之后再建 TCP 通道。
     *
     * 为什么必须做：Android 10 起每个网络有独立 netId 与路由表，进程默认走系统
     * **默认网络**（Wi-Fi / 移动数据）。默认网络的路由表里没有 192.168.49.0/24，
     * 于是连 192.168.49.1:8988 的包会从默认网卡发出去，直接超时 ——
     * 现象正是「P2P 组已建立、ping 也通，但 TCP 死活连不上」。
     *
     * 拿 P2P 网络的方式：遍历 [ConnectivityManager.getAllNetworks]，找出网卡地址落在
     * P2P 网段（192.168.49.0/24）的那个。它通常带 LOCAL_NETWORK 能力、不带 INTERNET，
     * 所以不会成为默认网络，必须显式绑定。
     *
     * P2P 组的网络注册到系统有延迟，所以这里轮询等待，并带总超时兜底。
     */
    fun prepareP2pNetwork(onReady: () -> Unit) {
        if (Build.VERSION.SDK_INT < API_29) {
            onReady()
            return
        }
        // 已经绑定过就直接继续，避免连接状态广播重复触发时反复处理
        if (p2pNetwork != null) {
            onReady()
            return
        }
        val cm = connectivityManager
        if (cm == null) {
            onReady()
            return
        }

        val done = AtomicBoolean(false)

        fun finish() {
            if (!done.compareAndSet(false, true)) return
            networkPollRunnable?.let { mainHandler.removeCallbacks(it) }
            networkPollRunnable = null
            onReady()
        }

        val deadline = SystemClock.elapsedRealtime() + NETWORK_READY_TIMEOUT_MS

        fun poll() {
            if (done.get()) return

            val net = findP2pNetwork()
            if (net != null) {
                val bound = runCatching { cm.bindProcessToNetwork(net) }
                    .getOrDefault(false)
                if (bound) {
                    p2pNetwork = net
                    // 读回来确认绑定是否真的生效：部分 ROM 会返回 true 但实际没绑上
                    val actual = runCatching { cm.boundNetworkForProcess }.getOrNull()
                    log("已绑定 P2P 网络（进程默认网络=$actual）")
                    onP2pNetworkBound?.invoke(net)
                } else {
                    log("绑定 P2P 网络失败，按默认网络尝试")
                }
                finish()
                return
            }

            if (SystemClock.elapsedRealtime() >= deadline) {
                log("未找到 P2P 网络，按默认网络尝试")
                finish()
                return
            }

            val next = Runnable { poll() }
            networkPollRunnable = next
            mainHandler.postDelayed(next, NETWORK_POLL_INTERVAL_MS)
        }

        poll()
    }

    /** 找出地址落在 P2P 网段的那个网络 */
    private fun findP2pNetwork(): Network? {
        val cm = connectivityManager ?: return null
        return runCatching {
            cm.allNetworks.firstOrNull { net ->
                val link = cm.getLinkProperties(net) ?: return@firstOrNull false
                link.linkAddresses.any { addr ->
                    val ip = addr.address
                    ip is Inet4Address &&
                        ip.hostAddress?.startsWith(P2P_SUBNET_PREFIX) == true
                }
            }
        }.getOrNull()
    }

    /** 解绑 P2P 网络，恢复默认网络 */
    private fun unbindP2pNetwork() {
        if (p2pNetwork == null) return
        runCatching { connectivityManager?.bindProcessToNetwork(null) }
        p2pNetwork = null
    }

    /**
     * 外部把进程网络切回默认后同步这里的记录。
     *
     * 场景：组网成功后用户改走局域网直连/扫码（TransferManager.setP2pNetwork(null)
     * 会解绑进程网络），但这里的 [p2pNetwork] 还留着旧值，
     * [prepareP2pNetwork] 会因为「已绑定过」提前返回，导致再做 P2P 传输时
     * 进程仍在默认网络、连 192.168.49.1 直接超时。
     */
    fun forgetBoundNetwork() {
        p2pNetwork = null
    }

    /** 断开：取消正在进行的连接 + 移除分组 */
    fun disconnect() {
        val (mgr, ch) = requireChannel() ?: return
        unbindP2pNetwork()
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

        /** Android 10 / API 29：requestNetwork / bindProcessToNetwork 从这版开始可用 */
        const val API_29 = 29

        /** stopPeerDiscovery 的兜底超时：到点不再等回调，直接执行后续动作 */
        const val STOP_DISCOVERY_TIMEOUT_MS = 800L

        /** 清理残留连接后，等这么久再重试 connect */
        const val CONNECT_RETRY_DELAY_MS = 300L

        /** 等 P2P 网络就绪的兜底超时 */
        const val NETWORK_READY_TIMEOUT_MS = 1500L

        /** 轮询 P2P 网络注册的间隔 */
        const val NETWORK_POLL_INTERVAL_MS = 150L

        /** Wi-Fi Direct 组网固定使用的网段前缀 */
        const val P2P_SUBNET_PREFIX = "192.168.49."
    }
}
