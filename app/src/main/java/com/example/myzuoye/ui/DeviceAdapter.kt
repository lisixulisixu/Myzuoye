package com.example.myzuoye.ui

import android.net.wifi.p2p.WifiP2pDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.myzuoye.R

/**
 * 附近设备列表适配器。
 */
class DeviceAdapter(
    private val onClick: (WifiP2pDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    private val items = mutableListOf<WifiP2pDevice>()

    /** 正在连接中的设备地址，用于在列表上显示“连接中”并禁用重复点击 */
    private var connectingAddress: String? = null

    /**
     * 提交新的设备列表。
     *
     * 内容（地址 / 名称 / 状态）完全一致时不重绘 —— 搜索期间 PEERS_CHANGED 很频繁，
     * 每次都 notifyDataSetChanged 会让列表不停重绑、滚动位置也跟着跳。
     *
     * @return 是否真的发生了更新
     */
    fun submit(peers: Collection<WifiP2pDevice>): Boolean {
        val incoming = peers.sortedBy { it.deviceName.lowercase() }
        val same = incoming.size == items.size && incoming.indices.all { i ->
            items[i].deviceAddress == incoming[i].deviceAddress &&
                items[i].deviceName == incoming[i].deviceName &&
                items[i].status == incoming[i].status
        }
        if (same) return false

        items.clear()
        items.addAll(incoming)
        notifyDataSetChanged()
        return true
    }

    /** 标记某台设备正在连接；传 null 清除 */
    fun setConnecting(address: String?) {
        if (connectingAddress == address) return
        connectingAddress = address
        notifyDataSetChanged()
    }

    fun clear() {
        if (items.isEmpty()) return
        items.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = items[position]
        val isConnecting = device.deviceAddress == connectingAddress

        holder.name.text = device.deviceName.ifBlank { "未知设备" }
        holder.address.text = device.deviceAddress
        holder.status.text = if (isConnecting) "连接中…" else statusText(device.status)
        holder.itemView.alpha = if (isConnecting) 0.6f else 1f
        holder.itemView.setOnClickListener {
            if (!isConnecting) onClick(device)
        }
    }

    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvDeviceName)
        val address: TextView = view.findViewById(R.id.tvDeviceAddress)
        val status: TextView = view.findViewById(R.id.tvDeviceStatus)
    }

    private fun statusText(status: Int): String = when (status) {
        WifiP2pDevice.AVAILABLE -> "可连接"
        WifiP2pDevice.INVITED -> "邀请中"
        WifiP2pDevice.CONNECTED -> "已连接"
        WifiP2pDevice.FAILED -> "失败"
        WifiP2pDevice.UNAVAILABLE -> "不可用"
        else -> "未知"
    }
}
