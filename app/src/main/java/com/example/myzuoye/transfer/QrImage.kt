package com.example.myzuoye.transfer

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 二维码图与文本的互转（依赖 Android 图形 + ZXing，JVM 单测不碰这里）。
 *
 * 生成与识别都做在应用内，是为了不引入相机权限与额外扫码依赖：
 * 展示端直接画一张图，识别端让用户从相册选对方的二维码截图/照片。
 * 两台设备面对面时，「截屏 → 发过去 → 相册识别」比实时相机扫码少一个权限弹窗，
 * 在模拟器上也能完整验证（模拟器没有可用的相机内容）。
 */
object QrImage {

    /** 生成二维码位图；二维码自带静区，外层 UI 不需要再留白 */
    fun encode(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            content,
            com.google.zxing.BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }

    /** 从位图识别二维码文本；识别失败（不是二维码/太模糊）返回 null */
    fun decode(bitmap: Bitmap): String? = runCatching {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        MultiFormatReader().apply { setHints(hints) }
            .decode(BinaryBitmap(HybridBinarizer(source)))
            .text
    }.getOrNull()
}
