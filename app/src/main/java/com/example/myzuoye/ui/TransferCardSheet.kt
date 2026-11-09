package com.example.myzuoye.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.example.myzuoye.R
import com.example.myzuoye.transfer.TransferLog
import java.io.File
import java.io.FileOutputStream

/**
 * 传输卡片弹窗：预览 + 保存到相册 + 分享。
 *
 * 卡片本身由 [TransferCardRenderer] 离屏画好，这里只负责摆放和落地：
 * - 保存：Android 10+ 走 MediaStore 写进相册（`Pictures/WiFi快传`），更早的版本
 *   写公共图片目录再通知媒体扫描（清单里已经为低版本声明了写外部存储权限）；
 * - 分享：先写进应用内部 `files/cards`，再走 FileProvider 授时 Uri——
 *   直接把 file:// 塞进 Intent 在 API 24+ 会抛 FileUriExposedException。
 */
object TransferCardSheet {

    /** 用一条传输记录弹卡片 */
    fun show(activity: Activity, entry: TransferLog.Entry) {
        if (activity.isFinishing) return
        val data = TransferCardRenderer.CardData(
            fileName = entry.name,
            size = entry.size,
            fromMe = entry.dir == TransferLog.DIR_SENT,
            at = if (entry.at > 0) entry.at else System.currentTimeMillis(),
            durationMs = entry.ms,
            peer = entry.peer,
            deviceName = deviceName()
        )
        val bitmap = runCatching { TransferCardRenderer.render(activity, data) }.getOrNull()
        if (bitmap == null) {
            android.widget.Toast.makeText(activity, R.string.card_render_failed, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        showBitmap(activity, bitmap, TransferCardRenderer.fileNameFor(data))
    }

    /** 直接展示一张已画好的卡片（测试与预览也走这里） */
    fun showBitmap(activity: Activity, bitmap: Bitmap, fileName: String) {
        val density = activity.resources.displayMetrics.density
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        container.addView(ImageView(activity).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = activity.getString(R.string.card_title)
        })
        container.addView(TextView(activity).apply {
            text = activity.getString(R.string.card_hint)
            textSize = 12f
            setTextColor(0xFF9FB0CC.toInt())
            gravity = Gravity.CENTER
            val top = (8 * density).toInt()
            setPadding(0, top, 0, 0)
        })

        AppDialog.builder(activity)
            .setTitle(R.string.card_title)
            .setView(container)
            .setPositiveButton(R.string.card_save) { _, _ ->
                val ok = saveToGallery(activity, bitmap, fileName)
                android.widget.Toast.makeText(
                    activity,
                    if (ok) R.string.card_saved else R.string.card_save_failed,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton(R.string.card_share) { _, _ -> share(activity, bitmap, fileName) }
            .setNegativeButton(R.string.card_close, null)
            .show()
    }

    // ------------------------------------------------------------------ 落地

    /** 写进相册。返回 false 表示系统不给写（无权限 / 存储已满） */
    fun saveToGallery(context: Context, bitmap: Bitmap, fileName: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/WiFi快传"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values
                ) ?: return false
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: return false
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            }.getOrDefault(false)
        }

        // Android 9 及以下：直接写公共图片目录，再让媒体扫描把它收进相册
        return runCatching {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "WiFi快传"
            ).apply { mkdirs() }
            val file = File(dir, fileName)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
            true
        }.getOrDefault(false)
    }

    /** 分享：先落一份到内部目录，再用 FileProvider 给出可读 Uri */
    fun share(context: Context, bitmap: Bitmap, fileName: String) {
        val file = runCatching {
            val dir = File(context.filesDir, "cards").apply { mkdirs() }
            File(dir, fileName).apply {
                FileOutputStream(this).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }.getOrNull() ?: return
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, context.getString(R.string.card_share))
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
    }

    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return when {
            model.isEmpty() -> maker
            maker.isEmpty() -> model
            model.startsWith(maker, ignoreCase = true) -> model
            else -> "$maker $model"
        }
    }
}
