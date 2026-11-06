package com.example.myzuoye.transfer

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream

/**
 * 大文件的「半成品」存放处，断点续传靠它。
 *
 * 为什么不用下载目录里的正式文件：
 * - Android 10+ 走 MediaStore，条目要 `IS_PENDING` 才不可见，但下次想按名字找回它、
 *   再以「追加」方式打开，MediaStore 的 API 既绕又不保证一致；
 * - 半成品放在应用专属外部目录（`Android/data/<包名>/files/partial`）里是普通 File，
 *   `append=true` 直接开、`length()` 直接查，恢复起来最省事。
 *
 * 收完后再复制进「下载/WiFi直传」，代价是一次本地拷贝——P2P 链路通常 10~30 MB/s，
 * 本地存储远快于此，比重新传一遍便宜得多。
 *
 * 只对超过 [RESUME_MIN_BYTES] 的文件走这条路：小文件一次就传完了，
 * 为它们多一次复制纯属浪费。
 */
object PartialStore {

    /** 小文件不值得走半成品流程 */
    const val RESUME_MIN_BYTES = 8L * 1024 * 1024

    /** 半成品保留时长：超过就清掉，避免长期占空间 */
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private fun dir(context: Context): File? {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val dir = File(base, "partial")
        return if (dir.exists() || dir.mkdirs()) dir else null
    }

    /**
     * 半成品文件名带上原始大小。
     *
     * 同一个文件名但大小不同（比如对方改了内容重发）必须当成两个文件，
     * 否则会把旧的一半拼到新文件上，出来的东西谁也看不懂。
     */
    private fun keyOf(fileName: String, size: Long): String {
        val safe = ReceivedFileStore.sanitizeFileName(fileName)
        return "$safe#$size.part"
    }

    fun fileFor(context: Context, fileName: String, size: Long): File? =
        dir(context)?.let { File(it, keyOf(fileName, size)) }

    /** 已有的字节数；没有半成品就是 0 */
    fun lengthOf(context: Context, fileName: String, size: Long): Long {
        val f = fileFor(context, fileName, size) ?: return 0
        return if (f.isFile) f.length() else 0
    }

    /** 以追加方式打开；不存在则新建 */
    fun openAppend(context: Context, fileName: String, size: Long, append: Boolean): FileOutputStream? =
        runCatching {
            val f = fileFor(context, fileName, size) ?: return null
            FileOutputStream(f, append)
        }.getOrNull()

    fun delete(context: Context, fileName: String, size: Long) {
        runCatching { fileFor(context, fileName, size)?.delete() }
    }

    /**
     * 启动时清理过期半成品。
     *
     * 只在应用启动时调一次，不能放在传输路径上——扫描目录本身有 IO 开销，
     * 而且正在传输的半成品不能被误删。
     */
    fun cleanup(context: Context, now: Long = System.currentTimeMillis()) {
        runCatching {
            val d = dir(context) ?: return
            d.listFiles()?.forEach { f ->
                if (f.isFile && now - f.lastModified() > MAX_AGE_MS) f.delete()
            }
        }
    }

    /** 当前占用空间，供设置页/日志展示 */
    fun totalBytes(context: Context): Long =
        runCatching {
            dir(context)?.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)
}
