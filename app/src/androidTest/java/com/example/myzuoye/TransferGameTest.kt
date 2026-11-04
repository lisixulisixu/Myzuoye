package com.example.myzuoye

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 传输小游戏窗口的仪器测试：用真实 Activity 承载 Dialog，验证窗口能弹出、
 * 进度/完成回调能更新、能正常关闭。
 */
@RunWith(AndroidJUnit4::class)
class TransferGameTest {

    @Test
    fun 窗口能弹出并更新传输进度() {
        lateinit var game: TransferGame
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            game = TransferGame(activity)
            game.show()
        }

        scenario.onActivity {
            assertTrue("窗口应已显示", game.isShowing())
            game.onProgress("测试文件.txt", 0.5f)
            game.onDone("测试文件.txt", true)
        }

        scenario.onActivity {
            game.dismiss()
            assertTrue("窗口应已关闭", !game.isShowing())
        }
        scenario.close()
    }
}
