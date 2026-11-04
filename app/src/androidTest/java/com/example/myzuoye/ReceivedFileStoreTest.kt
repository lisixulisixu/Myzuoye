package com.example.myzuoye

import android.content.ContentUris
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myzuoye.transfer.ReceivedFileStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets

/**
 * 接收落盘的仪器测试：验证真的写进了系统「下载」目录，且重名不覆盖。
 *
 * 这一层没法用 JVM 单测覆盖（MediaStore 是系统服务），所以放 androidTest，
 * 需要连着设备/模拟器跑 `./gradlew connectedDebugAndroidTest`。
 */
@RunWith(AndroidJUnit4::class)
class ReceivedFileStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val relPath = "${Environment.DIRECTORY_DOWNLOADS}/${ReceivedFileStore.SUB_DIR}/"
    private val created = mutableListOf<String>()

    @After
    fun cleanup() {
        created.forEach { name -> findUri(name)?.let { context.contentResolver.delete(it, null, null) } }
        created.clear()
    }

    @Test
    fun 同名文件不会互相覆盖() {
        val base = "wbtest_${System.currentTimeMillis()}.txt"
        val first = "第一份内容".toByteArray(StandardCharsets.UTF_8)
        val second = "第二份内容".toByteArray(StandardCharsets.UTF_8)

        val t1 = ReceivedFileStore.create(context, base)
        assertNotNull("应能在下载目录创建接收目标", t1)
        created += t1!!.fileName
        t1.write(first)
        t1.commit()
        assertEquals(first.size.toLong(), t1.written)

        val t2 = ReceivedFileStore.create(context, base)
        assertNotNull("第二个同名目标也应创建成功", t2)
        created += t2!!.fileName
        t2.write(second)
        t2.commit()

        assertNotEquals("同名文件必须改名而不是覆盖", t1.fileName, t2.fileName)
        assertTrue("应加序号后缀：${t2.fileName}", t2.fileName.contains("(1)"))

        assertEquals("第一份内容", readText(t1.fileName))
        assertEquals("第二份内容", readText(t2.fileName))
    }

    @Test
    fun 放弃的传输不会在下载目录留下文件() {
        val name = "wbdiscard_${System.currentTimeMillis()}.txt"
        val target = ReceivedFileStore.create(context, name)
        assertNotNull(target)

        target!!.write("只写了一部分".toByteArray(StandardCharsets.UTF_8))
        target.discard()

        assertNull("放弃后不应留下任何条目", findUri(target.fileName))
    }

    @Test
    fun 列表能列出已接收的文件且按时间倒序() {
        val base = "wblist_${System.currentTimeMillis()}"
        val first = ReceivedFileStore.create(context, "$base-a.txt")!!
        first.write("A".toByteArray(StandardCharsets.UTF_8))
        first.commit()
        created += first.fileName

        Thread.sleep(1100L) // MediaStore 的时间戳按秒，隔开两次写入

        val second = ReceivedFileStore.create(context, "$base-b.txt")!!
        second.write("B".toByteArray(StandardCharsets.UTF_8))
        second.commit()
        created += second.fileName

        val list = ReceivedFileStore.list(context)
        val names = list.map { it.name }
        assertTrue("列表应包含 $base-a.txt", base + "-a.txt" in names)
        assertTrue("列表应包含 $base-b.txt", base + "-b.txt" in names)

        val idxA = names.indexOf("$base-a.txt")
        val idxB = names.indexOf("$base-b.txt")
        assertTrue("后写入的 B 应排在 A 前面（时间倒序）", idxB < idxA)

        val entryA = list.first { it.name == "$base-a.txt" }
        assertTrue("大小应正确", entryA.sizeBytes == 1L)
        assertNotNull("应有可打开的 Uri", entryA.uri)
    }

    private fun findUri(name: String): Uri? {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        return context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            arrayOf(name, relPath),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(collection, cursor.getLong(0))
            } else {
                null
            }
        }
    }

    private fun readText(name: String): String? {
        val uri = findUri(name) ?: return null
        return context.contentResolver.openInputStream(uri)?.use {
            String(it.readBytes(), StandardCharsets.UTF_8)
        }
    }
}
