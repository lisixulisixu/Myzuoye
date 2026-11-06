package com.example.myzuoye.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机后把小组件状态复位。
 *
 * 小组件的状态存在 SharedPreferences 里，桌面进程只能读到这份快照。
 * 手机重启后连接肯定已经断了，但快照还停在「已连接」——桌面上就会一直显示绿灯。
 * 开机广播是唯一能在「应用没被打开」时纠正它的机会。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        WidgetState.setConnected(context, false)
        WidgetState.setTransferring(context, false)
        TransferWidgetProvider.refresh(context)
    }
}
