package com.example.myzuoye.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * 已收文件列表的缩略图加载。
 *
 * 为什么自己写而不是用 Glide/Coil：只为了一张 48dp 的小图引一个图片库不划算，
 * 这里要的功能也就是「后台解码 + 简单缓存 + 列表复用时别贴错图」。
 *
 * 关键点是**贴图前校验 tag**：列表滑动时 ViewHolder 会被复用，
 * 异步解码回来时那一格可能早就换成别的文件了，不校验就会出现「图片贴在错的行上」。
 */
object ThumbnailLoader {

    private val executor = Executors.newFixedThreadPool(2)

    /** 按 Uri + 目标尺寸缓存；上限按「图片数量」而不是字节数控制，够用且简单 */
    private val cache = object : LruCache<String, Bitmap>(120) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /** 图片/视频才显示缩略图，其他类型显示分类图标 */
    fun isThumbnailable(name: String): Boolean {
        val mime = mimeOf(name)
        return mime.startsWith("image/") || mime.startsWith("video/")
    }

    fun load(context: Context, uri: Uri, name: String, target: ImageView) {
        val key = "$uri@${target.width}x${target.height}"
        cache.get(key)?.let {
            target.tag = key
            target.setImageBitmap(it)
            return
        }

        target.tag = key
        target.setImageDrawable(null)

        val appContext = context.applicationContext
        executor.execute {
            val bitmap = decode(appContext, uri, name)
            if (bitmap == null) return@execute
            cache.put(key, bitmap)
            target.post {
                // 复用后这一格可能已经换成别的文件了，对不上就别贴
                if (target.tag == key) target.setImageBitmap(bitmap)
            }
        }
    }

    private fun decode(context: Context, uri: Uri, name: String): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 系统缩略图接口对图片和视频都适用，还能省掉自己算采样率
            context.contentResolver.loadThumbnail(uri, Size(THUMB_PX, THUMB_PX), null)
        } else {
            decodeSampled(context, uri)
        }
    }.getOrNull()

    private fun decodeSampled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val sample = sampleSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= THUMB_PX && h / 2 >= THUMB_PX) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    /** 大致的像素尺寸，和 48dp 在 3x 屏上差不多，够清晰也不浪费内存 */
    private const val THUMB_PX = 160
}
