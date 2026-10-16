package cn.moonify.aircursor.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

/**
 * 专为 AirCursor 远程输入定制的 EditText：
 * 突破 Android 原生 EditText 在内容为空时丢弃软键盘删除键与回车键的限制。
 * 1. 当输入框为空时拦截软键盘退格键（deleteSurroundingText / KEYCODE_DEL），向平板转发物理退格键；
 * 2. 拦截软键盘回车键（performEditorAction / KEYCODE_ENTER），向平板转发物理回车键或即时提交文本；
 * 3. 完美兼容各大第三方输入法（微信键盘、搜狗、百度、vivo、小米、Gboard 等）。
 */
class RemoteEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    interface KeyEventListener {
        fun onEmptyBackspace()
        fun onEnterPressed()
    }

    var keyEventListener: KeyEventListener? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val baseConnection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(baseConnection, true) {

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && (text == null || text!!.isEmpty())) {
                    keyEventListener?.onEmptyBackspace()
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && (text == null || text!!.isEmpty())) {
                    keyEventListener?.onEmptyBackspace()
                    return true
                }
                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                    if (text == null || text!!.isEmpty()) {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            keyEventListener?.onEmptyBackspace()
                        }
                        return true
                    }
                } else if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        keyEventListener?.onEnterPressed()
                    }
                    return true
                }
                return super.sendKeyEvent(event)
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                keyEventListener?.onEnterPressed()
                return true
            }
        }
    }
}
