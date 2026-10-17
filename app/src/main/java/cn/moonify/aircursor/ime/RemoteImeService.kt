package cn.moonify.aircursor.ime

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import cn.moonify.aircursor.bluetooth.TabletRfcommServer

/**
 * Tablet Receiver: Zero-height, invisible InputMethodService.
 * Listens for RFCOMM SPP connections from the phone and commits incoming UTF-8 text/commands
 * directly into the focused input field without showing any on-screen keyboard.
 *
 * 关键特性：
 * 1. onEvaluateInputViewShown() 必须返回 true，确保系统 TextView / EditText 正常激活输入生命周期并显示闪烁插入光标与游标指示器。
 * 2. onCreateInputView() 返回高度为 1px 的透明占位视图，配合 onConfigureWindow 确保零像素遮挡屏幕。
 * 3. 统一交由 TabletRfcommServer 管理蓝牙连接与剪贴板/IME 分发。
 */
class RemoteImeService : InputMethodService() {

    companion object {
        private const val TAG = "RemoteImeService"

        @Volatile
        var activeInstance: RemoteImeService? = null
            private set
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onEvaluateInputViewShown(): Boolean = true

    override fun onCreateInputView(): View {
        return FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
            )
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.VISIBLE
        }
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        win.setGravity(Gravity.BOTTOM)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d(TAG, "onStartInput: inputType=${attribute?.inputType}, restarting=$restarting")
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView: input session started, cursor active")
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        Log.d(TAG, "RemoteImeService onCreate, ensuring TabletRfcommServer is started")
        TabletRfcommServer.start(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeInstance == this) {
            activeInstance = null
        }
        Log.d(TAG, "RemoteImeService onDestroy")
    }
}
