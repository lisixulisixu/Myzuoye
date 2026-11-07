package com.example.myzuoye.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.myzuoye.weather.WeatherRepository

/**
 * 系统事件触发的「顺手刷新」。
 *
 * 小组件的 30 分钟定时刷新是系统下限，但插电/拔电这两个时刻用户很可能会看桌面，
 * 顺手补一次，卡片上的天气和时间就不至于「半天没动」。
 *
 * 为什么只收电源广播：`CONNECTIVITY_ACTION` 从 Android 8 起就不再投递给
 * 清单里注册的接收器（隐式广播限制），而电源广播在豁免名单里，所以这里只用它。
 *
 * 不会真的每次都请求网络：真正刷新走 [WeatherRepository.refreshAsync]，
 * 它内部有 15 分钟节流，这里只是「有机会就顺手刷一下」，不会把电量耗在这上面。
 */
class WidgetEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED -> {
                WeatherRepository.refreshAsync(context)
                TransferWidgetProvider.refresh(context)
            }
        }
    }
}
