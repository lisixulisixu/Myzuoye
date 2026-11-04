package com.example.myzuoye.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View

/**
 * 像素风跑酷小游戏：人物在左侧原地跑动，障碍物从右往左移动，点击屏幕让人物跳跃。
 *
 * 设计约束：
 * - 纯 Canvas 绘制像素块，无位图资源，开销极小；
 * - 用 [Choreographer] 驱动游戏循环（与屏幕刷新帧同步），[onDetachedFromWindow] 自动暂停，不空转耗电；
 * - 只有“点击跳跃”一个操作，点击屏幕任意位置都触发，符合“传输时单手可玩”的场景。
 */
class PixelRunnerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 跳跃越过障碍物的计数变化时回调 */
    var onScoreChanged: ((Int) -> Unit)? = null

    /** 碰撞后回调（用于结束本局） */
    var onGameOver: ((Int) -> Unit)? = null

    /** 是否正在运行（暂停时冻结画面，但不退出） */
    var running = false
        private set

    // ------------------------------------------------------------------ 游戏状态

    private var score = 0
    private var playerY = 0f            // 人物脚底 y 坐标
    private var playerVy = 0f           // 垂直速度（像素/帧）
    private var grounded = true
    private var obstacles = mutableListOf<Obstacle>()
    private var spawnTimer = 0f

    // 尺寸（onSizeChanged 里确定）
    private var groundY = 0f
    private var playerX = 0f

    /** 游戏循环是否已挂载到 Choreographer */
    private var looping = false
    private var lastFrameNanos = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // 像素块尺寸：一个“像素”= px 单位，所有图形都是它的整数倍，保持像素风
    private var px = 1f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (running) startLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopLoop()
    }

    fun start() {
        if (running) return
        // 尺寸还没确定时（尚未 layout），等布局完成再真正启动，避免人物/地面坐标错乱
        if (width == 0 || height == 0) {
            post { start() }
            return
        }
        running = true
        score = 0
        playerY = groundY
        playerVy = 0f
        grounded = true
        obstacles.clear()
        spawnTimer = 0f
        onScoreChanged?.invoke(score)
        startLoop()
        invalidate()
    }

    /** 暂停但保留画面（游戏结束后调用） */
    fun pause() {
        running = false
        stopLoop()
    }

    private fun startLoop() {
        if (looping) return
        looping = true
        lastFrameNanos = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        looping = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!looping) return
            // 用真实帧间隔驱动物理，保证不同刷新率下速度一致
            val dt = ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 1f / 30f)
            lastFrameNanos = frameTimeNanos
            if (running) tick(dt)
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun tick(dt: Float) {
        // 跳跃物理
        if (!grounded) {
            playerVy += GRAVITY * px * dt
            playerY += playerVy * dt
            if (playerY >= groundY) {
                playerY = groundY
                playerVy = 0f
                grounded = true
            }
        }

        // 生成障碍物
        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawnTimer = spawnInterval()
            obstacles += Obstacle(x = width + OBSTACLE_W * px, y = groundY)
        }

        // 移动障碍物，移除出屏，计分
        val playerLeft = playerX
        val playerRight = playerX + PLAYER_W * px
        val playerTop = playerY - PLAYER_H * px
        val playerBottom = playerY

        val iter = obstacles.iterator()
        while (iter.hasNext()) {
            val ob = iter.next()
            ob.x -= OBSTACLE_SPEED * px * dt

            // 越过且未计分 → 加分
            if (!ob.scored && ob.x + OBSTACLE_W * px < playerLeft) {
                ob.scored = true
                score++
                onScoreChanged?.invoke(score)
            }

            // 碰撞检测（AABB）
            if (!ob.collided) {
                val obLeft = ob.x
                val obRight = ob.x + OBSTACLE_W * px
                val obTop = ob.y - OBSTACLE_H * px
                val obBottom = ob.y
                val overlapX = playerRight > obLeft && playerLeft < obRight
                val overlapY = playerBottom > obTop && playerTop < obBottom
                if (overlapX && overlapY) {
                    ob.collided = true
                    pause()
                    onGameOver?.invoke(score)
                    return
                }
            }

            if (ob.x + OBSTACLE_W * px < 0) {
                iter.remove()
            }
        }

        invalidate()
    }

    private fun spawnInterval(): Float =
        MIN_SPAWN + (MAX_SPAWN - MIN_SPAWN) * Math.random().toFloat()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        px = h / 240f            // 用高度定像素比例，适配不同窗口
        groundY = h - 32f * px   // 底部留地面高度
        playerX = 24f * px       // 人物靠左
        if (grounded) playerY = groundY
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && running && grounded) {
            jump()
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun jump() {
        if (!grounded) return
        grounded = false
        playerVy = JUMP_VELOCITY * px
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 背景（浅色天空）
        paint.color = SKY
        canvas.drawRect(0f, 0f, w, h, paint)

        // 地面
        paint.color = GROUND
        canvas.drawRect(0f, groundY, w, h, paint)
        paint.color = GROUND_LINE
        canvas.drawRect(0f, groundY, w, groundY + px, paint)

        // 障碍物（像素方块）
        for (ob in obstacles) {
            drawObstacle(canvas, ob)
        }

        // 人物（像素小人）
        drawPlayer(canvas)
    }

    private fun drawObstacle(canvas: Canvas, ob: Obstacle) {
        val left = ob.x
        val top = ob.y - OBSTACLE_H * px
        val right = ob.x + OBSTACLE_W * px
        val bottom = ob.y
        paint.color = OBSTACLE
        canvas.drawRect(left, top, right, bottom, paint)
        // 高光，增加立体感
        paint.color = OBSTACLE_HI
        canvas.drawRect(left, top, left + OBSTACLE_W * px, top + 2 * px, paint)
    }

    private fun drawPlayer(canvas: Canvas) {
        val x = playerX
        val y = playerY
        val pw = PLAYER_W * px
        val ph = PLAYER_H * px

        // 身体（矩形）
        paint.color = BODY
        canvas.drawRect(x, y - ph, x + pw, y, paint)
        // 头（上方一个像素块）
        paint.color = HEAD
        canvas.drawRect(x + px, y - ph - 4 * px, x + pw - px, y - ph, paint)
        // 眼睛
        paint.color = EYE
        canvas.drawRect(x + 2 * px, y - ph - 3 * px, x + 3 * px, y - ph - 2 * px, paint)
        // 腿（奔跑时交替，简化成两个像素）
        paint.color = LEG
        val legShift = if ((System.currentTimeMillis() / 120) % 2 == 0L) 0f else px
        canvas.drawRect(x + px, y - px, x + 2 * px, y, paint)
        canvas.drawRect(x + pw - 3 * px + legShift, y - px, x + pw - 2 * px + legShift, y, paint)
    }

    // ------------------------------------------------------------------ 常量

    private data class Obstacle(
        var x: Float,
        val y: Float,
        var scored: Boolean = false,
        var collided: Boolean = false
    )

    private companion object {
        // 人物尺寸（单位：像素块）
        const val PLAYER_W = 10
        const val PLAYER_H = 14
        // 障碍物尺寸
        const val OBSTACLE_W = 8
        const val OBSTACLE_H = 12

        const val GRAVITY = 2200f          // 像素/秒²
        const val JUMP_VELOCITY = -880f    // 起跳初速度
        const val OBSTACLE_SPEED = 260f    // 障碍物移动速度（像素/秒）
        const val MIN_SPAWN = 0.9f         // 生成间隔下限（秒）
        const val MAX_SPAWN = 1.6f         // 生成间隔上限（秒）

        // 配色（像素风，低饱和）
        val SKY = Color.rgb(235, 242, 250)
        val GROUND = Color.rgb(120, 170, 120)
        val GROUND_LINE = Color.rgb(90, 140, 90)
        val OBSTACLE = Color.rgb(90, 90, 100)
        val OBSTACLE_HI = Color.rgb(130, 130, 145)
        val BODY = Color.rgb(60, 110, 180)
        val HEAD = Color.rgb(250, 205, 140)
        val EYE = Color.rgb(30, 30, 40)
        val LEG = Color.rgb(40, 40, 55)
    }
}
