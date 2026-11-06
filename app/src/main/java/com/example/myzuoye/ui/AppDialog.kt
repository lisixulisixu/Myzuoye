package com.example.myzuoye.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.example.myzuoye.R

/**
 * 统一风格的对话框入口。
 *
 * 直接用 [AlertDialog.Builder] 会走系统/Material 默认的浅色表面，
 * 在我们这套深色夜空界面里弹出来是一块刺眼的白板。
 * 这里用自定义 overlay 包一层 context，再把窗口背景换成深色圆角底板，
 * 双保险：即使某个 Material 版本不认 `colorSurface`，背景也不会变白。
 */
object AppDialog {

    fun builder(context: Context): AlertDialog.Builder {
        val themed = ContextThemeWrapper(context, R.style.ThemeOverlay_Myzuoye_Dialog)
        return AlertDialog.Builder(themed)
    }

    /** 建好之后再套一层深色底板，返回可直接 show() 的对话框 */
    fun create(context: Context): AlertDialog {
        val dialog = builder(context).create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
        return dialog
    }
}
