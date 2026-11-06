package com.example.myzuoye.transfer

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
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
    private val onCommit: () -> Boolean,
    private val onDiscard: (keepPartial: Boolean) -> Unit,
    initialWritten: Long = 0L,
    /**
     * 续传时把已落盘的那部分喂给 digest。
     *
     * 文件级 MD5 是对整份文件算的，只算后半段接上去永远比不上，
     * 所以断点续传必须先把已有部分重新读一遍——本地读盘比重新下载便宜得多。
     */
    private val existingReader: ((java.security.MessageDigest) -> Unit)? = null
) {
    /** 已写入字节数（续传时包含之前已经落盘的部分），用于和协议声明的大小核对 */
    var written: Long = initialWritten
        private set

    /** 开始时已有的字节数：>0 说明这是一次续传 */
    val resumedFrom: Long = initialWritten

    fun write(bytes: ByteArray) {
        stream.write(bytes)
        written += bytes.size
    }

    /** 续传开始前调用：把磁盘上已有的字节计入 digest，保证最终 MD5 覆盖整份文件 */
    fun primeDigest(digest: java.security.MessageDigest) {
        existingReader?.invoke(digest)
    }

    /** 收尾。返回 false 表示最终落盘失败（续传时要把半成品再复制一次，这一步可能失败） */
    fun commit(): Boolean {
        runCatching { stream.flush() }
        runCatching { stream.close() }
        return runCatching { onCommit() }.getOrDefault(false)
    }

    /**
     * 放弃本次接收。
     * @param keepPartial true 时保留已收到的部分，下次同名同大小的传输可以接着传
     */
    fun discard(keepPartial: Boolean = false) {
        runCatching { stream.close() }
        runCatching { onDiscard(keepPartial) }
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
     *
     * [expectedSize] 达到 [PartialStore.RESUME_MIN_BYTES] 时走「半成品 + 收完再复制」的路径，
     * 让传输中断后可以接着传；小文件仍然直接写进下载目录，不多一次拷贝。
     */
    fun create(context: Context, rawName: String, expectedSize: Long = 0L): ReceiveTarget? {
        val safeName = sanitizeFileName(rawName)
        if (expectedSize >= PartialStore.RESUME_MIN_BYTES) {
            createResumable(context, safeName, expectedSize)?.let { return it }
            // 半成品目录不可用时退回直写，功能不受影响，只是没有续传能力
        }
        return createDirect(context, safeName)
    }

    /** 直接写最终目录（老路径，小文件与降级时使用） */
    private fun createDirect(context: Context, safeName: String): ReceiveTarget? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createInDownloads(context, safeName)
        } else {
            createLegacy(context, safeName)
        }
    }

    /**
     * 大文件：先写应用专属目录里的半成品，收完再复制进下载目录。
     *
     * 若已经存在同名同大小的半成品，则以追加方式打开，[ReceiveTarget.resumedFrom]
     * 就是已经收到的字节数，上层据此告诉发送端从哪继续。
     */
    private fun createResumable(context: Context, safeName: String, size: Long): ReceiveTarget? {
        val existing = PartialStore.lengthOf(context, safeName, size).coerceAtMost(size)
        val stream = PartialStore.openAppend(context, safeName, size, append = existing > 0) ?: return null

        return ReceiveTarget(
            fileName = safeName,
            displayPath = "下载/$SUB_DIR/$safeName",
            stream = stream,
            initialWritten = existing,
            onCommit = { copyPartialToFinal(context, safeName, size) },
            onDiscard = { keepPartial ->
                if (!keepPartial) PartialStore.delete(context, safeName, size)
            },
            existingReader = if (existing > 0) {
                { digest ->
                    runCatching {
                        val part = PartialStore.fileFor(context, safeName, size)
                        part?.inputStream()?.use { input ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                digest.update(buffer, 0, read)
                            }
                        }
                    }
                }
            } else {
                null
            }
        )
    }

    /** 把半成品复制进「下载/WiFi直传」，成功后才删除半成品 */
    private fun copyPartialToFinal(context: Context, safeName: String, size: Long): Boolean {
        val part = PartialStore.fileFor(context, safeName, size) ?: return false
        val final = createDirect(context, safeName) ?: return false

        val copied = runCatching {
            part.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    final.write(if (read == buffer.size) buffer else buffer.copyOf(read))
                }
            }
            final.commit()
        }.getOrDefault(false)

        if (copied) {
            runCatching { part.delete() }
        } else {
            runCatching { final.discard() }
        }
        return copied
    }

    /** 一条已接收文件的信息，用于列表展示 */
    data class ReceivedFile(
        val name: String,
        val sizeBytes: Long,
        val dateModifiedMillis: Long,
        val uri: Uri
    )

    /**
     * 列出「下载/WiFi直传」目录下的所有文件，按修改时间倒序（新的在前）。
     *
     * 只读操作，任何异常都吞掉并返回空列表——查看功能不该因为某个文件损坏而崩溃。
     */
    fun list(context: Context): List<ReceivedFile> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listFromMediaStore(context)
        } else {
            listLegacy(context)
        }
    }.getOrDefault(emptyList())

    /**
     * 删除一个已接收文件。
     *
     * 本应用自己创建的 MediaStore 条目不需要额外权限就能删；
     * 旧系统上是普通文件，直接 delete。删不掉也不抛异常，只返回 false 让界面提示。
     */
    fun delete(context: Context, file: ReceivedFile): Boolean {
        return if (file.uri.scheme == "file") {
            runCatching { File(file.uri.path.orEmpty()).delete() }.getOrDefault(false)
        } else {
            runCatching { context.contentResolver.delete(file.uri, null, null) > 0 }
                .getOrDefault(false)
        }
    }

    private fun listFromMediaStore(context: Context): List<ReceivedFile> {        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR/"

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val result = mutableListOf<ReceivedFile>()
        resolver.query(
            collection, projection, selection, arrayOf(relativePath),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                result += ReceivedFile(
                    name = cursor.getString(nameCol) ?: "未命名",
                    sizeBytes = cursor.getLong(sizeCol),
                    dateModifiedMillis = cursor.getLong(dateCol) * 1000L,
                    uri = ContentUris.withAppendedId(collection, id)
                )
            }
        }
        return result
    }

    private fun listLegacy(context: Context): List<ReceivedFile> {
        val dir = legacyDir(context) ?: return emptyList()
        val files = dir.listFiles()?.filter { it.isFile } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { f ->
            ReceivedFile(
                name = f.name,
                sizeBytes = f.length(),
                dateModifiedMillis = f.lastModified(),
                uri = Uri.fromFile(f)
            )
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
                    runCatching { resolver.update(uri, done, null, null) }.isSuccess
                },
                onDiscard = { _ -> runCatching { resolver.delete(uri, null, null) }; Unit }
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
                onCommit = { true },
                onDiscard = { _ -> runCatching { file.delete() }; Unit }
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

    /** 半成品复制到下载目录时的缓冲区：一次 256KB，比逐块 64KB 少一大截系统调用 */
    private const val COPY_BUFFER_BYTES = 256 * 1024
}
