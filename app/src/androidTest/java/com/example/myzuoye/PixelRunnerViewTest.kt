package com.example.myzuoye

import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myzuoye.ui.PixelRunnerView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 像素跑酷游戏的仪器测试：验证 View 能正常启动游戏循环、计分回调能触发。
 *
 * 物理模拟（跳跃、障碍物移动、碰撞）依赖真实时间推进，这里只验证核心的
 * 「启动 → 计分回调」链路可用，不验证碰撞的精确时机（那是游戏性调参范畴）。
 */
@RunWith(AndroidJUnit4::class)
class PixelRunnerViewTest {

    @Test
    fun 启动后能运行并触发计分回调() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = PixelRunnerView(context)
        val container = FrameLayout(context)
        container.addView(view, FrameLayout.LayoutParams(600, 800))

        val scoreReached = intArrayOf(0)
        val runningState = booleanArrayOf(false)

        // 在主线程上启动游戏，并先强制布局
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            container.measure(600, 800)
            container.layout(0, 0, 600, 800)
            view.onScoreChanged = { score -> scoreReached[0] = score }
            view.start()
            runningState[0] = view.running
        }
        assertTrue("start 后应处于运行状态", runningState[0])

        // 开场有 3 秒倒计时，倒计时结束后障碍物才出现；
        // 持续点击模拟跳跃躲避，让人物能越过障碍物并计分。
        //
        // 注意：游戏的物理是按「帧间隔」推进的，每帧最多按 1/30 秒计算。
        // 模拟器负载高时实际帧率可能只有 10~15fps，游戏时间会明显慢于墙上时间，
        // 所以这里不能用固定 7 秒卡死，而是给足 25 秒并把「撞死暂停」也考虑进去：
        // 一旦发现已经暂停（撞到障碍物），就重开一局继续测。
        val deadline = System.currentTimeMillis() + 25000L
        var restarts = 0
        while (System.currentTimeMillis() < deadline && scoreReached[0] < 1) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                if (!view.running) {
                    // 上一局撞到障碍物了，重开
                    restarts++
                    view.start()
                } else {
                    view.dispatchTouchEvent(
                        android.view.MotionEvent.obtain(
                            System.currentTimeMillis(), System.currentTimeMillis(),
                            android.view.MotionEvent.ACTION_DOWN, 300f, 400f, 0
                        )
                    )
                    view.dispatchTouchEvent(
                        android.view.MotionEvent.obtain(
                            System.currentTimeMillis(), System.currentTimeMillis(),
                            android.view.MotionEvent.ACTION_UP, 300f, 400f, 0
                        )
                    )
                }
            }
            Thread.sleep(90L)
        }

        assertTrue(
            "持续跳跃应至少越过一个障碍物（实际 ${scoreReached[0]}，重开 $restarts 次）",
            scoreReached[0] >= 1
        )

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.pause()
            runningState[0] = view.running
        }
        assertTrue("pause 后应停止运行", !runningState[0])
    }
}
