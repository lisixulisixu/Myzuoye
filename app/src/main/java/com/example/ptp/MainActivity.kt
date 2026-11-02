package com.example.ptp

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
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ptp.p2p.P2pController
import com.example.ptp.ui.DeviceAdapter
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * M1：打通 Wi-Fi Direct 链路 —— 授权 → 搜索 → 连接 → 显示角色与组主地址。
 * 传输层不在本阶段范围内。
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

        btnDiscover.setOnClickListener { startDiscovery(force = true) }
        btnCreateGroup.setOnClickListener { controller.createGroup() }
        btnDisconnect.setOnClickListener { controller.disconnect() }

        if (!controller.isSupported) {
            tvP2pState.setText(R.string.p2p_state_unsupported)
            btnDiscover.isEnabled = false
            btnCreateGroup.isEnabled = false
            btnDisconnect.isEnabled = false
        }
    }

    override fun onStart() {
        super.onStart()
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
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.release()
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

        controller.onDisconnected = {
            tvRoleInfo.setText(R.string.role_unknown)
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
        if (device.status == WifiP2pDevice.CONNECTED) {
            log("已与该设备连接")
            return
        }
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
    }

    // ------------------------------------------------------------------ 工具

    private fun displayName(device: WifiP2pDevice): String =
        device.deviceName.ifBlank { device.deviceAddress.ifBlank { "未知设备" } }

    private fun log(message: String) {
        logLines.addLast("${LocalTime.now().format(timeFormatter)}  $message")
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        tvLog.text = logLines.joinToString("\n")
    }

    private companion object {
        const val KEY_PERMISSION_ASKED = "permission_asked"
        const val DISCOVER_DEBOUNCE_MS = 1000L
        const val MAX_LOG_LINES = 6
    }
}
