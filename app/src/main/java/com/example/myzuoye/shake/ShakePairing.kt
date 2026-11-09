package com.example.myzuoye.shake

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import com.example.myzuoye.MainActivity
import com.example.myzuoye.R
import com.example.myzuoye.p2p.ShakeToken
import com.example.myzuoye.ui.MascotView
import com.example.myzuoye.ui.hapticSuccess
import com.example.myzuoye.util.ShakeDetector

/**
 * 摇一摇配对，从 MainActivity 拆出来的独立功能块。
 *
 * 做什么：摇一下 → 把自己的「口令」通过 Wi-Fi Direct 服务发现广播出去，
 * 同时找别人广播的口令；口令对得上（说明两边几乎同时摇的）就算配对成功，
 * 由标识较大的一台发起连接，另一台等邀请。
 *
 * 为什么这么设计：
 * - 组网之前两台设备没有 IP 通路，服务发现的实例名是唯一能带自定义文本的官方通道；
 * - 口令按 1.2 秒分桶、允许相邻桶，两边各自摇自己的，不需要对表也不需要联网；
 * - 一个人摇不会连上任何东西 —— 没有对端的口令，本机什么也不做。
 *
 * 依赖的 Activity 成员都以 internal 可见性开放（见 MainActivity），
 * 逻辑从原文件原样搬移，行为不变。
 */
internal class ShakePairing(private val host: MainActivity) {

    /** 加速度计与识别器 */
    private var sensorManager: SensorManager? = null
    private var shakeListening = false
    private val shakeDetector = ShakeDetector()

    /** 摇一摇窗口的截止时刻（elapsedRealtime），0 表示当前没在配对 */
    private var shakeUntil = 0L

    /** 本次摇一摇的口令与自身标识 */
    private var shakeToken = 0L
    private var shakeSelfId = ""

    /** 已经因为摇一摇「配对成功」了，避免重复庆祝（对面可能连报几次广播） */
    private var shakeMatched = false

    /** 摇一摇配对上之后延迟发起的连接（收服务发现和 connect 抢状态机，得错开） */
    private var shakeInvite: Runnable? = null

    /** 加速度计回调。抖动识别交给 [ShakeDetector]，这里只负责切到主线程的业务 */
    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val detected = shakeDetector.feed(
                event.values[0], event.values[1], event.values[2],
                SystemClock.elapsedRealtime()
            )
            if (detected) begin()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // ------------------------------------------------------------------ 入口

    /** 开始一轮摇一摇配对（加速度计触发 / 菜单手动触发都走这里） */
    fun begin() {
        if (host.isFinishing || !host.requestPermissionIfNeeded()) return
        if (!host.controller.isSupported) return
        if (host.p2pConnected) {
            host.showHint(host.getString(R.string.shake_pair_connected))
            return
        }
        if (host.connecting) {
            host.log("正在连接中，摇一摇先等一等")
            return
        }
        val now = SystemClock.elapsedRealtime()
        // 已经在配对窗口里就不重复触发：摇得越猛，广播越不该反复重建
        if (now < shakeUntil) return

        shakeUntil = now + SHAKE_WINDOW_MS
        shakeMatched = false
        shakeToken = ShakeToken.token(System.currentTimeMillis())
        if (shakeSelfId.isEmpty()) shakeSelfId = ShakeToken.deviceId(deviceRawId())
        val instance = ShakeToken.instance(shakeToken, shakeSelfId)
        host.controller.startShakeService(instance)

        host.flowBackground.burst()
        // 开始找人：好奇又期待的表情（惊讶脸）
        host.mascot.showCurious(host.getString(R.string.shake_pair_say))
        host.tvP2pState.hapticSuccess()
        host.showHint(host.getString(R.string.shake_pair_searching))
        host.log("摇一摇配对：口令 $shakeToken，等待同样在摇的手机…")

        host.uiHandler.removeCallbacks(shakeTimeout)
        host.uiHandler.postDelayed(shakeTimeout, SHAKE_WINDOW_MS)
        host.uiHandler.removeCallbacks(shakeNudge)
        host.uiHandler.postDelayed(shakeNudge, SHAKE_NUDGE_MS)
    }

    /** 听到某个对端的摇一摇广播。口令匹配才继续，其余一律忽略 */
    fun onShakePeerFound(instance: String, device: WifiP2pDevice?) {
        val parsed = ShakeToken.parse(instance) ?: return
        val (peerToken, peerId) = parsed
        // 自己广播的回声（部分 ROM 会把本地服务也报上来）
        if (peerId == shakeSelfId) return
        // 我们没在摇：对方在摇，但按约定要两边都摇才算配对
        if (shakeUntil == 0L) return
        if (!ShakeToken.matches(shakeToken, peerToken)) {
            host.log("摇一摇：听到一个口令不匹配的广播，忽略")
            return
        }
        if (shakeMatched) return
        shakeMatched = true

        host.uiHandler.removeCallbacks(shakeTimeout)
        host.uiHandler.removeCallbacks(shakeNudge)
        shakeUntil = 0L
        // 先把服务发现收掉：它和 connect 会抢系统的 P2P 状态机
        host.controller.stopShakeService()

        host.flowBackground.burst()
        // 配对成功：害羞
        host.mascot.showShy(host.getString(R.string.shake_pair_matched_say))
        host.tvP2pState.hapticSuccess()
        host.showHint(host.getString(R.string.shake_pair_matched), MainActivity.HintKind.SUCCESS)
        host.log("摇一摇配对成功：口令 $peerToken")
        // 第二波冲击波晚一点，像两颗心撞上之后的余震
        host.uiHandler.postDelayed({ host.flowBackground.burst(0xFF60A5FA.toInt()) }, 420L)

        if (device == null) {
            host.log("没拿到对端设备信息，等对方来邀")
            return
        }

        // 收掉服务发现后系统还要一小会儿才回到空闲，立刻 connect 常常被判 BUSY，
        // 表现就是「配对成功了但一直连不上」。所以先错开 600ms 再发。
        val inviter = ShakeToken.shouldInvite(shakeSelfId, peerId)
        val delay = if (inviter) SHAKE_CONNECT_DELAY_MS else SHAKE_WAIT_BEFORE_RETRY_MS
        host.log(
            if (inviter) "由本机发起连接（${host.displayName(device)}）"
            else "等对方发起连接…"
        )
        shakeInvite?.let { host.uiHandler.removeCallbacks(it) }
        var attempt = 0
        val task = object : Runnable {
            override fun run() {
                shakeInvite = null
                if (host.p2pConnected) return
                if (host.connecting) {
                    // 上一次还在协商：稍后再看，别硬挤
                    shakeInvite = this
                    host.uiHandler.postDelayed(this, SHAKE_INVITE_RETRY_MS)
                    return
                }
                if (attempt == 0) {
                    host.log(if (inviter) "开始发起连接" else "等对方发起连接…")
                } else {
                    host.log(
                        if (inviter) "对方还没接上，再邀请一次（第 ${attempt + 1} 次）"
                        else "对方还没动静，这次由本机试一下"
                    )
                }
                connectShakePeer(device)

                // 对端可能正忙着做服务发现（此时它收不到邀请），
                // 所以邀请要隔几秒重来一次，直到对面腾出手来。
                attempt++
                if (attempt < SHAKE_INVITE_TRIES) {
                    shakeInvite = this
                    host.uiHandler.postDelayed(this, SHAKE_INVITE_RETRY_MS)
                }
            }
        }
        shakeInvite = task
        host.uiHandler.postDelayed(task, delay)
    }

    /** 开始听加速度计（界面可见时调用） */
    fun startListening() {
        if (shakeListening) return
        val mgr = host.getSystemService(SensorManager::class.java) ?: return
        val sensor = mgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            host.log("本机没有加速度计，摇一摇不可用（可用右上角菜单手动触发）")
            return
        }
        sensorManager = mgr
        shakeDetector.reset()
        runCatching {
            mgr.registerListener(shakeListener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }.onSuccess { shakeListening = true }
            .onFailure { host.log("加速度计注册失败：${it.message}") }
    }

    /** 停止听加速度计（界面不可见时调用） */
    fun stopListening() {
        if (!shakeListening) return
        runCatching { sensorManager?.unregisterListener(shakeListener) }
        shakeListening = false
        shakeDetector.reset()
    }

    /**
     * 结束配对窗口：停掉服务发现与窗口内的定时任务。
     *
     * 界面退后台 / 窗口超时都会走这里；已配对成功的路径不会进来
     * （配对成功时窗口已被清零，超时任务直接返回）。
     */
    fun end() {
        shakeUntil = 0L
        host.uiHandler.removeCallbacks(shakeTimeout)
        host.uiHandler.removeCallbacks(shakeNudge)
        host.controller.stopShakeService()
    }

    /** 只撤掉待发的邀请（界面销毁/退后台时用，不动服务发现） */
    fun cancelInvite() {
        shakeInvite?.let { host.uiHandler.removeCallbacks(it) }
        shakeInvite = null
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 摇一摇配对成功后的连接。
     *
     * 服务发现回调给的设备对象**不一定在系统的设备缓存里**——直接 connect 会被
     * 系统当「未知设备」丢弃，日志里是「连接失败：内部错误」。所以先拉一次设备列表：
     * 对方在就用列表里的对象连；不在就短暂开一次搜索把缓存填上，再试几次。
     */
    private fun connectShakePeer(target: WifiP2pDevice, attempt: Int = 0) {
        if (host.p2pConnected) return
        // 每次都补一发搜索：服务发现期间系统的设备搜索是停着的，
        // 缓存里没有对方的话 connect 会被直接丢弃（Dropping connect request）
        host.controller.startDiscovery()
        host.controller.requestPeersOnce { peers ->
            if (host.p2pConnected || host.connecting) return@requestPeersOnce
            val found = peers.firstOrNull {
                it.deviceAddress.equals(target.deviceAddress, ignoreCase = true)
            }
            if (found != null) {
                host.onDeviceClicked(found)
            } else if (attempt >= SHAKE_PEER_WAIT_TRIES) {
                host.log("设备列表一直没刷出对方，改用服务发现给的信息直接连")
                host.onDeviceClicked(target)
            } else {
                host.log("等系统刷新出对方再连（第 ${attempt + 1} 次）")
                host.uiHandler.postDelayed({ connectShakePeer(target, attempt + 1) }, SHAKE_PEER_WAIT_MS)
            }
        }
    }

    /**
     * 配对窗口过半还没消息时的一句话。
     *
     * 服务发现在模拟器上经常要十秒才回来，光看「配对中」很容易以为卡死了；
     * 这里只是补充提示，不改变窗口，也不影响后面的匹配。
     */
    private val shakeNudge = Runnable {
        if (shakeMatched || shakeUntil == 0L) return@Runnable
        host.showHint(host.getString(R.string.shake_pair_nudge))
    }

    private val shakeTimeout = Runnable {
        if (shakeMatched || shakeUntil == 0L) return@Runnable
        host.uiHandler.removeCallbacks(shakeNudge)
        end()
        host.mascot.setMood(MascotView.MOOD_IDLE)
        host.mascot.say(host.getString(R.string.shake_pair_say))
        host.showHint(host.getString(R.string.shake_pair_timeout))
        host.log("摇一摇：窗口结束，没找到同样在摇的手机")
    }

    /** ANDROID_ID：同一台设备稳定、不同设备不同，用来生成摇一摇的标识 */
    private fun deviceRawId(): String? = runCatching {
        Settings.Secure.getString(host.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull()

    private companion object {
        /**
         * 摇一摇的配对窗口。
         *
         * 定 20 秒是因为服务发现的响应可能很慢（模拟器上实测要 11 秒才回来）。
         * 真机上通常两三秒就配对成功，这 20 秒只是兜底，不会真的等满。
         */
        const val SHAKE_WINDOW_MS = 20_000L

        /** 窗口过半时补一句提示，避免「一直在配对中」看起来像卡死 */
        const val SHAKE_NUDGE_MS = 8_000L

        /** 配对成功后延迟多久再发起连接：让系统从服务发现里收尾，否则 connect 会被判忙 */
        const val SHAKE_CONNECT_DELAY_MS = 1_200L

        /** 等待方等多久还没被邀就连过去：只是兜底，正常路径上根本走不到 */
        const val SHAKE_WAIT_BEFORE_RETRY_MS = 4_500L

        /** 等系统把对方刷进设备缓存的间隔与次数（连接前必须让对方「可连接」） */
        const val SHAKE_PEER_WAIT_MS = 1_500L
        const val SHAKE_PEER_WAIT_TRIES = 6

        /** 邀请重试间隔与次数：对端在服务发现期间收不到邀请，只能隔一会儿再来 */
        const val SHAKE_INVITE_RETRY_MS = 1_200L
        const val SHAKE_INVITE_TRIES = 14
    }
}
