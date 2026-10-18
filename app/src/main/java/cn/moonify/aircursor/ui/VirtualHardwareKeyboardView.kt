package cn.moonify.aircursor.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import cn.moonify.aircursor.R
import cn.moonify.aircursor.bluetooth.HidMouseManager

/**
 * 全屏仿真实体蓝牙键盘视图 (Virtual Hardware Keyboard View)
 *
 * 核心设计：
 * 1. 模拟真正的物理外接键盘，包含标准 1~0、+、-、=、退格键 (Backspace)、Esc、Tab、Enter、空格及符号；
 * 2. 点击按键时，不经手机系统软键盘，直接向平板发送原始 USB HID 键盘按键报文；
 * 3. 由平板本地系统输入法（如 vivo/百度/搜狗/Gboard）接收击键并完成拼音汇编、候选词显示与上屏；
 * 4. 具备触感震动反馈与 Shift / CapsLock 状态动态键帽字符同步。
 */
class VirtualHardwareKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    data class KeyDef(
        val keycode: Byte,
        val normalText: String,
        val shiftedText: String = normalText,
        val weight: Float = 1.0f,
        val isModifier: Boolean = false,
        val isAccent: Boolean = false
    )

    var onKeySendListener: ((keycode: Byte, modifier: Byte) -> Unit)? = null

    private var isShiftActive = false
    private var isCapsLock = false
    private var isCtrlActive = false
    private var isAltActive = false

    private val keyButtons = mutableListOf<Pair<KeyDef, TextView>>()

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val padH = dpToPx(6)
        val padV = dpToPx(4)
        setPadding(padH, padV, padH, padV)
        buildKeyboardLayout()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun buildKeyboardLayout() {
        removeAllViews()
        keyButtons.clear()

        // Row 0: Esc, 1..0, -, =, Backspace
        val row0 = listOf(
            KeyDef(HidMouseManager.KEY_ESCAPE, "Esc", weight = 1.0f, isAccent = true),
            KeyDef(0x1E, "1", "!"),
            KeyDef(0x1F, "2", "@"),
            KeyDef(0x20, "3", "#"),
            KeyDef(0x21, "4", "$"),
            KeyDef(0x22, "5", "%"),
            KeyDef(0x23, "6", "^"),
            KeyDef(0x24, "7", "&"),
            KeyDef(0x25, "8", "*"),
            KeyDef(0x26, "9", "("),
            KeyDef(0x27, "0", ")"),
            KeyDef(0x2D, "-", "_"),
            KeyDef(0x2E, "=", "+"),
            KeyDef(HidMouseManager.KEY_BACKSPACE, "⌫", weight = 1.5f, isAccent = true)
        )

        // Row 1: Tab, QWERTYUIOP, [, ], \
        val row1 = listOf(
            KeyDef(HidMouseManager.KEY_TAB, "Tab", weight = 1.2f, isAccent = true),
            KeyDef(0x14, "q", "Q"),
            KeyDef(0x1A, "w", "W"),
            KeyDef(0x08, "e", "E"),
            KeyDef(0x15, "r", "R"),
            KeyDef(0x17, "t", "T"),
            KeyDef(0x1C, "y", "Y"),
            KeyDef(0x18, "u", "U"),
            KeyDef(0x0C, "i", "I"),
            KeyDef(0x12, "o", "O"),
            KeyDef(0x13, "p", "P"),
            KeyDef(0x2F, "[", "{"),
            KeyDef(0x30, "]", "}"),
            KeyDef(0x31, "\\", "|", weight = 1.1f)
        )

        // Row 2: Caps, ASDFGHJKL, ;, ', Enter
        val row2 = listOf(
            KeyDef(0x39, "Caps", weight = 1.4f, isModifier = true, isAccent = true),
            KeyDef(0x04, "a", "A"),
            KeyDef(0x16, "s", "S"),
            KeyDef(0x07, "d", "D"),
            KeyDef(0x09, "f", "F"),
            KeyDef(0x0A, "g", "G"),
            KeyDef(0x0B, "h", "H"),
            KeyDef(0x0D, "j", "J"),
            KeyDef(0x0E, "k", "K"),
            KeyDef(0x0F, "l", "L"),
            KeyDef(0x33, ";", ":"),
            KeyDef(0x34, "'", "\""),
            KeyDef(HidMouseManager.KEY_ENTER, "↵ Enter", weight = 1.8f, isAccent = true)
        )

        // Row 3: Shift, ZXCVBNM, ,, ., /, Shift
        val row3 = listOf(
            KeyDef(0xE1.toByte(), "⇧ Shift", weight = 1.8f, isModifier = true, isAccent = true),
            KeyDef(0x1D, "z", "Z"),
            KeyDef(0x1B, "x", "X"),
            KeyDef(0x06, "c", "C"),
            KeyDef(0x19, "v", "V"),
            KeyDef(0x05, "b", "B"),
            KeyDef(0x11, "n", "N"),
            KeyDef(0x10, "m", "M"),
            KeyDef(0x36, ",", "<"),
            KeyDef(0x37, ".", ">"),
            KeyDef(0x38, "/", "?"),
            KeyDef(0xE5.toByte(), "⇧ Shift", weight = 1.8f, isModifier = true, isAccent = true)
        )

        // Row 4: Ctrl, Alt, Win, Space, ←, ↑, ↓, →
        val row4 = listOf(
            KeyDef(0xE0.toByte(), "Ctrl", weight = 1.2f, isModifier = true, isAccent = true),
            KeyDef(0xE2.toByte(), "Alt", weight = 1.2f, isModifier = true, isAccent = true),
            KeyDef(0xE3.toByte(), "⊞ Win", weight = 1.1f, isAccent = true),
            KeyDef(HidMouseManager.KEY_SPACE, "Space 空格", weight = 5.2f),
            KeyDef(0x50, "←", weight = 1.0f, isAccent = true),
            KeyDef(0x52, "↑", weight = 1.0f, isAccent = true),
            KeyDef(0x51, "↓", weight = 1.0f, isAccent = true),
            KeyDef(0x4F, "→", weight = 1.0f, isAccent = true)
        )

        val rows = listOf(row0, row1, row2, row3, row4)
        for (rowData in rows) {
            val rowLayout = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1.0f).apply {
                    val m = dpToPx(2)
                    setMargins(0, m, 0, m)
                }
            }

            for (key in rowData) {
                val btn = createKeyButton(key)
                rowLayout.addView(btn)
                keyButtons.add(Pair(key, btn))
            }
            addView(rowLayout)
        }
    }

    private fun createKeyButton(key: KeyDef): TextView {
        val tv = TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, key.weight).apply {
                val m = dpToPx(2)
                setMargins(m, 0, m, 0)
            }
            gravity = Gravity.CENTER
            text = key.normalText
            textSize = if (key.normalText.length > 3) 11f else 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(context, R.color.land_key_text))
            setBackgroundResource(if (key.isAccent) R.drawable.bg_hw_key_accent else R.drawable.bg_hw_key)
            isClickable = true
            isFocusable = true

            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                handleKeyClick(key, this)
            }
        }
        return tv
    }

    private fun handleKeyClick(key: KeyDef, btn: TextView) {
        when (key.keycode) {
            0x39.toByte() -> { // CapsLock
                isCapsLock = !isCapsLock
                updateKeyLabels()
                btn.isSelected = isCapsLock
                btn.setBackgroundColor(if (isCapsLock) ContextCompat.getColor(context, R.color.land_active_tab) else ContextCompat.getColor(context, R.color.land_key_accent_bg))
                btn.setTextColor(if (isCapsLock) Color.WHITE else ContextCompat.getColor(context, R.color.land_key_text))
                // 向平板发送物理 CapsLock 键码
                onKeySendListener?.invoke(0x39.toByte(), 0)
                return
            }
            0xE1.toByte(), 0xE5.toByte() -> { // Shift
                isShiftActive = !isShiftActive
                updateKeyLabels()
                return
            }
            0xE0.toByte(), 0xE4.toByte() -> { // Ctrl
                isCtrlActive = !isCtrlActive
                btn.setBackgroundColor(if (isCtrlActive) ContextCompat.getColor(context, R.color.land_active_tab) else ContextCompat.getColor(context, R.color.land_key_accent_bg))
                btn.setTextColor(if (isCtrlActive) Color.WHITE else ContextCompat.getColor(context, R.color.land_key_text))
                return
            }
            0xE2.toByte(), 0xE6.toByte() -> { // Alt
                isAltActive = !isAltActive
                btn.setBackgroundColor(if (isAltActive) ContextCompat.getColor(context, R.color.land_active_tab) else ContextCompat.getColor(context, R.color.land_key_accent_bg))
                btn.setTextColor(if (isAltActive) Color.WHITE else ContextCompat.getColor(context, R.color.land_key_text))
                return
            }
        }

        // 计算当前修饰键掩码
        var modifier: Byte = 0
        if (isShiftActive) modifier = (modifier.toInt() or HidMouseManager.MODIFIER_LEFT_SHIFT.toInt()).toByte()
        if (isCtrlActive) modifier = (modifier.toInt() or HidMouseManager.MODIFIER_LEFT_CTRL.toInt()).toByte()
        if (isAltActive) modifier = (modifier.toInt() or HidMouseManager.MODIFIER_LEFT_ALT.toInt()).toByte()

        // 发送按键报文给平板
        onKeySendListener?.invoke(key.keycode, modifier)

        // 若不是锁定的大写/Shift，点击常规键后自动复位一次性 Shift
        if (isShiftActive) {
            isShiftActive = false
            updateKeyLabels()
        }
    }

    private fun updateKeyLabels() {
        for ((key, btn) in keyButtons) {
            val isLetter = key.normalText.length == 1 && key.normalText[0] in 'a'..'z'
            val isShifted = isShiftActive

            val displayLabel = when {
                isLetter -> {
                    val upper = isCapsLock xor isShifted
                    if (upper) key.shiftedText else key.normalText
                }
                isShifted -> key.shiftedText
                else -> key.normalText
            }
            btn.text = displayLabel

            // Shift 键高亮状态
            if (key.keycode == 0xE1.toByte() || key.keycode == 0xE5.toByte()) {
                btn.setBackgroundColor(if (isShiftActive) ContextCompat.getColor(context, R.color.land_active_tab) else ContextCompat.getColor(context, R.color.land_key_accent_bg))
                btn.setTextColor(if (isShiftActive) Color.WHITE else ContextCompat.getColor(context, R.color.land_key_text))
            }
        }
    }
}
