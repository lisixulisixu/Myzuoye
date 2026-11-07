package com.example.myzuoye

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ProgressBar
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myzuoye.net.LanAddress
import com.example.myzuoye.p2p.P2pController
import com.example.myzuoye.transfer.ChatEntry
import com.example.myzuoye.transfer.PartialStore
import com.example.myzuoye.transfer.QrImage
import com.example.myzuoye.transfer.QrPair
import com.example.myzuoye.transfer.SendHistory
import com.example.myzuoye.transfer.SendItem
import com.example.myzuoye.transfer.SendQueue
import com.example.myzuoye.transfer.TransferManager
import com.example.myzuoye.ui.AppDialog
import com.example.myzuoye.ui.ChatAdapter
import com.example.myzuoye.ui.ConnectionStepsView
import com.example.myzuoye.ui.CoverLiveView
import com.example.myzuoye.ui.DeviceAdapter
import com.example.myzuoye.ui.FlowBackgroundView
import com.example.myzuoye.ui.attachPressFeedback
import com.example.myzuoye.ui.hapticError
import com.example.myzuoye.ui.hapticSuccess
import com.example.myzuoye.ui.MascotView
import com.example.myzuoye.ui.TransferProgressView
import com.example.myzuoye.util.Formatters
import com.example.myzuoye.weather.WeatherRepository
import com.example.myzuoye.widget.TransferWidgetProvider
import com.example.myzuoye.widget.WidgetState
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * M1：授权 → 搜索 → 连接 → 显示角色与组主地址。
 * M2：建立连接后自动拉起 TCP 文本通道（组主监听、客户端连接），双向互发文本。
 * M3：文本通道之上增加单向文件传输（发送方选文件 → 协议头 + 分片 → 接收方落盘 + 进度）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var controller: P2pController
    private lateinit var adapter: DeviceAdapter

    private lateinit var tvP2pState: TextView
    private lateinit var tvSelfInfo: TextView
    private lateinit var tvRoleInfo: TextView
    private lateinit var tvPeerHint: TextView
    private lateinit var tvLog: TextView
    private lateinit var rvDevices: RecyclerView
    private lateinit var tvDevicesEmpty: View
    private lateinit var btnDiscover: Button
    private lateinit var btnCreateGroup: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnPlayGame: Button
    private lateinit var btnEmptySearch: Button

    /** 连接四步进度（发现 → 协商 → 组网 → 通道） */
    private lateinit var steps: ConnectionStepsView

    /** 封面动态插画：长发网格形变、柔光光斑、传输完成时爱心迸发 */
    private lateinit var coverLive: CoverLiveView

    /** 流动背景：连接状态变化时整体染一层状态色 */
    private lateinit var flowBackground: FlowBackgroundView

    /** 内联提示条（替代 Toast） */
    private lateinit var tvHint: TextView
    private lateinit var tvLogToggle: TextView
    private lateinit var logHeader: View

    /** 日志区是否收起 */
    private var logCollapsed = false

    /** 当前连接进行到第几步（失败时保留现场，方便把红点画在正确的位置） */
    private var currentStep = ConnectionStepsView.STEP_DISCOVER

    /** 最近一次通道状态：搜索循环会周期性调用 startDiscovery，不能在已连接时把进度打回第一步 */
    private var channelState = TransferManager.State.IDLE

    private lateinit var tvChannelState: TextView
    private lateinit var rvChat: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button

    private lateinit var btnPickFile: Button
    private lateinit var btnViewFiles: Button
    private lateinit var pbTransfer: TransferProgressView
    private lateinit var tvTransferState: TextView
    private lateinit var btnCancelTransfer: TextView

    /** Q 版人物形象 */
    private lateinit var mascot: MascotView

    /** 待发送的文件信息：uri + 显示名 + 大小。由发送队列在「轮到它」时填充 */
    private var pendingUri: Uri? = null
    private var pendingName: String = ""
    private var pendingSize: Long = 0

    /** 发送队列：多选的文件排队一个个发 */
    private lateinit var sendQueue: SendQueue

    /**
     * 长按「取消」暂停下来的那个文件。
     *
     * 暂停 = 停掉当前这次的传输，但记住发的是哪个文件；点「继续发送」
     * 把它重新入队，大文件会靠对端留下的半成品接着传，不用从头再来。
     */
    private var pausedSend: SendItem? = null

    /** 用户主动点了取消：用来把「发送失败」的文案换成「已取消」，别让人以为出了故障 */
    private var userCancelled = false

    /** 从别的应用分享进来但当时还没连接的文件，连上后自动排队 */
    private var pendingShareUris: List<Uri> = emptyList()

    /** 「改用局域网直连」的提示只给一次 */
    private var p2pHintShown = false

    /** 最近一次主动连接的设备名，连上后写到小组件上显示「已连接 XXX」 */
    private var connectingPeerName = ""

    /** 传输期间弹出的小游戏窗口 */
    private var transferGame: TransferGame? = null

    /**
     * 用户是否已经选择「传输时不玩小游戏」。
     *
     * 存在 SharedPreferences 里持久化：用户选过一次「不玩」之后，后续传输
     * 不再弹游戏窗口、也不再询问，避免每次传文件都被打扰。
     * 想恢复的话长按「玩游戏」按钮即可。
     */
    private var gamePromptDisabled = false

    private val logLines = ArrayDeque<String>()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** 已经弹过一次权限框，避免每次 onStart 反复打扰 */
    private var permissionAsked = false

    /** 权限请求正在进行中（系统弹窗还开着），此时不再重复发起 */
    private var permissionRequestInFlight = false

    /** 「定位未打开」提示只弹一次 */
    private var locationWarned = false

    /** 防止 onStart 与广播回调叠加触发重复搜索 */
    private var lastDiscoverAt = 0L

    /** 当前是否处于发现流程中，用于避免状态文案被广播回调覆盖 */
    private var searching = false

    /** 上一次的设备数量，用于日志降噪（列表刷新很频繁） */
    private var lastPeerCount = -1

    /** 搜索失败后的退避截止时间，避免对着 BUSY 硬撞 */
    private var discoveryBackoffUntil = 0L

    /** P2P 组是否已建立 */
    private var p2pConnected = false

    /** 是否正在发起连接（协商中），此时不要自动续搜，否则会打断 GO 协商 */
    private var connecting = false

    private val uiHandler = Handler(Looper.getMainLooper())

    /**
     * 自动续搜。
     *
     * 系统的 discoverPeers 一段时间后会自行停止（通常 30 秒左右），停了以后
     * PEERS_CHANGED 不再触发，列表就永远是旧的。这里在界面可见时定期检查：
     * 停了就重新发起，还在搜就只刷一次列表，保证「附近设备」一直是最新的。
     */
    private val discoveryLoop = object : Runnable {
        override fun run() {
            if (controller.isSupported && !p2pConnected && !connecting && hasNearbyPermission()) {
                when {
                    // 刚失败过：先退避，别对着系统的 BUSY 硬撞
                    SystemClock.elapsedRealtime() < discoveryBackoffUntil -> Unit
                    controller.isDiscovering -> controller.requestPeers()
                    else -> {
                        // 绕过按钮的防抖，但保留一次最小间隔
                        lastDiscoverAt = 0L
                        startDiscovery()
                    }
                }
            }
            uiHandler.postDelayed(this, DISCOVERY_KEEPALIVE_MS)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionRequestInFlight = false
            // 以“必需权限是否真的拿到”为准，通知权限被拒不影响连接
            if (hasNearbyPermission()) {
                log("已获得设备搜索权限")
                startDiscovery()
            } else {
                log("设备搜索权限被拒绝")
                tvP2pState.setText(R.string.p2p_state_no_permission)
                if (!shouldShowRationale()) {
                    showPermissionSettingsDialog()
                }
            }
            // 系统弹窗刚关掉，隔一小会儿再问天气定位，避免两个弹窗叠在一起
            uiHandler.postDelayed({ askWeatherLocationIfNeeded() }, 400L)
        }

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            enqueueFiles(uris)
        }

    /**
     * 天气小组件的「大致位置」授权。
     *
     * 和传文件用的权限完全分开：传文件在 Android 13+ 走 NEARBY_WIFI_DEVICES（neverForLocation），
     * 这里单独申请 COARSE，用户拒绝也不影响任何传输功能。
     */
    private val weatherLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                log("已获得大致位置权限，开始获取天气")
                WeatherRepository.refreshAsync(this, force = true)
            } else {
                log("未授权位置，小组件不显示天气")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        permissionAsked = savedInstanceState?.getBoolean(KEY_PERMISSION_ASKED) ?: false
        // 配对码校验是持久开关：重启后要重新交给传输层，否则会静默失效
        restorePairCode()
        // 读取「传输时不玩小游戏」偏好
        gamePromptDisabled = prefs().getBoolean(KEY_GAME_PROMPT_DISABLED, false)

        bindViews()
        applyWindowInsets()

        adapter = DeviceAdapter { device -> onDeviceClicked(device) }
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = adapter

        controller = P2pController(this)
        wireController()

        // M3：接收文件保存到公共「下载/WiFi直传」目录，由 ReceivedFileStore 按版本选择实现
        // M4：attach 应用上下文，供前台服务保活使用
        TransferManager.attach(this)

        // 清掉过期的大文件半成品（断点续传用），只在启动时扫一次，不进传输路径
        PartialStore.cleanup(this)

        sendQueue = SendQueue(
            opener = { uri -> runCatching { contentResolver.openInputStream(uri) }.getOrNull() },
            onItemStart = { item, index, total ->
                pendingUri = item.uri
                pendingName = item.name
                pendingSize = item.size
                if (total > 1) log("发送第 $index/$total 个：${item.name}")
                // Q 版人物：传输开始提醒
                mascot.showTransferring()
                // 游戏只问一次（排在队首那一个），后面几个不再反复打扰
                if (index == 1 && !gamePromptDisabled) askToPlayGame(item.name)
            },
            onItemDone = { item, ok ->
                if (!ok) log("发送失败：${item.name}")
                val left = sendQueue.pendingCount
                if (left > 0) toast(getString(R.string.send_queue_next, left))
            },
            onAllDone = { okCount, failCount ->
                tvTransferState.text = if (failCount == 0) {
                    getString(R.string.send_queue_all_done, okCount)
                } else {
                    getString(R.string.send_queue_partial, okCount, failCount)
                }
                updateTransferUi()
            }
        )

        // 从相册/文件管理器「分享」进来的文件
        handleShareIntent(intent)
        // 长按图标进来的快捷入口
        handleShortcutIntent(intent)

        // 轻量入场动画：区块依次淡入，不影响任何交互
        playEntranceAnimation()
        updateDevicesEmpty()

        btnDiscover.setOnClickListener { startDiscovery(force = true) }
        btnCreateGroup.setOnClickListener { controller.createGroup() }
        btnDisconnect.setOnClickListener {
            controller.disconnect()
            TransferManager.stop()
        }
        btnSend.setOnClickListener { sendMessage() }
        btnSend.isEnabled = false
        btnPickFile.setOnClickListener { pickFile() }
        btnViewFiles.setOnClickListener {
            startActivity(Intent(this, ReceivedFilesActivity::class.java))
            // 上滑淡入，和圆角卡片的风格一致，不用系统默认的横向滑动
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.activity_in_bottom, android.R.anim.fade_out)
        }
        btnCancelTransfer.setOnClickListener {
            // 「暂停」之后按钮变成「继续发送」：点它把刚才那个文件重新入队，
            // 大文件会走续传握手，从对端已有的字节数接着发
            val paused = pausedSend
            if (paused != null && !TransferManager.isSending) {
                pausedSend = null
                updateTransferUi()
                enqueueFiles(listOf(paused.uri))
                return@setOnClickListener
            }

            // 先把队列清掉，否则取消完当前这个，队列会立刻接着发下一个
            val hadQueue = sendQueue.pendingCount > 0
            sendQueue.clear()
            userCancelled = TransferManager.isSending
            // cancelActive 同时覆盖「正在发」和「正在收」：收到一半想放弃也能停
            val cancelled = TransferManager.cancelActive()
            toast(getString(if (cancelled) R.string.transfer_cancelled else R.string.queue_cleared))
            if (!cancelled && hadQueue) log("已清空发送队列")
            updateTransferUi()
        }
        // 长按「取消」= 暂停：记住当前这个文件，稍后可以接着发
        btnCancelTransfer.setOnLongClickListener {
            if (!TransferManager.isSending || pendingUri == null) return@setOnLongClickListener false
            pausedSend = SendItem(pendingUri!!, pendingName, pendingSize)
            sendQueue.clear()
            TransferManager.cancelSend()
            toast(getString(R.string.send_paused, pendingName))
            updateTransferUi()
            true
        }
        btnPlayGame.setOnClickListener { openGameForFun() }
        findViewById<View>(R.id.btnTools).setOnClickListener { showToolsMenu() }
        btnEmptySearch.setOnClickListener { startDiscovery(force = true) }
        // 长按「玩游戏」：恢复「传输时询问要不要玩」的提示
        btnPlayGame.setOnLongClickListener {
            restoreGamePrompt()
            true
        }

        // 调试入口：长按通道状态可手动起监听 / 连接，不依赖 P2P（模拟器可测）
        tvChannelState.setOnLongClickListener {
            showChannelDebugMenu()
            true
        }

        if (!controller.isSupported) {
            tvP2pState.setText(R.string.p2p_state_unsupported)
            btnDiscover.isEnabled = false
            btnCreateGroup.isEnabled = false
            btnDisconnect.isEnabled = false
        }
    }

    override fun onStart() {
        super.onStart()

        // 通道回调只在界面可见时挂载，避免 Activity 泄漏
        TransferManager.onStateChanged = { state, message -> renderChannelState(state, message) }
        TransferManager.onMessage = { entry ->
            appendChat(entry)
            maybeAutoCopy(entry)
        }
        // 对端开了配对码校验、我们的码不对：弹框要码
        TransferManager.onPairRejected = { reason -> showPairCodePrompt(reason) }
        TransferManager.onReceiveProgress = { progress, speed ->
            renderReceiveProgress(progress, speed)
            // 小组件：接收中显示文件名、速度和剩余时间（同一文件的高频回调会被去重）
            WidgetState.setTransferring(
                this, true, TransferManager.receivingFileName, (progress * 100).toInt(),
                speed, etaSeconds(speed, TransferManager.receivingTotalBytes, progress)
            )
        }
        TransferManager.onReceiveDone = { path ->
            renderReceiveDone(path)
            WidgetState.setTransferring(this, false)
            if (path != null) WidgetState.addReceivedFile(this)
        }
        TransferManager.onSendProgress = { progress, speed ->
            renderSendProgress(progress, speed)
            transferGame?.onProgress(pendingName, progress)
            WidgetState.setTransferring(
                this, true, pendingName, (progress * 100).toInt(),
                speed, etaSeconds(speed, TransferManager.sendingTotalBytes, progress)
            )
        }
        TransferManager.onSendDone = { ok ->
            renderSendDone(ok)
            transferGame?.onDone(pendingName, ok)
            WidgetState.setTransferring(this, false)
            // Q 版人物：传输结束提醒
            if (ok) mascot.showDone() else mascot.showFail()
            // 交给队列决定要不要接着发下一个（顺序不能反：上面几行还要用到刚发完的文件名）
            sendQueue.onTransferFinished(ok)
        }

        // 进入界面时对齐一次小组件状态（应用被杀后桌面上的卡片可能是旧的）
        TransferWidgetProvider.refresh(this)
        // 天气：内部按 15 分钟节流，无网/无权限时静默跳过，绝不影响传文件流程
        WeatherRepository.refreshAsync(this)
        renderChat()
        renderChannelState(TransferManager.state, "")

        if (!controller.isSupported) return

        controller.start()

        when {
            hasNearbyPermission() -> startDiscovery()
            !permissionAsked -> {
                permissionAsked = true
                permissionRequestInFlight = true
                permissionLauncher.launch(permissionsToRequest())
            }
            else -> tvP2pState.setText(R.string.p2p_state_no_permission)
        }

        // 界面可见期间保持搜索活跃，避免系统自动停止后列表不再更新
        uiHandler.removeCallbacks(discoveryLoop)
        uiHandler.postDelayed(discoveryLoop, DISCOVERY_KEEPALIVE_MS)

        // 首次进入时问一次要不要给小组件开放大致位置（只问一次，拒绝也不影响任何功能）
        uiHandler.postDelayed({ askWeatherLocationIfNeeded() }, 900L)
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(discoveryLoop)
        controller.stop()
        TransferManager.onStateChanged = null
        TransferManager.onMessage = null
        TransferManager.clearFileCallbacks()
        TransferManager.onPairRejected = null
        // 界面不可见时关闭游戏窗口，避免 Dialog 引用已销毁的 Activity
        transferGame?.dismiss()
        transferGame = null
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.release()
        // 只有真正退出界面才断开通道，旋转屏幕时保持连接
        if (isFinishing) {
            TransferManager.stop()
            // 通道已经主动关掉，桌面小组件不该继续显示「已连接」
            WidgetState.setConnected(this, false)
            WidgetState.setTransferring(this, false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PERMISSION_ASKED, permissionAsked)
    }

    // ------------------------------------------------------------------ 初始化

    private fun bindViews() {
        tvP2pState = findViewById(R.id.tvP2pState)
        tvSelfInfo = findViewById(R.id.tvSelfInfo)
        tvRoleInfo = findViewById(R.id.tvRoleInfo)
        tvPeerHint = findViewById(R.id.tvPeerHint)
        tvLog = findViewById(R.id.tvLog)
        tvLogToggle = findViewById(R.id.tvLogToggle)
        logHeader = findViewById(R.id.logHeader)
        tvHint = findViewById(R.id.tvHint)
        steps = findViewById(R.id.tvSteps)
        coverLive = findViewById(R.id.coverLive)
        flowBackground = findViewById(R.id.flowBackground)
        rvDevices = findViewById(R.id.rvDevices)
        tvDevicesEmpty = findViewById(R.id.tvDevicesEmpty)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnCreateGroup = findViewById(R.id.btnCreateGroup)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnPlayGame = findViewById(R.id.btnPlayGame)
        btnEmptySearch = findViewById(R.id.btnEmptySearch)

        tvChannelState = findViewById(R.id.tvChannelState)
        rvChat = findViewById(R.id.rvChat)
        chatAdapter = ChatAdapter().apply {
            onEntryLongClick = { entry -> showChatEntryActions(entry) }
        }
        rvChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvChat.adapter = chatAdapter
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)

        btnPickFile = findViewById(R.id.btnPickFile)
        btnViewFiles = findViewById(R.id.btnViewFiles)
        pbTransfer = findViewById(R.id.pbTransfer)
        tvTransferState = findViewById(R.id.tvTransferState)
        btnCancelTransfer = findViewById(R.id.btnCancelTransfer)

        mascot = findViewById(R.id.mascot)

        // 所有按钮统一「按下缩放 + 轻触觉反馈」，比只有涟漪更有实感
        listOf(
            btnDiscover, btnCreateGroup, btnDisconnect, btnPlayGame,
            btnPickFile, btnViewFiles, btnSend, btnEmptySearch
        ).forEach { it.attachPressFeedback() }

        // 回车直接发送：聊天这种高频输入不该逼人抬手去点按钮
        etMessage.setOnEditorActionListener { _, actionId, event ->
            val imeSend = actionId == EditorInfo.IME_ACTION_SEND
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (imeSend || enter) {
                sendMessage()
                true
            } else {
                false
            }
        }

        // 日志区可折叠：默认展开，收起后只留一行标题
        logCollapsed = prefs().getBoolean(KEY_LOG_COLLAPSED, false)
        applyLogCollapsed(logCollapsed, animate = false)
        logHeader.setOnClickListener { applyLogCollapsed(!logCollapsed) }

        // 长按日志内容：导出 / 清空。真机出问题时不用连电脑也能把日志取出来
        tvLog.setOnLongClickListener {
            showLogMenu()
            true
        }
    }

    /**
     * 折叠 / 展开诊断日志。
     *
     * 小屏上 76dp 的日志区长期占位很浪费，但排障时又离不开它，
     * 所以做成「标题常驻、内容可收」，并把选择记进偏好。
     */
    private fun applyLogCollapsed(collapsed: Boolean, animate: Boolean = true) {
        logCollapsed = collapsed
        tvLogToggle.setText(if (collapsed) R.string.log_expand else R.string.log_collapse)
        prefs().edit().putBoolean(KEY_LOG_COLLAPSED, collapsed).apply()

        tvLog.animate().cancel()
        if (collapsed) {
            if (!animate) {
                tvLog.visibility = View.GONE
                return
            }
            tvLog.animate().alpha(0f).setDuration(140L).withEndAction {
                if (logCollapsed) tvLog.visibility = View.GONE
            }.start()
        } else {
            tvLog.visibility = View.VISIBLE
            if (animate) {
                tvLog.alpha = 0f
                tvLog.animate().alpha(1f).setDuration(180L).start()
            } else {
                tvLog.alpha = 1f
            }
        }
    }

    // ------------------------------------------------------------------ 内联提示

    private enum class HintKind { INFO, SUCCESS, ERROR }

    private val hintHide = Runnable {
        tvHint.animate().alpha(0f).translationY(10f * resources.displayMetrics.density)
            .setDuration(160L)
            .withEndAction { tvHint.visibility = View.GONE }
            .start()
    }

    /**
     * 内联提示条。
     *
     * 替换系统 Toast：Toast 飘在屏幕最下方、样式不可控、连续弹出还会排队，
     * 而这里贴着页面显示、带状态配色，连续触发时直接替换文案不会堆积。
     */
    private fun showHint(message: String, kind: HintKind = HintKind.INFO) {
        if (message.isBlank() || isFinishing) return
        tvHint.text = message
        val tint = when (kind) {
            HintKind.SUCCESS -> 0xFF17372A.toInt()
            HintKind.ERROR -> 0xFF3A1D24.toInt()
            HintKind.INFO -> 0xFF1B2436.toInt()
        }
        tvHint.backgroundTintList = android.content.res.ColorStateList.valueOf(tint)

        uiHandler.removeCallbacks(hintHide)
        tvHint.visibility = View.VISIBLE
        tvHint.animate().cancel()
        tvHint.alpha = 0f
        tvHint.translationY = 12f * resources.displayMetrics.density
        tvHint.animate().alpha(1f).translationY(0f).setDuration(170L).start()
        uiHandler.postDelayed(hintHide, 2600L)
    }

    // ------------------------------------------------------------------ 更多操作

    /** 封面右上角「⋮」：把只有长按才知道的功能集中到一处，顺便补一份手势说明 */
    private fun showToolsMenu() {
        val pairLabel = prefs().getString(KEY_PAIR_CODE, null).orEmpty().let {
            if (it.isEmpty()) getString(R.string.pair_code_off)
            else getString(R.string.pair_code_on, it)
        }
        val items = arrayOf(
            getString(R.string.lan_direct),
            getString(R.string.qr_show),
            getString(R.string.qr_scan),
            getString(R.string.send_history),
            getString(
                R.string.auto_copy_toggle,
                getString(if (autoCopyEnabled()) R.string.switch_on else R.string.switch_off)
            ),
            getString(R.string.pair_code, pairLabel),
            getString(R.string.log_export),
            getString(R.string.tools_gestures),
            getString(R.string.log_clear)
        )
        AppDialog.builder(this)
            .setTitle(R.string.tools_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showLanDirectDialog()
                    1 -> showQrPairDialog()
                    2 -> pickQrImage.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                    3 -> showSendHistory()
                    4 -> {
                        val enabled = !autoCopyEnabled()
                        setAutoCopyEnabled(enabled)
                        showHint(
                            getString(if (enabled) R.string.auto_copy_on else R.string.auto_copy_off)
                        )
                    }
                    5 -> togglePairCode()
                    6 -> exportLog()
                    7 -> showGesturesDialog()
                    else -> {
                        TransferManager.clearHistory()
                        renderChat()
                        showHint("已清空聊天记录")
                    }
                }
            }
            .show()
    }

    // ------------------------------------------------------------------ 配对码

    /** 开 / 关配对码校验；开的时候顺手生成一个新码 */
    private fun togglePairCode() {
        val current = prefs().getString(KEY_PAIR_CODE, null).orEmpty()
        if (current.isEmpty()) {
            applyPairCode(newPairCode())
        } else {
            prefs().edit().remove(KEY_PAIR_CODE).apply()
            TransferManager.setPairCode(null)
            showHint(getString(R.string.pair_code_off_hint))
        }
    }

    private fun newPairCode(): String = (1000..9999).random().toString()

    /** 写进偏好并同步给传输层，同时把码亮给用户看（面板关了就看不见了） */
    private fun applyPairCode(code: String) {
        prefs().edit().putString(KEY_PAIR_CODE, code).apply()
        TransferManager.setPairCode(code)
        log("配对码校验已开启：$code")

        AppDialog.builder(this)
            .setTitle(getString(R.string.pair_code_dialog_title, code))
            .setMessage(R.string.pair_code_dialog_body)
            .setPositiveButton(R.string.dialog_ok, null)
            .setNeutralButton(R.string.pair_code_reroll) { _, _ -> applyPairCode(newPairCode()) }
            .show()
    }

    /** 应用启动时把上次的码重新交给传输层，否则重启后校验会悄悄失效 */
    private fun restorePairCode() {
        val saved = prefs().getString(KEY_PAIR_CODE, null).orEmpty()
        TransferManager.setPairCode(saved.ifEmpty { null })
    }

    // ------------------------------------------------------------------ 扫码配对

    /**
     * 显示本机的连接二维码。
     *
     * 内容是「IP:端口 + 配对码（开了才有）」，对端在「更多操作 → 扫码连接」里
     * 选这张图（截图/拍照都行）即可直连并自动完成配对码校验。
     */
    private fun showQrPairDialog() {
        val ip = LanAddress.primaryIpv4()
        if (ip == null) {
            toast(getString(R.string.qr_no_ip))
            return
        }
        val payload = QrPair.Payload(
            host = ip,
            port = TransferManager.port,
            pairCode = prefs().getString(KEY_PAIR_CODE, null).orEmpty().ifEmpty { null }
        )
        val size = (280 * resources.displayMetrics.density).toInt()
        val qr = runCatching { QrImage.encode(QrPair.encode(payload), size) }.getOrNull()
        if (qr == null) {
            toast(getString(R.string.qr_generate_failed))
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val codeLabel = payload.pairCode
            ?.let { getString(R.string.qr_pair_code_label, it) }
            ?: getString(R.string.qr_pair_code_none)
        container.addView(TextView(this).apply {
            text = getString(R.string.qr_addr_label, "$ip:${TransferManager.port}") + "\n" + codeLabel
            textSize = 13f
            gravity = android.view.Gravity.CENTER
        })
        container.addView(android.widget.ImageView(this).apply {
            setImageBitmap(qr)
            val m = (12 * resources.displayMetrics.density).toInt()
            setPadding(0, m, 0, 0)
            contentDescription = getString(R.string.qr_show)
        })
        AppDialog.builder(this)
            .setTitle(R.string.qr_show)
            .setView(container)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    /**
     * 从相册选一张二维码图片并连接。
     *
     * 用系统照片选择器（Photo Picker）：不需要存储权限，返回的临时 Uri 本应用就能读。
     * 解码放后台线程——一张大图缩放 + 识别可能上百毫秒，主线程做会卡住过渡动画。
     */
    private val pickQrImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            toast(getString(R.string.qr_decoding))
            Thread {
                val text = runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val src = android.graphics.BitmapFactory.decodeStream(input)
                        src?.let { QrImage.decode(it) }
                    }
                }.getOrNull() ?: return@Thread runOnUiThread {
                    toast(getString(R.string.qr_not_found))
                }
                val payload = QrPair.decode(text)
                runOnUiThread {
                    if (payload == null) {
                        toast(getString(R.string.qr_not_ours))
                    } else {
                        connectViaQr(payload)
                    }
                }
            }.start()
        }

    /** 二维码解析成功：带码直连。配对码顺手存起来，下次重启仍生效 */
    private fun connectViaQr(payload: QrPair.Payload) {
        val code = payload.pairCode
        if (code != null) {
            prefs().edit().putString(KEY_PAIR_CODE, code).apply()
            TransferManager.setPairCode(code)
        }
        TransferManager.stop()
        TransferManager.setP2pNetwork(null)
        unbindProcessNetwork()
        // 局域网/转发链路 RTT 远大于 P2P（如模拟器 adb 转发），放宽单次超时
        TransferManager.connect(payload.host, payload.port, timeoutMs = 5000)
        log(getString(R.string.qr_connect_log, payload.host, payload.port))
        showHint(getString(R.string.qr_connect_hint, payload.host))
    }

    /**
     * 对端拒了我们（配对码不对）：弹框让用户填码再连一次。
     *
     * 不用 Toast 而用输入框：这是唯一能继续往下走的动作，
     * 用户看到提示却没法输入的话，只能重新点一遍设备。
     */
    private fun showPairCodePrompt(reason: String) {
        if (isFinishing) return
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = EditText(this).apply {
            hint = getString(R.string.pair_code_input_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            setSingleLine(true)
        }
        container.addView(input)

        val body = getString(
            R.string.pair_code_prompt_body,
            reason.ifBlank { getString(R.string.pair_code_prompt_fallback) }
        )

        AppDialog.builder(this)
            .setTitle(R.string.pair_code_prompt_title)
            .setMessage(body)
            .setView(container)
            .setPositiveButton(R.string.pair_code_connect) { _, _ ->
                val code = input.text.toString().trim()
                if (code.length != 4) {
                    toast(getString(R.string.pair_code_invalid))
                    return@setPositiveButton
                }
                if (!TransferManager.retryWithPairCode(code)) {
                    toast(getString(R.string.channel_not_ready))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 最近发送过的文件：点一下直接重新入队 */
    private fun showSendHistory() {
        val entries = SendHistory.recent(this)
        if (entries.isEmpty()) {
            toast(getString(R.string.send_history_empty))
            return
        }
        val labels = entries.map { entry ->
            val size = if (entry.size > 0) Formatters.size(entry.size) else ""
            if (size.isEmpty()) entry.name else "${entry.name} · $size"
        }.toTypedArray()

        AppDialog.builder(this)
            .setTitle(R.string.send_history)
            .setItems(labels) { _, which ->
                val entry = entries.getOrNull(which) ?: return@setItems
                if (!TransferManager.isConnected) {
                    toast(getString(R.string.channel_not_ready))
                    return@setItems
                }
                val uri = Uri.parse(entry.uri)
                // 持久授权可能已经失效（用户清理了权限、或来源不支持），这里先探一下
                val readable = runCatching {
                    contentResolver.openInputStream(uri)?.use { true } ?: false
                }.getOrDefault(false)
                if (!readable) {
                    toast(getString(R.string.send_history_expired))
                    return@setItems
                }
                enqueueFiles(listOf(uri))
            }
            .show()
    }

    /**
     * 收到文本后按开关决定是否进剪贴板。
     *
     * 只处理对端发来的消息（kind == PEER），
     * 自己发的、系统提示（已连接 / 已收到文件）一律跳过，
     * 否则剪贴板里会莫名其妙多出「通道已建立」这种话。
     */
    private fun maybeAutoCopy(entry: ChatEntry) {
        if (!autoCopyEnabled()) return
        if (entry.kind != ChatEntry.Kind.PEER) return
        val text = entry.text.trim()
        if (text.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        runCatching {
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        }.onSuccess {
            showHint(getString(R.string.auto_copy_on), HintKind.INFO)
        }
    }

    /** 收到的文本是否自动复制到剪贴板 */
    private fun autoCopyEnabled(): Boolean = prefs().getBoolean(KEY_AUTO_COPY, false)

    private fun setAutoCopyEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_AUTO_COPY, enabled).apply()
    }

    private fun showGesturesDialog() {
        AppDialog.builder(this)
            .setTitle(R.string.tools_gestures)
            .setMessage(R.string.tools_gestures_body)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    /**
     * 入场动画：各区块依次淡入并轻微上移。
     *
     * 只用 alpha 与 translationY，不改布局参数，所以不会引起重新测量；
     * 幅度 12dp、每块间隔 40ms，够轻，不会让人觉得「打开了什么特效页面」。
     */
    private fun playEntranceAnimation() {
        val root = findViewById<ViewGroup>(R.id.root) ?: return
        val column = root.getChildAt(1) as? LinearLayout ?: return
        val density = resources.displayMetrics.density
        column.children.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 16f * density
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * 40L)
                .setDuration(320L)
                .start()
        }
    }

    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun wireController() {
        controller.onLog = { message -> log(message) }

        controller.onP2pEnabled = { enabled ->
            if (enabled) {
                if (!searching) tvP2pState.setText(R.string.p2p_state_ready)
                // 只在已授权时自动开搜，否则交给 onStart 的授权流程，避免重复弹窗；
                // 协商/组网中系统可能反弹 P2P 状态广播，此时重开搜索只会 BUSY，
                // 还会干扰正在进行的 GO 协商
                if (hasNearbyPermission() && !connecting && !p2pConnected) startDiscovery()
            } else {
                tvP2pState.setText(R.string.p2p_state_off)
                searching = false
                adapter.clear()
                tvPeerHint.setText(R.string.nearby_devices_zero)
                updateDevicesEmpty()
            }
        }

        controller.onPeersChanged = { peers ->
            val changed = adapter.submit(peers)
            tvPeerHint.text = getString(R.string.nearby_devices, peers.size)
            updateDevicesEmpty()
            // 只在数量真的变化时打日志，避免每次刷新都刷屏
            if (changed && peers.size != lastPeerCount) {
                lastPeerCount = peers.size
                log("附近设备：${peers.size} 台")
            }
        }

        controller.onThisDeviceChanged = { device ->
            tvSelfInfo.text = getString(R.string.self_info, displayName(device))
        }

        controller.onConnectionInfo = { info -> onConnected(info) }

        controller.onGroupCreated = {
            // 已知自己是组主，提前监听，减少对方连上来时的等待
            TransferManager.startServer()
        }

        controller.onConnectionRequest = { device ->
            // 收到对方邀请，默认接受（WPS PBC 免确认）
            log("收到 ${displayName(device)} 的邀请，自动接受")
            true
        }

        controller.onDisconnected = {
            p2pConnected = false
            connecting = false
            uiHandler.removeCallbacks(connectingTimeout)
            adapter.setConnecting(null)
            refreshSteps()
            TransferManager.setP2pNetwork(null)
            tvRoleInfo.setText(R.string.role_unknown)
            // 这里不主动关通道：P2P 链路断开时 socket 会自己报错退出，
            // 而组主的监听保留下来，下次重新组网可以直接复用。
        }

        controller.onConnectFailed = { reason ->
            connecting = false
            uiHandler.removeCallbacks(connectingTimeout)
            adapter.setConnecting(null)
            tvP2pState.setText(R.string.p2p_state_ready)
            refreshSteps()
            toast("连接失败：$reason")
        }

        controller.onDiscoveryFailed = { reason ->
            if (reason == android.net.wifi.p2p.WifiP2pManager.BUSY) {
                // 系统还在忙上一次搜索/连接，退避一段时间再试，
                // 否则每几秒撞一次会让系统一直停在“忙”的状态
                discoveryBackoffUntil = SystemClock.elapsedRealtime() + DISCOVERY_BUSY_BACKOFF_MS
            }
        }

        // 拿到 P2P 网络后交给传输层做 socket 级绑定（进程级绑定在部分 ROM 上不生效）
        controller.onP2pNetworkBound = { network ->
            TransferManager.setP2pNetwork(network)
        }

        controller.onDiscoveryChanged = { discovering ->
            searching = discovering
            if (discovering) tvP2pState.setText(R.string.p2p_state_searching)
        }
    }

    // ------------------------------------------------------------------ 权限

    /**
     * Wi-Fi Direct 在实机上必需的运行时权限，按系统版本分裂：
     * - Android 13（API 33）起：NEARBY_WIFI_DEVICES（neverForLocation，不需要定位）
     * - Android 12 及以下：仍然是 ACCESS_FINE_LOCATION，且系统定位开关必须打开
     *
     * 早前只判断 NEARBY_WIFI_DEVICES，在 Android 7~12 上该权限并不存在，
     * checkSelfPermission 恒返回拒绝，导致老系统手机直接卡在“无权限”、搜不到设备。
     */
    private fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)

        // Android 10~12：搜索仍需定位权限，但写下载目录不再需要存储权限
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        // Android 9 及以下：额外需要存储权限，否则文件存不进公共下载目录
        else -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    private fun hasNearbyPermission(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 实际一次性申请的权限：必需权限 + （Android 13 起）通知权限。
     * 通知只影响前台服务提示能否显示，被拒绝也不阻断连接。
     */
    private fun permissionsToRequest(): Array<String> {
        val required = requiredPermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            required += Manifest.permission.POST_NOTIFICATIONS
        }
        return required.toTypedArray()
    }

    /** 权限是否被判为“不再询问”——是的话就只给个去设置的入口，别再弹窗了 */
    private fun shouldShowRationale(): Boolean = requiredPermissions().any {
        shouldShowRequestPermissionRationale(it)
    }

    private fun requestPermissionIfNeeded(): Boolean {
        if (hasNearbyPermission()) return true
        if (permissionRequestInFlight) return false
        if (!permissionAsked || shouldShowRationale()) {
            permissionAsked = true
            permissionRequestInFlight = true
            permissionLauncher.launch(permissionsToRequest())
        } else {
            showPermissionSettingsDialog()
        }
        return false
    }

    /**
     * 询问是否给天气小组件开放「大致位置」。
     *
     * 只在 Android 13+ 需要单独问：低版本为了搜设备本来就要定位权限，
     * 已经拿到就直接用，不重复打扰。
     * 整个流程只问一次，用户点了「暂不」也不会再弹，小组件上会显示提示文案。
     */
    private fun askWeatherLocationIfNeeded() {
        if (isFinishing || isDestroyed) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (WeatherRepository.hasLocationPermission(this)) return
        if (permissionRequestInFlight) return
        if (prefs().getBoolean(KEY_WEATHER_LOC_ASKED, false)) return

        prefs().edit().putBoolean(KEY_WEATHER_LOC_ASKED, true).apply()

        AppDialog.builder(this)
            .setTitle(R.string.weather_loc_title)
            .setMessage(R.string.weather_loc_message)
            .setPositiveButton(R.string.weather_loc_allow) { _, _ ->
                weatherLocationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            .setNegativeButton(R.string.weather_loc_later) { _, _ ->
                log("未开放位置，小组件不显示天气（可在系统设置中随时开启）")
            }
            .show()
    }

    private fun showPermissionSettingsDialog() {        tvP2pState.setText(R.string.p2p_state_no_permission)
        AppDialog.builder(this)
            .setTitle(R.string.dialog_permission_title)
            .setMessage(R.string.dialog_permission_message)
            .setPositiveButton(R.string.action_go_settings) { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ 动作

    /**
     * Android 12 及以下，Wi-Fi Direct 扫描需要系统定位开关也是打开的，
     * 只给权限还不够。这里只提示一次，避免每次续搜都弹。
     */
    private fun warnIfLocationDisabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        if (locationWarned) return
        val lm = getSystemService(LOCATION_SERVICE) as? android.location.LocationManager ?: return
        val enabled = runCatching { lm.isLocationEnabled }.getOrDefault(true)
        if (!enabled) {
            locationWarned = true
            log("系统定位未开启：Android 12 及以下搜不到设备，请打开定位开关")
            Toast.makeText(this, "请打开系统“定位”开关，否则搜不到设备", Toast.LENGTH_LONG).show()
        }
    }

    private fun startDiscovery(force: Boolean = false) {
        if (!requestPermissionIfNeeded()) return
        warnIfLocationDisabled()

        val now = SystemClock.elapsedRealtime()
        if (now - lastDiscoverAt < DISCOVER_DEBOUNCE_MS) {
            if (force) log("搜索过于频繁，请稍等一秒")
            return
        }
        lastDiscoverAt = now
        searching = true

        tvP2pState.setText(R.string.p2p_state_searching)
        // 四步进度由 refreshSteps 按权威状态推导：协商中/已连接时点搜索不会重置进度
        refreshSteps()
        controller.startDiscovery()
    }

    private fun onDeviceClicked(device: WifiP2pDevice) {
        if (!requestPermissionIfNeeded()) return
        if (connecting) {
            log("正在连接中，请稍候…")
            return
        }
        connecting = true
        tvP2pState.setText(R.string.p2p_state_connecting)
        log("正在连接 ${displayName(device)}（${device.deviceAddress}），等待对方确认…")
        // 立刻在列表上标出“连接中”，避免用户重复点击同一条
        adapter.setConnecting(device.deviceAddress)
        connectingPeerName = displayName(device)
        refreshSteps()
        // 超时兜底：个别 ROM 协商失败既不回调 onConnectFailed 也不触发断开广播，
        // 「邀请中」会永久挂着、设备列表再也点不动。30 秒没结果就复位让用户重试。
        uiHandler.postDelayed(connectingTimeout, CONNECTING_TIMEOUT_MS)
        controller.connect(device)
    }

    private val connectingTimeout = Runnable {
        if (!connecting) return@Runnable
        connecting = false
        adapter.setConnecting(null)
        tvP2pState.setText(R.string.p2p_state_ready)
        refreshSteps()
        log("连接协商超时（30 秒无结果），已恢复。请确认对方手机是否弹出了系统确认框")
    }

    private fun onConnected(info: WifiP2pInfo) {
        p2pConnected = true
        connecting = false
        uiHandler.removeCallbacks(connectingTimeout)
        adapter.setConnecting(null)

        val role = if (info.isGroupOwner) "组主（GO）" else "客户端"
        // 组主的固定地址是 192.168.49.1，组主自己拿不到 groupOwnerAddress
        val groupOwnerIp = if (info.isGroupOwner) {
            "192.168.49.1（本机）"
        } else {
            info.groupOwnerAddress?.hostAddress ?: "未知"
        }

        tvP2pState.setText(R.string.p2p_state_connected)
        refreshSteps()
        tvRoleInfo.text = getString(R.string.role_info, role, groupOwnerIp)
        log("连接成功：$role，组主地址 $groupOwnerIp")

        // 连接成功后清空设备列表，避免残留“已连接/邀请中”的旧状态
        adapter.clear()
        tvPeerHint.setText(R.string.nearby_devices_zero)

        // M2：组网完成后拉起 TCP 通道。
        // 但必须先把这个进程绑到 P2P 网络 —— Android 10+ 的默认网络路由表里
        // 没有 192.168.49.0/24，不绑定的话 TCP 包会从 Wi-Fi/数据网卡发出去直接超时。
        controller.prepareP2pNetwork {
            if (info.isGroupOwner) {
                TransferManager.startServer()
            } else {
                val host = info.groupOwnerAddress?.hostAddress
                if (host.isNullOrBlank()) {
                    log("组主地址未知，无法建立文本通道")
                } else {
                    TransferManager.connect(host)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 文本通道

    /**
     * 文本通道调试菜单（长按通道状态触发）。
     *
     * 用途：不依赖 Wi-Fi Direct，直接在本机起监听或连一个地址。
     * 两个模拟器上配合 `adb forward` 即可完整验证文本通道与文件传输：
     * ```
     * # A 机点“仅本地监听”
     * adb -s emulator-5554 forward tcp:18988 tcp:8988
     * # B 机点“手动连接”，输入 10.0.2.2:18988
     * ```
     */
    private fun showChannelDebugMenu() {
        val items = arrayOf(
            getString(R.string.lan_direct),
            "仅本地监听（调试）",
            "手动连接…（调试）",
            "清空聊天记录"
        )
        AppDialog.builder(this)
            .setTitle(R.string.channel_debug_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showLanDirectDialog()

                    1 -> {
                        TransferManager.stop()
                        TransferManager.setP2pNetwork(null)
                        unbindProcessNetwork()
                        TransferManager.startServer()
                        log("调试：开始监听 ${TransferManager.port}")
                    }

                    2 -> showManualConnectDialog()

                    3 -> {
                        TransferManager.clearHistory()
                        renderChat()
                    }
                }
            }
            .show()
    }

    /**
     * 局域网直连。
     *
     * 这是 Wi-Fi Direct 的兜底通道：两台设备连同一个 Wi-Fi，一台当服务端，另一台输入对方 IP。
     * 走的还是同一套 TCP + 帧协议，所以文本、文件、断点续传全都照旧可用。
     * 国产 ROM 上 P2P 被限制时，这条路基本必然能通（除了路由器开了 AP 隔离）。
     */
    private fun showLanDirectDialog() {
        val ip = LanAddress.primaryIpv4()
        val port = TransferManager.port

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }

        val info = TextView(this).apply {
            text = if (ip == null) {
                getString(R.string.lan_no_ip)
            } else {
                getString(R.string.lan_local_addr, "$ip:$port")
            }
            textSize = 13f
        }

        val hintView = TextView(this).apply {
            text = getString(R.string.lan_hint_body)
            textSize = 12f
            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        val input = EditText(this).apply {
            hint = getString(R.string.lan_peer_hint)
            setSingleLine(true)
        }

        container.addView(info)
        container.addView(hintView)
        container.addView(input)

        AppDialog.builder(this)
            .setTitle(R.string.lan_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.lan_connect) { _, _ ->
                connectLan(input.text.toString())
            }
            .setNeutralButton(R.string.lan_listen) { _, _ -> listenLan() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 局域网通道前解除进程级网络绑定。
     *
     * P2P 组网成功时进程被绑到 P2P 网络（prepareP2pNetwork）；之后改走
     * 局域网直连/扫码连接时，仅解 socket 绑定不够——进程默认路由还留在
     * 已死（或不存在局域网路由）的 P2P 网络里，连 10.0.2.2 / 局域网 IP
     * 都会失败。这里无条件绑回系统默认网络。
     */
    private fun unbindProcessNetwork() {
        if (Build.VERSION.SDK_INT < 29) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.bindProcessToNetwork(null) }
    }

    /** 作为服务端在局域网上监听，等对方输入本机地址连过来 */
    private fun listenLan() {
        TransferManager.stop()
        // 局域网通道不绑 P2P 网卡，否则包会被丢进 192.168.49.x 那条路由
        TransferManager.setP2pNetwork(null)
        unbindProcessNetwork()
        TransferManager.startServer()
        val ip = LanAddress.primaryIpv4()
        val addr = if (ip == null) "本机 IP（未连 Wi-Fi）" else "$ip:${TransferManager.port}"
        log("局域网监听已开启：$addr")
        toast(getString(R.string.lan_listen_started, addr))
    }

    private fun connectLan(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val (host, port) = parseHostPort(text)
        TransferManager.stop()
        TransferManager.setP2pNetwork(null)
        unbindProcessNetwork()
        // 局域网/转发链路 RTT 远大于 P2P（如模拟器 adb 转发），放宽单次超时
        TransferManager.connect(host, port, timeoutMs = 5000)
        log("局域网直连 $host:$port")
    }

    /** 支持 `1.2.3.4` 和 `1.2.3.4:8988` 两种写法 */
    private fun parseHostPort(text: String): Pair<String, Int> {
        val parts = text.split(":")
        return if (parts.size >= 2) {
            parts[0] to (parts[1].toIntOrNull() ?: TransferManager.port)
        } else {
            text to TransferManager.port
        }
    }

    private fun showManualConnectDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.channel_debug_host_hint)
            setText(DEFAULT_DEBUG_HOST)
            setSelectAllOnFocus(true)
        }

        AppDialog.builder(this)
            .setTitle(R.string.channel_debug_connect)
            .setView(input)
            .setPositiveButton(R.string.dialog_connect) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                val (host, port) = parseHostPort(text)
                TransferManager.stop()
                // 调试通道走的是 loopback / adb 转发，不属于 P2P 网络，
                // 绑着 P2P 网络反而连不通，这里先解绑
                TransferManager.setP2pNetwork(null)
                unbindProcessNetwork()
                log("调试：连接 $host:$port")
                TransferManager.connect(host, port, timeoutMs = 5000)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return
        if (!TransferManager.isConnected) {
            toast(getString(R.string.channel_not_ready))
            return
        }
        if (TransferManager.send(text)) {
            etMessage.setText("")
        } else {
            toast(getString(R.string.channel_not_ready))
        }
    }

    /** 列表为空时显示引导文案，有设备就藏起来 */
    private fun updateDevicesEmpty() {
        tvDevicesEmpty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    /** 更新四步进度，同时记住当前位置（失败时要靠它把红点画对地方） */
    /**
     * 四步进度唯一的更新入口：从权威状态（P2P 是否组网、是否在协商、文本通道状态）
     * 推导当前步骤，而不是到处手动 set。
     *
     * 之前散落各处的 updateStep 会互相踩：搜索循环把协商中的进度打回「发现」、
     * TCP 失败的红叉画到「协商」上（其实 P2P 已经组网成功）。
     * 现在所有状态变化后都调这一个函数，显示永远和真实状态一致。
     */
    private fun refreshSteps() {
        val (step, failed) = when {
            // 文本通道已就绪：全链路完成
            channelState == TransferManager.State.CONNECTED ->
                ConnectionStepsView.STEP_DONE to false

            // P2P 组网成功后，发现/协商/组网三步已锁定完成；
            // TCP 连不上属于「通道」阶段的失败，不回退前三步
            p2pConnected && channelState == TransferManager.State.ERROR ->
                ConnectionStepsView.STEP_CHANNEL to true

            p2pConnected ->
                ConnectionStepsView.STEP_CHANNEL to false

            // P2P 协商中（点了设备、等系统弹窗确认）
            connecting ->
                ConnectionStepsView.STEP_NEGOTIATE to false

            // 未组网但文本通道出错（局域网直连路径）：红叉留在原地
            channelState == TransferManager.State.ERROR ->
                currentStep to true

            // 局域网直连不走 P2P 四阶段，通道在建/监听直接点亮「通道」
            channelState == TransferManager.State.LISTENING ||
                channelState == TransferManager.State.CONNECTING ->
                ConnectionStepsView.STEP_CHANNEL to false

            else -> ConnectionStepsView.STEP_DISCOVER to false
        }
        currentStep = step
        steps.setProgress(step, failed)
    }

    private fun renderChannelState(state: TransferManager.State, message: String) {
        channelState = state
        if (message.isNotBlank()) log(message)

        tvChannelState.setText(
            when (state) {
                TransferManager.State.IDLE -> R.string.channel_idle
                TransferManager.State.LISTENING -> R.string.channel_listening
                TransferManager.State.CONNECTING -> R.string.channel_connecting
                TransferManager.State.CONNECTED -> R.string.channel_connected
                TransferManager.State.ERROR -> R.string.channel_error
            }
        )
        btnSend.isEnabled = state == TransferManager.State.CONNECTED

        when (state) {
            TransferManager.State.IDLE -> {
                coverLive.setMood(CoverLiveView.MOOD_IDLE)
                flowBackground.setAccent(COLOR_ACCENT_IDLE, 0.14f)
            }

            TransferManager.State.CONNECTING -> {
                flowBackground.setAccent(COLOR_ACCENT_IDLE, 0.22f)
            }

            TransferManager.State.LISTENING -> {
                flowBackground.setAccent(COLOR_ACCENT_IDLE, 0.20f)
            }

            TransferManager.State.CONNECTED -> {
                coverLive.setMood(CoverLiveView.MOOD_CONNECTED)
                flowBackground.setAccent(COLOR_ACCENT_ONLINE, 0.18f)
                if (connectingPeerName.isNotBlank()) {
                    WidgetState.setPeer(this, connectingPeerName)
                }
            }

            TransferManager.State.ERROR -> {
                coverLive.setMood(CoverLiveView.MOOD_ERROR)
                flowBackground.setAccent(COLOR_ACCENT_ERROR, 0.22f)
                if (p2pConnected) {
                    showHint(getString(R.string.channel_error_p2p_ok), HintKind.ERROR)
                }
            }
        }

        // 四步进度统一由权威状态推导（见 refreshSteps 的说明）
        refreshSteps()

        // 桌面小组件：同步连接状态（内部做了「状态没变不刷新」的判断）
        WidgetState.setConnected(this, state == TransferManager.State.CONNECTED)

        // 从别的应用分享进来的文件：当时没连接就先存着，连上后自动排队发送
        if (state == TransferManager.State.CONNECTED && pendingShareUris.isNotEmpty()) {
            val uris = pendingShareUris
            pendingShareUris = emptyList()
            log("连接建立，开始发送先前分享进来的 ${uris.size} 个文件")
            enqueueFiles(uris)
        }

        // 连接失败时告诉用户还有一条路可走，只提示一次，免得反复刷屏
        if (state == TransferManager.State.ERROR && !p2pHintShown) {
            p2pHintShown = true
            log(getString(R.string.p2p_failed_hint))
        }
    }

    private fun renderChat() {
        val entries = TransferManager.history()
        chatAdapter.submitAll(
            if (entries.isEmpty()) {
                listOf(ChatEntry(ChatEntry.Kind.SYSTEM, getString(R.string.chat_empty)))
            } else {
                entries
            }
        )
        scrollChatToBottom()
    }

    /** 追加一条消息并滚到最底；列表为空时先把占位提示顶掉 */
    private fun appendChat(entry: ChatEntry) {
        // 恢复历史后只有一条占位提示的场景：第一条真消息来时把它顶掉
        if (chatAdapter.itemCount == 1 &&
            chatAdapter.hintOnly()
        ) {
            chatAdapter.submitAll(listOf(entry))
        } else {
            chatAdapter.add(entry)
        }
        scrollChatToBottom()
    }

    private fun scrollChatToBottom() {
        rvChat.post {
            val count = chatAdapter.itemCount
            if (count > 0) rvChat.smoothScrollToPosition(count - 1)
        }
    }

    /**
     * 长按一条消息：复制；自己发的且通道还在时可以重发。
     * 复制走剪贴板，不弹全屏对话框以外的任何东西。
     */
    private fun showChatEntryActions(entry: ChatEntry) {
        val items = mutableListOf(
            getString(R.string.chat_copy) to Runnable {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@Runnable
                runCatching {
                    cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), entry.text))
                }.onSuccess { showHint(getString(R.string.chat_copied), HintKind.SUCCESS) }
            }
        )
        if (entry.kind == ChatEntry.Kind.MINE && TransferManager.isConnected) {
            items += getString(R.string.chat_resend) to Runnable {
                if (TransferManager.send(entry.text)) {
                    showHint(getString(R.string.chat_resent), HintKind.INFO)
                } else {
                    showHint(getString(R.string.channel_not_ready), HintKind.ERROR)
                }
            }
        }
        AppDialog.builder(this)
            .setTitle(R.string.chat_actions_title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                items[which].second.run()
            }
            .show()
    }

    // ------------------------------------------------------------------ 文件传输

    private fun pickFile() {
        // 多选：一次挑十个文件也不用点十次，队列会自动一个个发
        pickFileLauncher.launch(arrayOf("*/*"))
    }

    /**
     * 把选中的文件排进发送队列。
     *
     * 读名字和大小放在这里做（而不是逐个在发送时读），是为了让队列一开始就知道
     * 每一项要发多大——界面上「第 2/5 个」这样的提示才有意义。
     */
    private fun enqueueFiles(uris: List<Uri>) {
        if (!TransferManager.isConnected) {
            toast(getString(R.string.channel_not_ready))
            return
        }
        val items = uris.map { uri ->
            val (name, size) = queryFileInfo(uri)
            // 记一笔发送历史，之后可以在「更多 → 最近发送」里一键重发
            SendHistory.record(this, uri, name, size)
            SendItem(uri, name, size)
        }
        val queued = sendQueue.enqueue(items)
        if (queued == 0) {
            toast(getString(R.string.transfer_busy))
            return
        }
        log("已加入发送队列：$queued 个文件")
        if (queued > 1) toast(getString(R.string.send_queue_added, queued))
        updateTransferUi()
    }

    /**
     * 处理从别的应用「分享」进来的文件。
     *
     * 直接分享时大概率还没连接（用户是从相册点分享过来的）。这时先把 Uri 记住，
     * 等通道建立后自动排队发送——比弹一句「请先连接再重新分享」体验好得多。
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            )

            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            }

            else -> emptyList()
        }
        if (uris.isEmpty()) return

        if (TransferManager.isConnected) {
            enqueueFiles(uris)
        } else {
            pendingShareUris = uris
            toast(getString(R.string.share_waiting_connect, uris.size))
            log("收到 ${uris.size} 个分享文件，等待连接后发送")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleShortcutIntent(intent)
    }

    /**
     * 长按图标（或从别处带 action 拉起）时的快捷动作。
     *
     * 都延迟一点再执行：刚进 onCreate 时权限对话框、入场动画、P2P 初始化还在排队，
     * 立刻去搜设备会被还没准备好的状态挡回去。
     */
    private fun handleShortcutIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_SHORTCUT_DISCOVER -> uiHandler.postDelayed({
                if (!isFinishing) startDiscovery(force = true)
            }, 700L)

            ACTION_SHORTCUT_HISTORY -> uiHandler.postDelayed({
                if (!isFinishing) showSendHistory()
            }, 500L)
        }
    }

    /** 读 SAF 的文件名与大小；拿不到时给一个能跑通的默认值 */
    private fun queryFileInfo(uri: Uri): Pair<String, Long> {
        var name = getString(R.string.transfer_unknown_file)
        var size = -1L
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) cursor.getString(nameIdx)?.let { name = it }
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        }.onFailure { log("读取文件信息失败：${it.message}") }
        return name to size
    }

    /**
     * 传输开始后询问用户要不要玩小游戏。
     *
     * 选「要玩」→ 弹游戏窗口；选「不玩」→ 记住偏好，以后传输不再弹也不再问
     * （主界面的「玩游戏」按钮随时可以自己玩；长按它可恢复这个询问）。
     */
    private fun askToPlayGame(fileName: String) {
        AppDialog.builder(this)
            .setTitle(R.string.game_ask_title)
            .setMessage(R.string.game_ask_message)
            .setPositiveButton(R.string.game_ask_play) { _, _ ->
                if (isFinishing || isDestroyed) return@setPositiveButton
                if (TransferManager.isSending) {
                    // 传输还在进行：正常弹游戏，顶部显示实时进度
                    showTransferGame(fileName)
                } else {
                    // 传输已经结束（小文件很快）：直接按“已完成”状态打开，避免显示 0%
                    showFinishedGame(fileName)
                }
            }
            .setNegativeButton(R.string.game_ask_skip) { _, _ ->
                gamePromptDisabled = true
                prefs().edit().putBoolean(KEY_GAME_PROMPT_DISABLED, true).apply()
                toast(getString(R.string.game_ask_skipped_toast))
            }
            .setCancelable(true)
            .show()
    }

    private fun restoreGamePrompt() {
        if (!gamePromptDisabled) {
            toast(getString(R.string.game_ask_restored_toast))
            return
        }
        gamePromptDisabled = false
        prefs().edit().putBoolean(KEY_GAME_PROMPT_DISABLED, false).apply()
        toast(getString(R.string.game_ask_restored_toast))
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    /** 弹出传输小游戏窗口，并把后续进度/完成回调桥接给它 */
    private fun showTransferGame(fileName: String) {
        val game = TransferGame(this)
        transferGame = game
        game.show()
        // 立即显示初始状态
        game.onProgress(fileName, 0f)
    }

    /** 传输已经结束时打开游戏窗口：直接呈现「已发送」状态，不会卡在 0% */
    private fun showFinishedGame(fileName: String) {
        if (transferGame?.isShowing() == true) return
        val game = TransferGame(this)
        transferGame = game
        game.show()
        game.onProgress(fileName, 1f)
        game.onDone(fileName, true)
    }

    /** 平时娱乐入口：无传输信息，只玩游戏 */
    private fun openGameForFun() {
        if (transferGame?.isShowing() == true) return
        val game = TransferGame(this)
        transferGame = game
        game.showIdle()
    }

    private fun renderReceiveProgress(progress: Float, speed: Long) {
        val percent = (progress * 100).toInt()
        pbTransfer.progress = percent
        pbTransfer.setBusy(true)
        // transfer_receiving 需要两个参数（文件名 + 百分比），缺一就会抛
        // MissingFormatArgumentException 直接崩溃，这里必须都传齐。
        val name = TransferManager.receivingFileName.ifBlank {
            getString(R.string.transfer_unknown_file)
        }
        coverLive.setMood(CoverLiveView.MOOD_TRANSFER)
        flowBackground.setAccent(COLOR_ACCENT_TRANSFER, 0.26f)
        val suffix = speedSuffix(speed, TransferManager.receivingTotalBytes, progress)
        tvTransferState.text = if (suffix.isEmpty()) {
            getString(R.string.transfer_receiving, name, percent)
        } else {
            getString(R.string.transfer_receiving_speed, name, percent, suffix)
        }
        // 接收中也要有「取消」，不然收到不想收的大文件只能等对方发完
        updateTransferUi()
    }

    private fun renderReceiveDone(path: String?) {
        pbTransfer.progress = 0
        pbTransfer.setBusy(false)
        if (path != null) {
            tvTransferState.text = getString(R.string.transfer_received_done, path)
            coverLive.setMood(CoverLiveView.MOOD_CONNECTED)
            flowBackground.setAccent(COLOR_ACCENT_ONLINE, 0.18f)
            coverLive.celebrate()
            pbTransfer.celebrate()
            window.decorView.hapticSuccess()
            showHint("已接收：${File(path).name}", HintKind.SUCCESS)
        } else {
            tvTransferState.text = getString(R.string.transfer_received_fail)
            coverLive.setMood(CoverLiveView.MOOD_ERROR)
            flowBackground.setAccent(COLOR_ACCENT_ERROR, 0.22f)
            window.decorView.hapticError()
            showHint(getString(R.string.transfer_received_fail), HintKind.ERROR)
        }
        updateTransferUi()
    }

    private fun renderSendProgress(progress: Float, speed: Long) {
        val percent = (progress * 100).toInt()
        pbTransfer.progress = percent
        pbTransfer.setBusy(true)
        coverLive.setMood(CoverLiveView.MOOD_TRANSFER)
        flowBackground.setAccent(COLOR_ACCENT_TRANSFER, 0.26f)
        val suffix = speedSuffix(speed, TransferManager.sendingTotalBytes, progress)
        tvTransferState.text = if (suffix.isEmpty()) {
            getString(R.string.transfer_sending, pendingName, percent)
        } else {
            getString(R.string.transfer_sending_speed, pendingName, percent, suffix)
        }
        updateTransferUi()
    }

    /**
     * 「2.4 MB/s · 剩 8 秒」这样的后缀。
     *
     * 速度还没采到样本、或者总量未知（SAF 不给 SIZE）时返回空串，
     * 调用方就退回不带速度的文案，而不是显示 `-- /s` 这种废信息。
     */
    private fun speedSuffix(speed: Long, totalBytes: Long, progress: Float): String {
        if (speed <= 0L) return ""
        val remaining = (totalBytes * (1f - progress.coerceIn(0f, 1f))).toLong()
        return if (remaining > 0L && totalBytes > 0L) {
            getString(
                R.string.transfer_speed_eta,
                Formatters.speed(speed),
                Formatters.duration(remaining / speed)
            )
        } else {
            getString(R.string.transfer_speed_only, Formatters.speed(speed))
        }
    }

    /**
     * 估算剩余秒数，供桌面小组件显示。
     *
     * 速度还没采到样本、或总量未知（SAF 不给 SIZE）时返回 0，
     * 卡片那边就不会硬凑出一个「剩 -- 秒」。
     */
    private fun etaSeconds(speed: Long, totalBytes: Long, progress: Float): Long {
        if (speed <= 0L || totalBytes <= 0L) return 0L
        val remaining = (totalBytes * (1f - progress.coerceIn(0f, 1f))).toLong()
        return if (remaining > 0L) remaining / speed else 0L
    }

    private fun renderSendDone(ok: Boolean) {
        pbTransfer.progress = 0
        pbTransfer.setBusy(false)
        when {
            // 用户自己点的取消不算失败，文案要区分开
            userCancelled -> {
                userCancelled = false
                tvTransferState.text = getString(R.string.transfer_cancelled)
            }

            ok -> {
                tvTransferState.text = getString(R.string.transfer_sent_done, pendingName)
                coverLive.setMood(CoverLiveView.MOOD_CONNECTED)
                flowBackground.setAccent(COLOR_ACCENT_ONLINE, 0.18f)
                coverLive.celebrate()
                pbTransfer.celebrate()
                window.decorView.hapticSuccess()
                // 队列里还有文件时不弹提示，避免连续发送时提示条一直闪
                if (sendQueue.pendingCount == 0) {
                    showHint(getString(R.string.transfer_sent_done, pendingName), HintKind.SUCCESS)
                }
            }

            else -> {
                tvTransferState.text = getString(R.string.transfer_sent_fail)
                coverLive.setMood(CoverLiveView.MOOD_ERROR)
                flowBackground.setAccent(COLOR_ACCENT_ERROR, 0.22f)
                window.decorView.hapticError()
                showHint(getString(R.string.transfer_sent_fail), HintKind.ERROR)
            }
        }
        updateTransferUi()
    }

    /** 「取消」只在真的有事可取消时出现；暂停之后变成「继续发送」 */
    private fun updateTransferUi() {
        // 传大文件时锁屏 → Wi-Fi 进省电 → 断连看门狗判死，传输莫名其妙失败。
        // 只要还有传输在跑就保持屏幕常亮，全部结束后立即释放。
        val busy = TransferManager.isSending || TransferManager.isReceiving
        if (busy) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val paused = pausedSend
        if (paused != null && !TransferManager.isSending) {
            btnCancelTransfer.visibility = View.VISIBLE
            btnCancelTransfer.setText(R.string.send_resume)
            // 没连上就继续不了，置灰比点了没反应更清楚
            btnCancelTransfer.isEnabled = TransferManager.isConnected
            return
        }
        val active = TransferManager.isSending ||
            TransferManager.isReceiving ||
            sendQueue.pendingCount > 0
        btnCancelTransfer.visibility = if (active) View.VISIBLE else View.GONE
        btnCancelTransfer.setText(R.string.transfer_cancel)
        btnCancelTransfer.isEnabled = true
    }

    // ------------------------------------------------------------------ 工具

    private fun displayName(device: WifiP2pDevice): String =
        device.deviceName.ifBlank { device.deviceAddress.ifBlank { "未知设备" } }

    /** 统一走内联提示条；真机上离屏时（比如正在跳转）退回 Toast 保证不丢信息 */
    private fun toast(message: String) {
        if (isFinishing || tvHint == null) return
        showHint(message)
    }

    private fun log(message: String) {
        logLines.addLast("${LocalTime.now().format(timeFormatter)}  $message")
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        tvLog.text = logLines.joinToString("\n")
    }

    // ------------------------------------------------------------------ 日志导出

    private fun showLogMenu() {
        AppDialog.builder(this)
            .setItems(
                arrayOf(getString(R.string.log_export), getString(R.string.log_clear))
            ) { _, which ->
                if (which == 0) exportLog() else {
                    logLines.clear()
                    tvLog.text = ""
                }
            }
            .show()
    }

    /**
     * 把日志写成文件再调系统分享。
     *
     * 真机上出问题时用户往往没法连 adb，这个入口让「把日志发给我」变成一次点击。
     * 文件走 FileProvider 授权，不是把 file:// 直接塞进 Intent（API 24 起那样会崩）。
     */
    private fun exportLog() {
        val content = buildString {
            appendLine("WiFi 快传 诊断日志")
            appendLine("导出时间：${LocalDateTime.now()}")
            appendLine(
                "设备：${Build.MANUFACTURER} ${Build.MODEL} / " +
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            )
            appendLine("连接状态：" + if (TransferManager.isConnected) "已连接" else "未连接")
            appendLine("角色：" + tvRoleInfo.text)
            appendLine("通道：" + tvChannelState.text)
            appendLine("---- 运行日志 ----")
            logLines.forEach { appendLine(it) }
        }

        val file = runCatching {
            val dir = File(filesDir, "logs").apply { mkdirs() }
            val name = "log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
            File(dir, name).apply { writeText(content) }
        }.getOrNull()

        if (file == null) {
            toast(getString(R.string.log_export_failed))
            return
        }

        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "WiFi 快传日志")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, getString(R.string.log_export)))
        }.onFailure {
            toast(getString(R.string.log_export_failed))
        }
    }

    private companion object {
        const val KEY_PERMISSION_ASKED = "permission_asked"

        /** SharedPreferences 文件名与「传输时不玩小游戏」偏好的 key */
        const val PREFS_NAME = "myzuoye_prefs"
        const val KEY_GAME_PROMPT_DISABLED = "game_prompt_disabled"

        /** 诊断日志是否收起 */
        const val KEY_LOG_COLLAPSED = "log_collapsed"

        /** 收到对方文本时是否自动复制到剪贴板 */
        const val KEY_AUTO_COPY = "auto_copy_text"

        /** 桌面图标长按快捷方式的 action，与 res/xml/shortcuts.xml 保持一致 */
        const val ACTION_SHORTCUT_DISCOVER = "com.example.myzuoye.action.SHORTCUT_DISCOVER"
        const val ACTION_SHORTCUT_HISTORY = "com.example.myzuoye.action.SHORTCUT_HISTORY"

        /** 本机作为组主时的 4 位配对码；不存在表示不校验 */
        const val KEY_PAIR_CODE = "pair_code"

        // 背景状态色：冷蓝待连接 / 青绿已连接 / 紫传输中 / 红失败
        const val COLOR_ACCENT_IDLE = 0xFF3B82F6.toInt()
        const val COLOR_ACCENT_ONLINE = 0xFF34C759.toInt()
        const val COLOR_ACCENT_TRANSFER = 0xFFA855F7.toInt()
        const val COLOR_ACCENT_ERROR = 0xFFE06C75.toInt()

        /** 「要不要给小组件开放大致位置」是否已经问过（只问一次） */
        const val KEY_WEATHER_LOC_ASKED = "weather_location_asked"

        /** 手动点“搜索设备”的最小间隔，压得太短会频繁触发系统 BUSY */
        const val DISCOVER_DEBOUNCE_MS = 600L

        /** 自动续搜的检查间隔：系统搜索会自动停止，停了就靠这个把它拉起来 */
        const val DISCOVERY_KEEPALIVE_MS = 6000L

        /** GO 协商的兜底超时：超时后复位「邀请中」，让设备列表恢复可点 */
        const val CONNECTING_TIMEOUT_MS = 30_000L

        /** 搜索遇到 BUSY（系统忙）时的退避时长 */
        const val DISCOVERY_BUSY_BACKOFF_MS = 15000L

        const val MAX_LOG_LINES = 4

        /** 模拟器里 10.0.2.2 指向宿主机；配合 adb forward 即可跨模拟器联调 */
        const val DEFAULT_DEBUG_HOST = "10.0.2.2:8988"
    }
}
