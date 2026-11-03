package com.example.myzuoye

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myzuoye.p2p.P2pController
import com.example.myzuoye.transfer.TransferManager
import com.example.myzuoye.ui.DeviceAdapter
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

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
    private lateinit var btnDiscover: Button
    private lateinit var btnCreateGroup: Button
    private lateinit var btnDisconnect: Button

    private lateinit var tvChannelState: TextView
    private lateinit var tvChat: TextView
    private lateinit var svChat: ScrollView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button

    private lateinit var btnPickFile: Button
    private lateinit var pbTransfer: ProgressBar
    private lateinit var tvTransferState: TextView

    /** 待发送的文件信息：uri + 显示名 + 大小。选完文件后由 pickFileLauncher 填充 */
    private var pendingUri: Uri? = null
    private var pendingName: String = ""
    private var pendingSize: Long = 0

    private val logLines = ArrayDeque<String>()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** 已经弹过一次权限框，避免每次 onStart 反复打扰 */
    private var permissionAsked = false

    /** 权限请求正在进行中（系统弹窗还开着），此时不再重复发起 */
    private var permissionRequestInFlight = false

    /** 防止 onStart 与广播回调叠加触发重复搜索 */
    private var lastDiscoverAt = 0L

    /** 当前是否处于发现流程中，用于避免状态文案被广播回调覆盖 */
    private var searching = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionRequestInFlight = false
            if (granted) {
                log("已获得“附近设备”权限")
                startDiscovery()
            } else {
                log("“附近设备”权限被拒绝")
                tvP2pState.setText(R.string.p2p_state_no_permission)
                if (!shouldShowRationale()) {
                    showPermissionSettingsDialog()
                }
            }
        }

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            resolveFileInfo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        permissionAsked = savedInstanceState?.getBoolean(KEY_PERMISSION_ASKED) ?: false

        bindViews()
        applyWindowInsets()

        adapter = DeviceAdapter { device -> onDeviceClicked(device) }
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = adapter

        controller = P2pController(this)
        wireController()

        // M3：接收文件保存到应用私有目录，避开分区存储权限
        TransferManager.receiveDir = File(filesDir, "received")
        // M4：attach 应用上下文，供前台服务保活使用
        TransferManager.attach(this)

        btnDiscover.setOnClickListener { startDiscovery(force = true) }
        btnCreateGroup.setOnClickListener { controller.createGroup() }
        btnDisconnect.setOnClickListener {
            controller.disconnect()
            TransferManager.stop()
        }
        btnSend.setOnClickListener { sendMessage() }
        btnSend.isEnabled = false
        btnPickFile.setOnClickListener { pickFile() }

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
        TransferManager.onMessage = { line -> appendChat(line) }
        TransferManager.onReceiveProgress = { progress -> renderReceiveProgress(progress) }
        TransferManager.onReceiveDone = { path -> renderReceiveDone(path) }
        TransferManager.onSendProgress = { progress -> renderSendProgress(progress) }
        TransferManager.onSendDone = { ok -> renderSendDone(ok) }
        renderChat()
        renderChannelState(TransferManager.state, "")

        if (!controller.isSupported) return

        controller.start()

        when {
            hasNearbyPermission() -> startDiscovery()
            !permissionAsked -> {
                permissionAsked = true
                permissionRequestInFlight = true
                permissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            else -> tvP2pState.setText(R.string.p2p_state_no_permission)
        }
    }

    override fun onStop() {
        super.onStop()
        controller.stop()
        TransferManager.onStateChanged = null
        TransferManager.onMessage = null
        TransferManager.clearFileCallbacks()
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.release()
        // 只有真正退出界面才断开通道，旋转屏幕时保持连接
        if (isFinishing) TransferManager.stop()
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
        rvDevices = findViewById(R.id.rvDevices)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnCreateGroup = findViewById(R.id.btnCreateGroup)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        tvChannelState = findViewById(R.id.tvChannelState)
        tvChat = findViewById(R.id.tvChat)
        svChat = findViewById(R.id.svChat)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)

        btnPickFile = findViewById(R.id.btnPickFile)
        pbTransfer = findViewById(R.id.pbTransfer)
        tvTransferState = findViewById(R.id.tvTransferState)
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
                // 只在已授权时自动开搜，否则交给 onStart 的授权流程，避免重复弹窗
                if (hasNearbyPermission()) startDiscovery()
            } else {
                tvP2pState.setText(R.string.p2p_state_off)
                searching = false
                adapter.clear()
                tvPeerHint.setText(R.string.nearby_devices_zero)
            }
        }

        controller.onPeersChanged = { peers ->
            adapter.submit(peers)
            tvPeerHint.text = getString(R.string.nearby_devices, peers.size)
            log("设备列表更新：${peers.size} 台")
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
            tvRoleInfo.setText(R.string.role_unknown)
            // 这里不主动关通道：P2P 链路断开时 socket 会自己报错退出，
            // 而组主的监听保留下来，下次重新组网可以直接复用。
        }

        controller.onDiscoveryChanged = { discovering ->
            searching = discovering
            if (discovering) tvP2pState.setText(R.string.p2p_state_searching)
        }
    }

    // ------------------------------------------------------------------ 权限

    private fun hasNearbyPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.NEARBY_WIFI_DEVICES
        ) == PackageManager.PERMISSION_GRANTED

    private fun shouldShowRationale(): Boolean =
        shouldShowRequestPermissionRationale(Manifest.permission.NEARBY_WIFI_DEVICES)

    private fun requestPermissionIfNeeded(): Boolean {
        if (hasNearbyPermission()) return true
        if (permissionRequestInFlight) return false
        if (!permissionAsked || shouldShowRationale()) {
            permissionAsked = true
            permissionRequestInFlight = true
            permissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            showPermissionSettingsDialog()
        }
        return false
    }

    private fun showPermissionSettingsDialog() {
        tvP2pState.setText(R.string.p2p_state_no_permission)
        AlertDialog.Builder(this)
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
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ 动作

    private fun startDiscovery(force: Boolean = false) {
        if (!requestPermissionIfNeeded()) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastDiscoverAt < DISCOVER_DEBOUNCE_MS) {
            if (force) log("搜索过于频繁，请稍等一秒")
            return
        }
        lastDiscoverAt = now
        searching = true

        tvP2pState.setText(R.string.p2p_state_searching)
        controller.startDiscovery()
    }

    private fun onDeviceClicked(device: WifiP2pDevice) {
        if (!requestPermissionIfNeeded()) return
        tvP2pState.setText(R.string.p2p_state_connecting)
        log("正在连接 ${displayName(device)}（${device.deviceAddress}）")
        controller.connect(device)
    }

    private fun onConnected(info: WifiP2pInfo) {
        val role = if (info.isGroupOwner) "组主（GO）" else "客户端"
        // 组主的固定地址是 192.168.49.1，组主自己拿不到 groupOwnerAddress
        val groupOwnerIp = if (info.isGroupOwner) {
            "192.168.49.1（本机）"
        } else {
            info.groupOwnerAddress?.hostAddress ?: "未知"
        }

        tvP2pState.setText(R.string.p2p_state_connected)
        tvRoleInfo.text = getString(R.string.role_info, role, groupOwnerIp)
        log("连接成功：$role，组主地址 $groupOwnerIp")

        // 连接成功后清空设备列表，避免残留“已连接/邀请中”的旧状态
        adapter.clear()
        tvPeerHint.setText(R.string.nearby_devices_zero)

        // M2：组网完成后立刻拉起 TCP 通道
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

    // ------------------------------------------------------------------ 文本通道

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

    private fun renderChannelState(state: TransferManager.State, message: String) {
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
    }

    private fun renderChat() {
        val lines = TransferManager.history()
        tvChat.text = if (lines.isEmpty()) {
            getString(R.string.chat_empty)
        } else {
            lines.joinToString("\n")
        }
        scrollChatToBottom()
    }

    private fun appendChat(line: String) {
        if (tvChat.text.toString() == getString(R.string.chat_empty)) {
            tvChat.text = line
        } else {
            tvChat.append("\n$line")
        }
        scrollChatToBottom()
    }

    private fun scrollChatToBottom() {
        svChat.post { svChat.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------------ 文件传输

    private fun pickFile() {
        pickFileLauncher.launch(arrayOf("*/*"))
    }

    private fun resolveFileInfo(uri: Uri) {
        val name: String
        val size: Long
        try {
            contentResolver.query(
                uri,
                arrayOf(
                    android.provider.OpenableColumns.DISPLAY_NAME,
                    android.provider.OpenableColumns.SIZE
                ),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    name = if (nameIdx >= 0) cursor.getString(nameIdx) else "未命名"
                    size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else -1L
                    pendingUri = uri
                    pendingName = name
                    pendingSize = size
                    log("已选择：$name（$size 字节）")
                    startSend()
                    return
                }
            }
        } catch (e: Exception) {
            log("读取文件信息失败：${e.message}")
        }
        toast(getString(R.string.transfer_pick))
    }

    private fun startSend() {
        if (!TransferManager.isConnected) {
            toast(getString(R.string.channel_not_ready))
            return
        }
        val uri = pendingUri ?: return
        try {
            val input = contentResolver.openInputStream(uri) ?: run {
                toast(getString(R.string.transfer_pick))
                return
            }
            val ok = TransferManager.sendFile(input, pendingName, pendingSize)
            if (!ok) {
                toast(getString(R.string.transfer_busy))
            }
        } catch (e: Exception) {
            log("打开文件失败：${e.message}")
            toast(getString(R.string.transfer_pick))
        }
    }

    private fun renderReceiveProgress(progress: Float) {
        val percent = (progress * 100).toInt()
        pbTransfer.progress = percent
        tvTransferState.text = getString(R.string.transfer_receiving, percent)
    }

    private fun renderReceiveDone(path: String?) {
        pbTransfer.progress = 0
        if (path != null) {
            tvTransferState.text = getString(R.string.transfer_received_done, path)
        } else {
            tvTransferState.text = getString(R.string.transfer_received_fail)
        }
    }

    private fun renderSendProgress(progress: Float) {
        val percent = (progress * 100).toInt()
        pbTransfer.progress = percent
        tvTransferState.text = getString(R.string.transfer_sending, pendingName, percent)
    }

    private fun renderSendDone(ok: Boolean) {
        pbTransfer.progress = 0
        if (ok) {
            tvTransferState.text = getString(R.string.transfer_sent_done, pendingName)
        } else {
            tvTransferState.text = getString(R.string.transfer_sent_fail)
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun displayName(device: WifiP2pDevice): String =
        device.deviceName.ifBlank { device.deviceAddress.ifBlank { "未知设备" } }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun log(message: String) {
        logLines.addLast("${LocalTime.now().format(timeFormatter)}  $message")
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        tvLog.text = logLines.joinToString("\n")
    }

    private companion object {
        const val KEY_PERMISSION_ASKED = "permission_asked"
        const val DISCOVER_DEBOUNCE_MS = 1000L
        const val MAX_LOG_LINES = 4
    }
}
