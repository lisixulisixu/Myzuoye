package com.example.myzuoye

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.example.myzuoye.ui.PixelRunnerView

/**
 * 传输期间弹出的小游戏窗口。
 *
 * 职责：在传输进行时给用户一个消遣的像素跑酷游戏，同时把传输状态实时显示在顶部①区。
 *
 * 交互约定：
 * - 返回键 / 「返回」按钮：仅退出游戏窗口，后台传输继续，主界面进度照常更新；
 * - 「确定」按钮：传输完成前等同「返回」；传输完成后点击则关闭窗口；
 * - 传输完成：不强制关闭（用户可以继续玩），顶部①区显示「已发送」，进度条拉满，
 *   此时点「确定」即可关闭。游戏结束（碰撞）后点击画面可重开一局，不影响传输。
 */
class TransferGame(private val context: Context) {

    private var dialog: Dialog? = null
    private var gameView: PixelRunnerView? = null
    private var tvInfo: TextView? = null
    private var tvScore: TextView? = null
    private var pbTransfer: ProgressBar? = null
    private var overlay: View? = null
    private var tvOverlay: TextView? = null
    private var btnConfirm: Button? = null

    /** 传输是否已完成（完成后「确定」变成真正关闭窗口） */
    private var transferDone = false

    /** 是否处于「休闲模式」（无传输，平时玩的窗口） */
    private var idleMode = false

    fun isShowing(): Boolean = dialog?.isShowing == true

    /** 平时娱乐入口：无传输信息，只玩 */
    fun showIdle() {
        idleMode = true
        show()
    }

    fun show() {
        if (isShowing()) return

        val d = Dialog(context)
        dialog = d
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_game, null)
        d.setContentView(view)

        gameView = view.findViewById(R.id.gameView)
        tvInfo = view.findViewById(R.id.tvGameTransferInfo)
        tvScore = view.findViewById(R.id.tvGameScore)
        pbTransfer = view.findViewById(R.id.pbGameTransfer)
        overlay = view.findViewById(R.id.overlayHint)
        tvOverlay = view.findViewById(R.id.tvOverlayHint)
        val btnBack = view.findViewById<Button>(R.id.btnGameBack)
        btnConfirm = view.findViewById(R.id.btnGameConfirm)

        // 休闲模式：隐藏传输进度条，①区只显示一句提示
        if (idleMode) {
            pbTransfer?.visibility = View.GONE
            tvInfo?.text = context.getString(R.string.game_idle_info)
        }

        gameView?.onScoreChanged = { score ->
            tvScore?.text = context.getString(R.string.game_score, score)
        }
        gameView?.onGameOver = { score ->
            overlay?.visibility = View.VISIBLE
            tvOverlay?.text = context.getString(R.string.game_over, score)
        }

        // 点击画面重开一局（仅游戏结束时）
        gameView?.setOnClickListener {
            if (!gameView!!.running) {
                overlay?.visibility = View.GONE
                gameView?.start()
            }
        }

        btnBack.setOnClickListener { dismiss() }
        btnConfirm?.setOnClickListener { dismiss() }

        // 返回键等同「返回」按钮
        d.setOnCancelListener { /* 什么都不做，由 dismiss 统一处理 */ }

        val window = d.window
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        d.setCanceledOnTouchOutside(false)
        d.show()

        // 开始游戏
        gameView?.start()
    }

    /** 更新顶部①区：文件名 + 进度（休闲模式下不生效） */
    fun onProgress(fileName: String, progress: Float) {
        if (!isShowing() || idleMode) return
        val pct = (progress.coerceIn(0f, 1f) * 100).toInt()
        pbTransfer?.progress = pct
        tvInfo?.text = context.getString(R.string.game_transfer_info, fileName, pct)
    }

    /** 传输完成：进度拉满，显示已发送，标记完成（「确定」可关闭）。休闲模式下不生效 */
    fun onDone(fileName: String, ok: Boolean) {
        if (!isShowing() || idleMode) return
        transferDone = true
        pbTransfer?.progress = 100
        tvInfo?.text = if (ok) {
            context.getString(R.string.game_transfer_done, fileName)
        } else {
            context.getString(R.string.game_transfer_fail, fileName)
        }
        btnConfirm?.isEnabled = true
    }

    /** 关闭窗口（不打断后台传输） */
    fun dismiss() {
        gameView?.pause()
        dialog?.dismiss()
        dialog = null
        gameView = null
    }
}
