package com.example.myzuoye

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myzuoye.transfer.ChatEntry
import com.example.myzuoye.ui.ChatAdapter
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 聊天气泡的「肉眼验收」：
 * 我方渐变气泡靠右、对方深色气泡靠左、系统行居中、文件卡带缩略图占位。
 * fileUri 故意给一个不存在的 content://，验证缩略图加载失败时安静降级（不崩、不留白块）。
 */
@RunWith(AndroidJUnit4::class)
class ChatBubblePreviewTest {

    private val width = 620
    private val height = 520

    @Test
    fun 导出气泡渲染对照图() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        val entries = listOf(
            ChatEntry(ChatEntry.Kind.SYSTEM, "通道已建立"),
            ChatEntry(ChatEntry.Kind.PEER, "你好呀，文件准备好啦"),
            ChatEntry(ChatEntry.Kind.MINE, "收到，这就开始传输~"),
            ChatEntry(ChatEntry.Kind.SYSTEM, "文件接收完成：photo.jpg，已保存到 下载/WiFi直传/photo.jpg"),
            ChatEntry(
                ChatEntry.Kind.FILE_IN,
                "文件接收完成：photo.jpg，已保存到 下载/WiFi直传/photo.jpg",
                fileUri = "content://nonexistent/12345",
                at = System.currentTimeMillis()
            ),
            ChatEntry(ChatEntry.Kind.PEER, "这条很长很长很长很长很长很长很长很长很长很长很长的话应该会折行而不至于撑出屏幕")
        )

        lateinit var bmp: Bitmap
        instrumentation.runOnMainSync {
            val adapter = ChatAdapter()
            adapter.submitAll(entries)
            val rv = RecyclerView(context)
            rv.layoutManager = LinearLayoutManager(context)
            rv.adapter = adapter
            rv.setBackgroundColor(Color.parseColor("#141B28"))
            rv.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            rv.layout(0, 0, width, height)
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            rv.draw(Canvas(bmp))
        }

        // 先导出对照图（人看）
        val dir = File(context.filesDir, "motion_preview").apply { mkdirs() }
        val out = File(dir, "chat_bubbles.png")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("对照图未生成", out.exists() && out.length() > 0)

        // 我方气泡：右半边要有渐变蓝（B 通道明显高于 R）；对方气泡：左半边要有面板色亮于背景
        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        bmp.recycle()
        var rightBlue = 0
        var leftBright = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (x > width * 2 / 3 && b > r + 40 && b > 100) rightBlue++
                if (x < width / 3 && g > 40 && r > 20 && p != 0xFF141B28.toInt()) leftBright++
            }
        }
        assertTrue("右半区应有我方蓝色气泡（$rightBlue px）", rightBlue > 200)
        assertTrue("左半区应有对方气泡内容（$leftBright px）", leftBright > 100)
    }
}
