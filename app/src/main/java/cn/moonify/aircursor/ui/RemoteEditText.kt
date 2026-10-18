package cn.moonify.aircursor.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

/**
 * 专为 AirCursor 远程输入定制的 EditText：
 * 突破 Android 原生 EditText 在内容为空时丢弃软键盘删除键与回车键的限制。
 * 1. 深度拦截 commitText，捕获输入法候选词与联想推荐词，直接上屏；
 * 2. 避免强制清空破坏手机软键盘内部状态，保留上下文保证联想推荐与快速输入不丢字；
 * 3. 拦截软键盘退格键（deleteSurroundingText / KEYCODE_DEL），向平板转发物理退格键；
 * 4. 拦截软键盘回车键（performEditorAction / KEYCODE_ENTER），向平板转发物理回车键；
 * 5. 完美兼容各大第三方输入法（微信键盘、搜狗、百度、vivo、小米、Gboard 等）。
 */
class RemoteEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    interface KeyEventListener {
        fun onTextCommitted(text: String)
        fun onEmptyBackspace()
        fun onEnterPressed()
        fun onInputCancelled() {}
    }

    var keyEventListener: KeyEventListener? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            clearFocus()
            keyEventListener?.onInputCancelled()
        }
        return super.onKeyPreIme(keyCode, event)
    }

    private fun isComposing(): Boolean {
        val ed = text ?: return false
        return BaseInputConnection.getComposingSpanStart(ed) != -1
    }

    private fun checkAndPruneBuffer() {
        val s = text ?: return
        if (s.length > 80 && !isComposing()) {
            val toRemove = s.length - 40
            s.delete(0, toRemove)
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val baseConnection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(baseConnection, true) {

            private var activeComposingText: String? = null

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                activeComposingText = text?.toString()
                return super.setComposingText(text, newCursorPosition)
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                activeComposingText = null
                if (!text.isNullOrEmpty()) {
                    val committed = text.toString()
                    keyEventListener?.onTextCommitted(committed)
                }
                val res = super.commitText(text, newCursorPosition)
                post { checkAndPruneBuffer() }
                return res
            }

            override fun finishComposingText(): Boolean {
                val raw = activeComposingText
                activeComposingText = null
                if (!raw.isNullOrEmpty()) {
                    keyEventListener?.onTextCommitted(raw)
                }
                val res = super.finishComposingText()
                post { checkAndPruneBuffer() }
                return res
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) {
                    if (!isComposing()) {
                        keyEventListener?.onEmptyBackspace()
                    }
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) {
                    if (!isComposing()) {
                        keyEventListener?.onEmptyBackspace()
                    }
                }
                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (!isComposing()) {
                            keyEventListener?.onEmptyBackspace()
                        }
                    }
                    return super.sendKeyEvent(event)
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
