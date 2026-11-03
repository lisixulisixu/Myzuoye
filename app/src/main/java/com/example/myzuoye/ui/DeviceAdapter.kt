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

    fun submit(peers: Collection<WifiP2pDevice>) {
        items.clear()
        items.addAll(peers.sortedBy { it.deviceName.lowercase() })
        notifyDataSetChanged()
    }

    fun clear() {
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
        holder.name.text = device.deviceName.ifBlank { "未知设备" }
        holder.address.text = device.deviceAddress
        holder.status.text = statusText(device.status)
        holder.itemView.setOnClickListener { onClick(device) }
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
