package com.example.myzuoye.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.myzuoye.transfer.TransferManager

/**
 * 通知栏上的动作入口。
 *
 * 传输通知是从前台服务发的，`PendingIntent` 直接指向这个广播：
 * - 取消：跑在主进程里，能直接拿到 [TransferManager] 单例，不用再跨一次 AIDL。
 *
 * 之所以不做成 Actvity 跳转：用户点「取消」时不想被拉到应用里，
 * 传大文件时界面已经在后台，弹出来反而打断他正在做的事。
 */
class TransferActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CANCEL -> TransferManager.cancelActive()
        }
    }

    companion object {
        const val ACTION_CANCEL = "com.example.myzuoye.action.CANCEL_TRANSFER"
    }
}
