package com.example.myzuoye.transfer

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 一次接收任务的落盘目标。
 *
 * 抽象出这一层是因为文件的「家」有两套完全不同的实现：
 * - Android 10 及以上写 MediaStore，落在公共「下载/WiFi直传」目录，文件管理器、相册、微信都能直接看到；
 * - Android 9 及以下没有分区存储，直接写外部存储的 Download 目录。
 *
 * 上层只关心「写字节 / 收尾成功 / 放弃清理」三件事，不关心 Uri 还是 File。
 *
 * 先写后确认的语义：[commit] 之前文件对别的应用不可见（IS_PENDING=1），
 * 校验失败调用 [discard] 就不会在下载目录里留下半截垃圾文件。
 */
class ReceiveTarget(
    val fileName: String,
    /** 给用户看的路径，例如「下载/WiFi直传/照片.jpg」 */
    val displayPath: String,
    private val stream: OutputStream,
    private val onCommit: () -> Unit,
    private val onDiscard: () -> Unit
) {
    /** 已写入字节数，用于和协议声明的大小核对 */
    var written: Long = 0L
        private set

    fun write(bytes: ByteArray) {
        stream.write(bytes)
        written += bytes.size
    }

    fun commit() {
        runCatching { stream.flush() }
        runCatching { stream.close() }
        runCatching { onCommit() }
    }

    fun discard() {
        runCatching { stream.close() }
        runCatching { onDiscard() }
    }
}

object ReceivedFileStore {

    /** 下载目录下的子目录名，避免和用户自己的下载文件混在一起 */
    const val SUB_DIR = "WiFi直传"

    /**
     * 为 [rawName] 创建一个可写的接收目标。
     *
     * 重名不覆盖：自动改成「名字 (1).png」「名字 (2).png」这样的形式。
     * 返回 null 表示无法创建（权限不足 / 存储不可用），调用方应放弃本次传输。
     */
    fun create(context: Context, rawName: String): ReceiveTarget? {
        val safeName = sanitizeFileName(rawName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createInDownloads(context, safeName)
        } else {
            createLegacy(context, safeName)
        }
    }

    // ------------------------------------------------------------------ MediaStore

    private fun createInDownloads(context: Context, safeName: String): ReceiveTarget? {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR/"

        return runCatching {
            val name = uniqueMediaName(resolver, collection, relativePath, safeName)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                // 写入期间对外隐藏，校验通过后再放开
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri: Uri = resolver.insert(collection, values) ?: return null
            val stream = resolver.openOutputStream(uri) ?: run {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }

            ReceiveTarget(
                fileName = name,
                displayPath = "下载/$SUB_DIR/$name",
                stream = stream,
                onCommit = {
                    val done = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(uri, done, null, null)
                },
                onDiscard = { runCatching { resolver.delete(uri, null, null) } }
            )
        }.getOrNull()
    }

    /** 同名文件不覆盖，依次尝试「名字 (1).ext」「名字 (2).ext」… */
    private fun uniqueMediaName(
        resolver: ContentResolver,
        collection: Uri,
        relativePath: String,
        name: String
    ): String {
        if (!mediaNameExists(resolver, collection, relativePath, name)) return name
        var index = 1
        while (index <= MAX_DUPLICATE_INDEX) {
            val candidate = appendIndex(name, index)
            if (!mediaNameExists(resolver, collection, relativePath, candidate)) return candidate
            index++
        }
        return appendSuffix(name, " (${System.currentTimeMillis()})")
    }

    private fun mediaNameExists(
        resolver: ContentResolver,
        collection: Uri,
        relativePath: String,
        name: String
    ): Boolean {
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        return runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                arrayOf(name, relativePath),
                null
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ 旧系统

    /**
     * Android 9 及以下：直接写外部存储的 Download 目录，需要 WRITE_EXTERNAL_STORAGE。
     * 权限没给或者存储不可用时退到应用专属外部目录（USB 连接电脑可直接看到，卸载即清理）。
     */
    private fun createLegacy(context: Context, safeName: String): ReceiveTarget? {
        val dir = legacyDir(context) ?: return null
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            val file = uniqueFile(dir, safeName)
            ReceiveTarget(
                fileName = file.name,
                displayPath = file.absolutePath,
                stream = FileOutputStream(file),
                onCommit = {},
                onDiscard = { runCatching { file.delete() } }
            )
        }.getOrNull()
    }

    private fun legacyDir(context: Context): File? {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            val public = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                SUB_DIR
            )
            if (public.exists() || public.mkdirs()) return public
        }

        val fallback = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val dir = File(fallback, SUB_DIR)
        return if (dir.exists() || dir.mkdirs()) dir else null
    }

    private fun uniqueFile(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        var index = 1
        while (index <= MAX_DUPLICATE_INDEX) {
            val candidate = File(dir, appendIndex(name, index))
            if (!candidate.exists()) return candidate
            index++
        }
        return File(dir, appendSuffix(name, " (${System.currentTimeMillis()})"))
    }

    // ------------------------------------------------------------------ 工具

    /** 去掉路径分隔符等危险字符，防止 ../ 之类的名字把文件写到目录外 */
    fun sanitizeFileName(raw: String): String {
        var name = raw.replace('\\', '_').replace('/', '_').trim()
        if (name.isEmpty() || name == "." || name == "..") name = "received_file"
        return name
    }

    /** 在扩展名前插入序号：照片.jpg → 照片 (1).jpg */
    private fun appendIndex(name: String, index: Int): String = appendSuffix(name, " ($index)")

    private fun appendSuffix(name: String, suffix: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) {
            "${name.substring(0, dot)}$suffix${name.substring(dot)}"
        } else {
            name + suffix
        }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    /** 重名最多尝试这么多次，超出就用时间戳兜底 */
    private const val MAX_DUPLICATE_INDEX = 999
}
