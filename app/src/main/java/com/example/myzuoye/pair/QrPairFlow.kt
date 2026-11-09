package com.example.myzuoye.pair

import android.net.Uri
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.example.myzuoye.MainActivity
import com.example.myzuoye.R
import com.example.myzuoye.transfer.QrImage
import com.example.myzuoye.transfer.QrPair
import com.example.myzuoye.transfer.TransferManager
import com.example.myzuoye.ui.AppDialog

/**
 * 二维码配对，从 MainActivity 拆出来的独立功能块。
 *
 * 三个入口：
 * - [showQrDialog]：展示本机的地址二维码（对方扫码就连上来）；
 * - [launchScan]：从相册选一张二维码图片识别后连接；
 * - [showPairCodePrompt]：被对端以配对码拒绝时，弹框让用户填码重连。
 *
 * **必须在 Activity 构造期创建本类**：[scanLauncher] 要在 onCreate 之前注册
 * （registerForActivityResult 的硬要求），惰性初始化会在 RESUMED 状态注册而直接抛异常。
 *
 * 依赖的 Activity 成员以 internal 可见性开放，逻辑原样搬移，行为不变。
 */
internal class QrPairFlow(private val host: MainActivity) {

    /**
     * 从相册选一张二维码图片并连接。
     *
     * 用系统照片选择器（Photo Picker）：不需要存储权限，返回的临时 Uri 本应用就能读。
     * 解码放后台线程——一张大图缩放 + 识别可能上百毫秒，主线程做会卡住过渡动画。
     */
    private val scanLauncher =
        host.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            host.toast(host.getString(R.string.qr_decoding))
            Thread {
                val text = runCatching { decodeQrFromUri(uri) }.getOrNull()
                if (host.isFinishing || host.isDestroyed) return@Thread
                val payload = text?.let { QrPair.decode(it) }
                host.runOnUiThread {
                    if (host.isFinishing || host.isDestroyed) return@runOnUiThread
                    when {
                        text == null -> host.toast(host.getString(R.string.qr_not_found))
                        payload == null -> host.toast(host.getString(R.string.qr_not_ours))
                        else -> connectViaQr(payload)
                    }
                }
            }.start()
        }

    /** 弹出系统相册选择器（工具菜单 / 小组件扫码入口） */
    fun launchScan() {
        scanLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    /** 展示本机地址与配对码的二维码，对方扫码即可直连 */
    fun showQrDialog() {
        if (host.isFinishing) return
        val ip = com.example.myzuoye.net.LanAddress.primaryIpv4()
        if (ip == null) {
            host.toast(host.getString(R.string.qr_no_ip))
            return
        }
        val payload = QrPair.Payload(
            host = ip,
            port = TransferManager.port,
            pairCode = host.prefs().getString(MainActivity.KEY_PAIR_CODE, null).orEmpty().ifEmpty { null }
        )
        val size = (280 * host.resources.displayMetrics.density).toInt()
        val qr = runCatching { QrImage.encode(QrPair.encode(payload), size) }.getOrNull()
        if (qr == null) {
            host.toast(host.getString(R.string.qr_generate_failed))
            return
        }
        val container = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            val pad = (24 * host.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val codeLabel = payload.pairCode
            ?.let { host.getString(R.string.qr_pair_code_label, it) }
            ?: host.getString(R.string.qr_pair_code_none)
        container.addView(TextView(host).apply {
            text = host.getString(R.string.qr_addr_label, "$ip:${TransferManager.port}") + "\n" + codeLabel
            textSize = 13f
            gravity = android.view.Gravity.CENTER
        })
        container.addView(android.widget.ImageView(host).apply {
            setImageBitmap(qr)
            val m = (12 * host.resources.displayMetrics.density).toInt()
            setPadding(0, m, 0, 0)
            contentDescription = host.getString(R.string.qr_show)
        })
        AppDialog.builder(host)
            .setTitle(R.string.qr_show)
            .setView(container)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    /**
     * 对端拒了我们（配对码不对）：弹框让用户填码再连一次。
     *
     * 不用 Toast 而用输入框：这是唯一能继续往下走的动作，
     * 用户看到提示却没法输入的话，只能重新点一遍设备。
     */
    fun showPairCodePrompt(reason: String) {
        if (host.isFinishing) return
        val density = host.resources.displayMetrics.density
        val container = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = EditText(host).apply {
            hint = host.getString(R.string.pair_code_input_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            setSingleLine(true)
        }
        container.addView(input)

        val body = host.getString(
            R.string.pair_code_prompt_body,
            reason.ifBlank { host.getString(R.string.pair_code_prompt_fallback) }
        )

        AppDialog.builder(host)
            .setTitle(R.string.pair_code_prompt_title)
            .setMessage(body)
            .setView(container)
            .setPositiveButton(R.string.pair_code_connect) { _, _ ->
                val code = input.text.toString().trim()
                if (code.length != 4) {
                    host.toast(host.getString(R.string.pair_code_invalid))
                    return@setPositiveButton
                }
                if (!TransferManager.retryWithPairCode(code)) {
                    host.toast(host.getString(R.string.channel_not_ready))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ 内部

    /** 二维码解析成功：带码直连。配对码顺手存起来，下次重启仍生效 */
    private fun connectViaQr(payload: QrPair.Payload) {
        if (host.isFinishing) return
        val code = payload.pairCode
        if (code != null) {
            host.prefs().edit().putString(MainActivity.KEY_PAIR_CODE, code).apply()
            TransferManager.setPairCode(code)
        }
        TransferManager.stop()
        TransferManager.setP2pNetwork(null)
        host.unbindProcessNetwork()
        // 局域网/转发链路 RTT 远大于 P2P（如模拟器 adb 转发），放宽单次超时
        TransferManager.connect(payload.host, payload.port, timeoutMs = 5000)
        host.log(host.getString(R.string.qr_connect_log, payload.host, payload.port))
        host.showHint(host.getString(R.string.qr_connect_hint, payload.host))
    }

    /**
     * 读 Uri 并识别二维码。
     *
     * 手机拍的照片动辄 4000×3000（ARGB 解码后 48MB），直接 `decodeStream` 在低端机上
     * 会把进程撑爆。先只读尺寸算采样率，再按上限 [QR_DECODE_MAX_PX] 解码——二维码
     * 通常只占画面一小块，采到 1400px 仍足够识别，内存降到十几 MB。
     */
    private fun decodeQrFromUri(uri: Uri): String? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        host.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / (sample * 2) >= QR_DECODE_MAX_PX && h / (sample * 2) >= QR_DECODE_MAX_PX) {
            sample *= 2
        }
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = host.contentResolver.openInputStream(uri)
            ?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        return try {
            QrImage.decode(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        /** 二维码识别时的位图上限边长：够识别，又不至于把大图整张解出来 */
        const val QR_DECODE_MAX_PX = 1400
    }
}
