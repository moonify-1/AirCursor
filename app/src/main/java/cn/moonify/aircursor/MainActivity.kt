package cn.moonify.aircursor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import cn.moonify.aircursor.bluetooth.AirCursorConnectionManager
import cn.moonify.aircursor.bluetooth.HidMouseManager
import cn.moonify.aircursor.bluetooth.PinyinConverter
import cn.moonify.aircursor.bluetooth.RfcommClientManager
import cn.moonify.aircursor.bluetooth.RfcommConstants
import cn.moonify.aircursor.bluetooth.TabletRfcommServer
import cn.moonify.aircursor.bluetooth.TouchContact
import cn.moonify.aircursor.databinding.ActivityMainBinding
import java.util.concurrent.Executors
import kotlin.experimental.and
import kotlin.experimental.inv
import kotlin.experimental.or

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val PREFS_NAME = "aircursor_prefs"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_TOUCHPAD_SENS = "touchpad_sens"
        const val KEY_TWO_FINGER_SENS = "two_finger_sens"
        const val KEY_WHEEL_SENS = "wheel_sens"
        const val KEY_TOUCHPAD_SCROLL_DIRECTION = "touchpad_scroll_direction"
        const val KEY_WHEEL_DIRECTION = "wheel_direction_natural"
        const val KEY_COMBO_MODE = "combo_mode"
        const val KEY_HID_MODE = "hid_mode"
        const val KEY_RECENTS_SHORTCUT = "recents_shortcut"
        const val KEY_SHOW_MOUSE_BUTTONS = "show_mouse_buttons"
        const val KEY_ANTI_SCREENSHOT = "anti_screenshot"
        const val KEY_DONT_SHOW_BLACK_SCREEN_DIALOG = "dont_show_black_screen_dialog"

        const val THEME_NIGHT = "night"
        const val THEME_LIGHT = "day"
        const val THEME_SYSTEM = "system"

        const val SHORTCUT_RECENTS_OVERVIEW = "recents_overview"
        const val SHORTCUT_ALT_TAB = "alt_tab"
        const val SHORTCUT_WIN_TAB = "win_tab"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val connectionManager = AirCursorConnectionManager.instance
    private val hidMouseManager get() = connectionManager.hidMouseManager
    private val rfcommClientManager get() = connectionManager.rfcommClientManager

    private val pairedDevices = mutableListOf<BluetoothDevice>()
    private lateinit var spinnerAdapter: ArrayAdapter<String>

    @Volatile
    private var currentButtonState: Byte = 0x00

    private var isInputModeActive = false
    private var wasKeyboardVisible = false

    private val connectionListener = object : AirCursorConnectionManager.Listener {
        override fun onStateChanged() {
            runOnUiThread { updateStatusText() }
        }

        override fun onReconnectProgress(attempt: Int, maxAttempts: Int, message: String) {
            runOnUiThread { updateStatusText() }
        }

        override fun onReconnectFailed(device: BluetoothDevice?) {
            runOnUiThread {
                val deviceName = device?.let {
                    try { it.name } catch (_: Exception) { null } ?: it.address
                } ?: "设备"
                Toast.makeText(this@MainActivity, "重连失败：多次尝试未能连接至 $deviceName", Toast.LENGTH_LONG).show()
                updateStatusText()
            }
        }

        override fun onError(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                updateStatusText()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_CONNECT] != false &&
            permissions[Manifest.permission.BLUETOOTH_ADVERTISE] != false &&
            permissions[Manifest.permission.BLUETOOTH_SCAN] != false
        } else {
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] != false
        }

        if (btGranted) {
            onPermissionsGranted()
        } else {
            Toast.makeText(this, "需要蓝牙权限才能连接平板设备", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedTheme = prefs.getString(KEY_THEME_MODE, THEME_NIGHT) ?: THEME_NIGHT
        applyThemeMode(savedTheme, persist = false)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 触控板防截屏保护：开启 FLAG_SECURE 屏蔽三指误触手机截屏
        val antiScreenshot = prefs.getBoolean(KEY_ANTI_SCREENSHOT, true)
        applyAntiScreenshot(antiScreenshot)

        // 优先将多指手势交由应用处理，抑制系统瞬态栏手势冲突
        WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        connectionManager.init(applicationContext)
        connectionManager.addListener(connectionListener)

        // 返回键行为：若当前在设置页，则返回鼠标页；若当前连接中或正在保持连接，则退到后台，防止意外断开
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.layoutSettingsContainer.visibility == View.VISIBLE) {
                    switchTab(true)
                    return
                }
                if (connectionManager.isConnectedOrConnecting()) {
                    moveTaskToBack(true)
                    Toast.makeText(this@MainActivity, "AirCursor 正在后台保持连接", Toast.LENGTH_SHORT).show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        setupUI()
        setupListeners()
        checkAndRequestPermissions()

        // 异步预加载离线拼音字典，确保输入中文时零延迟
        Executors.newSingleThreadExecutor().execute {
            PinyinConverter.init(applicationContext)
        }

        // 启动平板端接收中继服务（支持免切输入法剪贴板自动粘贴）
        TabletRfcommServer.start(applicationContext)

        Toast.makeText(this, "AirCursor v${BuildConfig.VERSION_NAME} (构建: ${BuildConfig.BUILD_TIME})", Toast.LENGTH_SHORT).show()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount >= 3) {
            binding.root.requestDisallowInterceptTouchEvent(true)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun applyThemeMode(themeMode: String, persist: Boolean = true) {
        val targetNightMode = when (themeMode) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_YES // Default is dark / night
        }
        if (persist) {
            prefs.edit().putString(KEY_THEME_MODE, themeMode).apply()
        }
        if (AppCompatDelegate.getDefaultNightMode() != targetNightMode) {
            AppCompatDelegate.setDefaultNightMode(targetNightMode)
        }
    }

    /**
     * 控制触控板界面防截屏保护：
     * 开启 FLAG_SECURE 后，系统级拒绝触控板界面的任何截屏操作，防止三指操作误触发手机系统截屏
     */
    private fun applyAntiScreenshot(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun setupUI() {
        // Tab default: Mouse Tab active
        switchTab(isMouseTab = true)

        // Device Spinner setup
        spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spnDevices.adapter = spinnerAdapter

        // Theme radio group setup
        when (prefs.getString(KEY_THEME_MODE, THEME_NIGHT)) {
            THEME_LIGHT -> binding.rbThemeLight.isChecked = true
            THEME_SYSTEM -> binding.rbThemeSystem.isChecked = true
            else -> binding.rbThemeDark.isChecked = true
        }

        // Sensitivity sliders setup
        val savedTouchpadSens = prefs.getFloat(KEY_TOUCHPAD_SENS, 1.2f)
        binding.touchpadView.sensitivity = savedTouchpadSens
        binding.sbTouchpadSens.progress = (savedTouchpadSens * 10).toInt().coerceIn(5, 30)
        binding.tvTouchpadSensLabel.text = String.format("触控板灵敏度: %.1fx", savedTouchpadSens)

        val savedTwoFingerSens = prefs.getFloat(KEY_TWO_FINGER_SENS, prefs.getFloat(KEY_WHEEL_SENS, 0.2f))
        binding.touchpadView.scrollSensitivity = savedTwoFingerSens
        binding.sbTwoFingerSens.progress = (savedTwoFingerSens * 10).toInt().coerceIn(1, 30)
        binding.tvTwoFingerSensLabel.text = String.format("双指拖动灵敏度: %.1fx", savedTwoFingerSens)

        val savedWheelSens = prefs.getFloat(KEY_WHEEL_SENS, 0.2f)
        binding.wheelBarView.sensitivity = savedWheelSens
        binding.sbWheelSens.progress = (savedWheelSens * 10).toInt().coerceIn(1, 20)
        binding.tvWheelSensLabel.text = String.format("滚轮栏灵敏度: %.1fx", savedWheelSens)

        // Touchpad two-finger scroll direction setup (default: true for natural swipe direction)
        val savedTouchpadNatural = prefs.getBoolean(KEY_TOUCHPAD_SCROLL_DIRECTION, true)
        binding.touchpadView.isNaturalScroll = savedTouchpadNatural
        if (savedTouchpadNatural) {
            binding.rbTouchpadScrollNatural.isChecked = true
        } else {
            binding.rbTouchpadScrollInverted.isChecked = true
        }

        // Wheel bar direction setup (default: true for natural swipe direction)
        val savedWheelNatural = prefs.getBoolean(KEY_WHEEL_DIRECTION, true)
        binding.wheelBarView.isNaturalDirection = savedWheelNatural
        if (savedWheelNatural) {
            binding.rbWheelNatural.isChecked = true
        } else {
            binding.rbWheelInverted.isChecked = true
        }

        // Recents shortcut setup (default: Alt + Tab)
        val savedShortcut = prefs.getString(KEY_RECENTS_SHORTCUT, SHORTCUT_ALT_TAB) ?: SHORTCUT_ALT_TAB
        if (savedShortcut == SHORTCUT_WIN_TAB) {
            binding.rbShortcutWinTab.isChecked = true
        } else {
            binding.rbShortcutAltTab.isChecked = true
        }

        // Initialize Touchpad mode
        val isNative = (hidMouseManager.hidMode == HidMouseManager.MODE_TOUCHPAD)
        binding.touchpadView.isNativeTouchpadMode = isNative

        // Anti-Screenshot protection setup (default: true)
        val antiScreenshot = prefs.getBoolean(KEY_ANTI_SCREENSHOT, true)
        binding.switchAntiScreenshot.isChecked = antiScreenshot

        // Show version and build time
        binding.tvAboutVersion.text = "AirCursor v${BuildConfig.VERSION_NAME} (构建时间: ${BuildConfig.BUILD_TIME})"

        updateConnectionProperties()
    }

    /**
     * 控制输入模式：点击输入框时隐藏顶部的“鼠标触控”导航栏和“目标平板”连接卡片，
     * 取消输入后重新显示，为触控板和输入法腾出最大垂直高度。
     */
    private fun setInputModeActive(active: Boolean) {
        if (isInputModeActive == active) return
        isInputModeActive = active
        if (active) {
            binding.layoutTabContainer.visibility = View.GONE
            binding.cardConnectionProps.visibility = View.GONE
        } else {
            binding.layoutTabContainer.visibility = View.VISIBLE
            binding.cardConnectionProps.visibility = View.VISIBLE
        }
    }

    private fun showSoftKeyboard() {
        binding.etInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun dismissInput() {
        wasKeyboardVisible = false
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etInput.windowToken, 0)
        binding.etInput.clearFocus()
        binding.etInput.text?.clear()
        setInputModeActive(false)
    }

    private fun switchTab(isMouseTab: Boolean) {
        if (isMouseTab) {
            binding.layoutMouseContainer.visibility = View.VISIBLE
            binding.layoutSettingsContainer.visibility = View.GONE

            binding.btnTabMouse.setBackgroundResource(R.drawable.bg_tab_active)
            binding.btnTabMouse.setTextColor(ContextCompat.getColor(this, R.color.primary))
            binding.btnTabMouse.setTypeface(null, Typeface.BOLD)

            binding.btnTabSettings.setBackgroundColor(Color.TRANSPARENT)
            binding.btnTabSettings.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnTabSettings.setTypeface(null, Typeface.NORMAL)

            if (isInputModeActive) {
                binding.layoutTabContainer.visibility = View.GONE
                binding.cardConnectionProps.visibility = View.GONE
            } else {
                binding.layoutTabContainer.visibility = View.VISIBLE
                binding.cardConnectionProps.visibility = View.VISIBLE
            }
        } else {
            dismissInput()
            binding.layoutTabContainer.visibility = View.VISIBLE
            binding.layoutMouseContainer.visibility = View.GONE
            binding.layoutSettingsContainer.visibility = View.VISIBLE

            binding.btnTabSettings.setBackgroundResource(R.drawable.bg_tab_active)
            binding.btnTabSettings.setTextColor(ContextCompat.getColor(this, R.color.primary))
            binding.btnTabSettings.setTypeface(null, Typeface.BOLD)

            binding.btnTabMouse.setBackgroundColor(Color.TRANSPARENT)
            binding.btnTabMouse.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnTabMouse.setTypeface(null, Typeface.NORMAL)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        // Tab switcher clicks
        binding.btnTabMouse.setOnClickListener { switchTab(true) }
        binding.btnTabSettings.setOnClickListener { switchTab(false) }

        // Top Status Card click on Mouse screen navigates to Settings
        binding.cardConnectionProps.setOnClickListener {
            switchTab(false)
        }

        // Black Screen Touchpad Mode triggers
        binding.btnBlackScreen.setOnClickListener {
            launchBlackScreenMode()
        }
        binding.btnSettingsBlackScreen.setOnClickListener {
            launchBlackScreenMode()
        }

        // Theme Mode RadioGroup listener
        binding.rgThemeMode.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.rbThemeLight -> THEME_LIGHT
                R.id.rbThemeSystem -> THEME_SYSTEM
                else -> THEME_NIGHT
            }
            applyThemeMode(newMode, persist = true)
        }

        // Sensitivity sliders listeners
        binding.sbTouchpadSens.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sens = (progress.coerceAtLeast(5)) / 10f
                binding.touchpadView.sensitivity = sens
                binding.tvTouchpadSensLabel.text = String.format("触控板灵敏度: %.1fx", sens)
                if (fromUser) {
                    prefs.edit().putFloat(KEY_TOUCHPAD_SENS, sens).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.sbTwoFingerSens.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sens = (progress.coerceAtLeast(1)) / 10f
                binding.touchpadView.scrollSensitivity = sens
                binding.tvTwoFingerSensLabel.text = String.format("双指拖动灵敏度: %.1fx", sens)
                if (fromUser) {
                    prefs.edit().putFloat(KEY_TWO_FINGER_SENS, sens).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.sbWheelSens.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sens = (progress.coerceAtLeast(1)) / 10f
                binding.wheelBarView.sensitivity = sens
                binding.tvWheelSensLabel.text = String.format("滚轮栏灵敏度: %.1fx", sens)
                if (fromUser) {
                    prefs.edit().putFloat(KEY_WHEEL_SENS, sens).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Refresh paired devices list
        binding.btnRefresh.setOnClickListener {
            refreshPairedDevices()
        }

        // Connect button (launches both HID mouse and RFCOMM SPP socket)
        binding.btnConnect.setOnClickListener {
            connectToSelectedDevice()
        }

        // Disconnect button
        binding.btnDisconnect.setOnClickListener {
            disconnectAll()
        }

        // Tablet IME guide dialog
        binding.btnTabletSettings.setOnClickListener {
            showTabletGuideDialog()
        }

        binding.touchpadView.isNaturalScroll = prefs.getBoolean(KEY_TOUCHPAD_SCROLL_DIRECTION, true)
        
        // Left / Right Mouse Buttons Visibility setting
        val showButtons = prefs.getBoolean(KEY_SHOW_MOUSE_BUTTONS, true)
        binding.switchMouseButtons.isChecked = showButtons
        binding.layoutMouseButtons.visibility = if (showButtons) View.VISIBLE else View.GONE
        binding.switchMouseButtons.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SHOW_MOUSE_BUTTONS, isChecked).apply()
            binding.layoutMouseButtons.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Touchpad movements, tap, scroll, and multi-finger gestures
        binding.touchpadView.callback = object : cn.moonify.aircursor.ui.TouchpadView.Callback {
            override fun onMove(dx: Int, dy: Int) {
                if (isInputModeActive) dismissInput()
                hidMouseManager.sendMouseReport(currentButtonState, dx, dy, 0, 0)
            }

            override fun onTap() {
                if (isInputModeActive) dismissInput()
                // 单指轻触：发送左键单击 (Down 后延时 Up)
                val downState = currentButtonState or HidMouseManager.BUTTON_LEFT
                hidMouseManager.sendMouseReport(downState, 0, 0, 0, 0)
                mainHandler.postDelayed({
                    hidMouseManager.sendMouseReport(currentButtonState, 0, 0, 0, 0)
                }, 20)
            }

            override fun onSecondaryTap() {
                if (isInputModeActive) dismissInput()
                // 双指轻触：发送右键单击
                val downState = currentButtonState or HidMouseManager.BUTTON_RIGHT
                hidMouseManager.sendMouseReport(downState, 0, 0, 0, 0)
                mainHandler.postDelayed({
                    hidMouseManager.sendMouseReport(currentButtonState, 0, 0, 0, 0)
                }, 20)
            }

            override fun onDragStart() {
                if (isInputModeActive) dismissInput()
                currentButtonState = currentButtonState or HidMouseManager.BUTTON_LEFT
                hidMouseManager.sendMouseReport(currentButtonState, 0, 0, 0, 0)
            }

            override fun onDragMove(dx: Int, dy: Int) {
                hidMouseManager.sendMouseReport(currentButtonState, dx, dy, 0, 0)
            }

            override fun onDragEnd() {
                currentButtonState = currentButtonState and HidMouseManager.BUTTON_LEFT.inv()
                hidMouseManager.sendMouseReport(currentButtonState, 0, 0, 0, 0)
            }

            override fun onScroll(wheel: Int, pan: Int) {
                if (isInputModeActive) dismissInput()
                hidMouseManager.sendMouseReport(currentButtonState, 0, 0, wheel, pan)
            }

            override fun onThreeFingerSwipeUp() {
                if (isInputModeActive) dismissInput()
                handleThreeFingerOverview("三指上滑")
            }

            override fun onThreeFingerSwipeDown() {
                if (isInputModeActive) dismissInput()
                hidMouseManager.sendConsumerKey(HidMouseManager.CONSUMER_HOME)
            }

            override fun onThreeFingerHorizontalSwipe(direction: Int) {
                if (isInputModeActive) dismissInput()
                handleDirectAppSwitch(direction)
            }

            override fun onThreeFingerTap() {
                if (isInputModeActive) dismissInput()
                handleThreeFingerOverview("三指轻点")
            }
        }

        // 48dp Scroll wheel
        binding.wheelBarView.callback = object : cn.moonify.aircursor.ui.WheelBarView.Callback {
            override fun onWheel(wheel: Int) {
                if (isInputModeActive) dismissInput()
                hidMouseManager.sendMouseReport(currentButtonState, 0, 0, wheel, 0)
            }
        }

        // Physical Left Button
        binding.btnLeftClick.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (isInputModeActive) dismissInput()
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    currentButtonState = currentButtonState or HidMouseManager.BUTTON_LEFT
                    hidMouseManager.sendMouseReport(currentButtonState, 0, 0, 0, 0)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    currentButtonState = currentButtonState and HidMouseManager.BUTTON_LEFT.inv()
                    hidMouseManager.sendMouseReport(currentButtonState, 0, 0, 0, 0)
                    true
                }
                else -> false
            }
        }

        // Physical Right Button
        binding.btnRightClick.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (isInputModeActive) dismissInput()
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    currentButtonState = currentButtonState or HidMouseManager.BUTTON_RIGHT
                    hidMouseManager.sendMouseReport(currentButtonState, 0, 0, 0, 0)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    currentButtonState = currentButtonState and HidMouseManager.BUTTON_RIGHT.inv()
                    hidMouseManager.sendMouseReport(currentButtonState, 0, 0, 0, 0)
                    true
                }
                else -> false
            }
        }

        // HID Working Mode RadioGroup (Touchpad vs Combo vs Pure Mouse)
        when (hidMouseManager.hidMode) {
            HidMouseManager.MODE_TOUCHPAD -> binding.rbModeTouchpad.isChecked = true
            HidMouseManager.MODE_COMBO -> binding.rbModeCombo.isChecked = true
            else -> binding.rbModePureMouse.isChecked = true
        }

        binding.rgHidMode.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.rbModeTouchpad -> HidMouseManager.MODE_TOUCHPAD
                R.id.rbModeCombo -> HidMouseManager.MODE_COMBO
                else -> HidMouseManager.MODE_PURE_MOUSE
            }
            if (hidMouseManager.hidMode != newMode) {
                hidMouseManager.switchMode(newMode)
                binding.touchpadView.isNativeTouchpadMode = (newMode == HidMouseManager.MODE_TOUCHPAD)
                updateStatusText()

                val modeName = when (newMode) {
                    HidMouseManager.MODE_TOUCHPAD -> "【智能高精度触控板】(推荐)"
                    HidMouseManager.MODE_COMBO -> "【外接键盘+鼠标】复合模式"
                    else -> "【传统纯鼠标】标准模式"
                }
                AlertDialog.Builder(this)
                    .setTitle("工作模式切换提示")
                    .setMessage("已切换为 $modeName。\n\n⚠️ 重要提示：\n由于平板系统会强缓存蓝牙 HID 描述符，更换模式后强烈建议进入平板【蓝牙设置】，将手机【取消配对 / 忽略设备】后再重新配对连接！\n否则可能因平板使用旧模式描述符导致光标错位或无法识别触控手势。")
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }

        // Touchpad Two-Finger Scroll Direction (Natural vs Inverted)
        binding.rgTouchpadScrollDirection.setOnCheckedChangeListener { _, checkedId ->
            val natural = (checkedId == R.id.rbTouchpadScrollNatural)
            binding.touchpadView.isNaturalScroll = natural
            prefs.edit().putBoolean(KEY_TOUCHPAD_SCROLL_DIRECTION, natural).apply()
        }

        // Wheel Bar Direction (Natural vs Inverted)
        binding.rgWheelDirection.setOnCheckedChangeListener { _, checkedId ->
            val natural = (checkedId == R.id.rbWheelNatural)
            binding.wheelBarView.isNaturalDirection = natural
            prefs.edit().putBoolean(KEY_WHEEL_DIRECTION, natural).apply()
        }

        // Open Phone System Split-Screen Settings button
        binding.btnOpenSplitSettings.setOnClickListener {
            openSplitScreenSettings()
        }

        // Anti-Screenshot protection Switch
        binding.switchAntiScreenshot.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_ANTI_SCREENSHOT, isChecked).apply()
            applyAntiScreenshot(isChecked)
            val tip = if (isChecked) "已开启触控板防截屏保护" else "已关闭触控板防截屏保护"
            Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
        }

        // Open Phone System Screenshot Settings button
        binding.btnOpenScreenshotSettings.setOnClickListener {
            openScreenshotSettings()
        }

        // Recents shortcut mode RadioGroup listener (Overview vs Alt+Tab vs Win+Tab)
        val savedShortcut = prefs.getString(KEY_RECENTS_SHORTCUT, SHORTCUT_RECENTS_OVERVIEW)
        when (savedShortcut) {
            SHORTCUT_ALT_TAB -> binding.rbShortcutAltTab.isChecked = true
            SHORTCUT_WIN_TAB -> binding.rbShortcutWinTab.isChecked = true
            else -> binding.rbShortcutOverview.isChecked = true
        }

        binding.rgRecentsShortcut.setOnCheckedChangeListener { _, checkedId ->
            val shortcut = when (checkedId) {
                R.id.rbShortcutAltTab -> SHORTCUT_ALT_TAB
                R.id.rbShortcutWinTab -> SHORTCUT_WIN_TAB
                else -> SHORTCUT_RECENTS_OVERVIEW
            }
            prefs.edit().putString(KEY_RECENTS_SHORTCUT, shortcut).apply()
        }

        // 软键盘退格与回车拦截监听（解决手机软键盘在输入框为空时点击删除或回车无法转发至平板的问题）
        // 软键盘输入与编辑拦截监听（捕获候选词/推荐词上屏、空输入框退格与回车转发至平板）
        binding.etInput.keyEventListener = object : cn.moonify.aircursor.ui.RemoteEditText.KeyEventListener {
            override fun onTextCommitted(text: String) {
                sendTextToTablet(text)
            }

            override fun onEmptyBackspace() {
                binding.etInput.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (hidMouseManager.connectionState == BluetoothProfile.STATE_CONNECTED) {
                    hidMouseManager.sendKeyboardKey(HidMouseManager.KEY_BACKSPACE)
                } else {
                    rfcommClientManager.sendCommand(RfcommConstants.CMD_BACKSPACE)
                }
            }

            override fun onEnterPressed() {
                binding.etInput.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (hidMouseManager.connectionState == BluetoothProfile.STATE_CONNECTED) {
                    hidMouseManager.sendKeyboardKey(HidMouseManager.KEY_ENTER)
                } else {
                    rfcommClientManager.sendCommand(RfcommConstants.CMD_ENTER)
                }
                binding.etInput.text?.clear()
            }

            override fun onInputCancelled() {
                dismissInput()
            }
        }

        // 输入栏焦点与点击监听：点击输入栏时隐藏上方目标平板和顶部鼠标触控导航栏，取消输入后再恢复
        binding.etInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (!isInputModeActive) {
                    binding.etInput.post { setInputModeActive(true) }
                }
                showSoftKeyboard()
            }
        }
        binding.etInput.setOnClickListener {
            if (!isInputModeActive) {
                binding.etInput.post { setInputModeActive(true) }
            }
            showSoftKeyboard()
        }

        // 监听软键盘折叠/收起：当用户关闭软键盘时（且软键盘曾经处于展开状态），自动退出输入状态并恢复上方目标平板和鼠标触控栏
        val rootView = binding.root
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            rootView.getWindowVisibleDisplayFrame(r)
            val screenHeight = rootView.rootView.height
            if (screenHeight <= 0) return@addOnGlobalLayoutListener
            val keypadHeight = screenHeight - r.bottom
            val isKeyboardOpen = keypadHeight > screenHeight * 0.15

            if (isKeyboardOpen) {
                wasKeyboardVisible = true
            } else if (wasKeyboardVisible && isInputModeActive) {
                // 仅当软键盘曾经弹起且现在已关闭时，才触发退出输入模式
                dismissInput()
            }
        }

        // 软键盘回车/完成监听：当手机软键盘点击“完成/发送”或 Enter 键时，确保转发物理回车
        binding.etInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_NEXT ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                binding.etInput.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (hidMouseManager.connectionState == BluetoothProfile.STATE_CONNECTED) {
                    hidMouseManager.sendKeyboardKey(HidMouseManager.KEY_ENTER)
                } else {
                    rfcommClientManager.sendCommand(RfcommConstants.CMD_ENTER)
                }
                binding.etInput.text?.clear()
                true
            } else {
                false
            }
        }

    }

    /**
     * 智能多通道发送文本至平板：
     * 1. 串口已连接时（免切输入法直通 / 方案B）：
     *    手机通过 RFCOMM 将文本送达平板系统剪贴板，平板回复 ACK_PASTE 后手机立即通过 HID 发送 Ctrl+V 触发精准粘贴！
     *    （若平板开启了 AirCursor 输入法，则由 IME 直接 commitText 落字，零延迟 100% 绝对精准）；
     * 2. 串口未连接时（走 HID 直连 / 方案C）：自动将中文转译为拼音击键并映射中英文标点，模拟外接物理键盘选词上屏；
     * 3. 纯鼠标模式且串口未连接时：友好弹出 Toast 提示开启复合模式。
     */
    private fun sendTextToTablet(text: String) {
        val rfConnected = rfcommClientManager.connectionState == RfcommClientManager.STATE_CONNECTED
        val hidConnected = hidMouseManager.connectionState == BluetoothProfile.STATE_CONNECTED

        if (rfConnected) {
            rfcommClientManager.sendText(text) { ack ->
                if (ack == RfcommConstants.ACK_PASTE) {
                    // 平板端已将原始文本安全写入系统剪贴板，手机立即发送物理 Ctrl+V 触发精准粘贴！
                    hidMouseManager.sendPasteShortcut()
                }
                // 若 ack == ACK_COMMITTED，说明平板使用 AirCursor 输入法并已由 IME commitText 直接上屏，无需发送 Ctrl+V
            }
        } else if (hidConnected && hidMouseManager.isComboMode) {
            hidMouseManager.sendChineseTextAsPinyin(text)
        } else if (hidConnected && !hidMouseManager.isComboMode) {
            Toast.makeText(
                this,
                "当前为纯鼠标模式。输入中文请在设置中开启【外接键盘复合模式】或在平板开启 AirCursor",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "未连接平板设备，无法发送文本", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (connectionManager.isInitialized) {
            updateStatusText()
            refreshPairedDevices()
            if (!connectionManager.isConnectedOrConnecting()) {
                connectionManager.autoConnectLastDevice(this)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val neededPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        // Android 13+ 通知权限，用于前台连接保活通知
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (neededPermissions.isNotEmpty()) {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        } else {
            onPermissionsGranted()
        }
    }

    private fun onPermissionsGranted() {
        connectionManager.init(applicationContext)
        refreshPairedDevices()
        // 每次打开时，尝试自动连接上次已连接成功的设备
        connectionManager.autoConnectLastDevice(this)
    }

    @SuppressLint("MissingPermission")
    private fun refreshPairedDevices() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "请先在手机设置中开启蓝牙", Toast.LENGTH_SHORT).show()
            return
        }

        pairedDevices.clear()
        spinnerAdapter.clear()

        val bonded = bluetoothAdapter.bondedDevices
        val lastAddr = connectionManager.getLastConnectedAddress()
        var selectedPos = -1

        if (bonded.isNullOrEmpty()) {
            spinnerAdapter.add("未找到已配对设备 (请先在系统设置中配对)")
        } else {
            for ((index, device) in bonded.withIndex()) {
                pairedDevices.add(device)
                val deviceName = device.name ?: "未知设备"
                spinnerAdapter.add("$deviceName (${device.address})")
                if (lastAddr != null && device.address.equals(lastAddr, ignoreCase = true)) {
                    selectedPos = index
                }
            }
        }
        spinnerAdapter.notifyDataSetChanged()

        if (selectedPos != -1) {
            binding.spnDevices.setSelection(selectedPos)
        }
    }

    private fun connectToSelectedDevice() {
        val selectedIndex = binding.spnDevices.selectedItemPosition
        if (selectedIndex < 0 || selectedIndex >= pairedDevices.size) {
            Toast.makeText(this, "请选择一个已配对的平板设备", Toast.LENGTH_SHORT).show()
            return
        }

        val device = pairedDevices[selectedIndex]
        Toast.makeText(this, "正在连接平板: ${device.name ?: device.address}...", Toast.LENGTH_SHORT).show()

        connectionManager.connect(device)
        updateStatusText()
    }

    private fun disconnectAll() {
        connectionManager.disconnectAll(manual = true)
        updateStatusText()
        Toast.makeText(this, "已断开连接", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatusText() {
        if (!connectionManager.isInitialized) {
            binding.tvStatus.text = "HID纯鼠标: 未就绪  |  串口通道: 未连接"
            binding.tvStatus.setTextColor(Color.parseColor("#EF4444"))
            updateConnectionProperties()
            return
        }

        if (connectionManager.isReconnecting) {
            val attempt = connectionManager.reconnectAttempt
            val max = AirCursorConnectionManager.MAX_RECONNECT_ATTEMPTS
            binding.tvStatus.text = "正在重新连接 ($attempt/$max)..."
            binding.tvStatus.setTextColor(Color.parseColor("#F59E0B")) // Amber
            updateConnectionProperties()
            return
        }

        if (connectionManager.isConfirmedUnreachable) {
            binding.tvStatus.text = "已断开 (已重试${AirCursorConnectionManager.MAX_RECONNECT_ATTEMPTS}次，确认无法连接)"
            binding.tvStatus.setTextColor(Color.parseColor("#EF4444")) // Red
            updateConnectionProperties()
            return
        }

        val modeTag = when (hidMouseManager.hidMode) {
            HidMouseManager.MODE_TOUCHPAD -> "HID智能触控板"
            HidMouseManager.MODE_COMBO -> "HID键盘鼠标"
            else -> "HID纯鼠标"
        }
        val hidState = hidMouseManager.connectionState
        val hidStateStr = when (hidState) {
            BluetoothProfile.STATE_CONNECTED -> "$modeTag: 已连接"
            BluetoothProfile.STATE_CONNECTING -> "$modeTag: 连接中…"
            else -> if (hidMouseManager.isAppRegistered) "$modeTag: 就绪" else "$modeTag: 未就绪"
        }

        val rfState = rfcommClientManager.connectionState
        val rfStateStr = when (rfState) {
            RfcommClientManager.STATE_CONNECTED -> "串口通道: 已连接"
            RfcommClientManager.STATE_CONNECTING -> "串口通道: 连接中…"
            else -> if (hidMouseManager.isComboMode) "串口: 未开启(走HID直连)" else "串口通道: 未连接"
        }

        val fullStatus = "$hidStateStr  |  $rfStateStr"
        binding.tvStatus.text = fullStatus

        if (hidState == BluetoothProfile.STATE_CONNECTED) {
            binding.tvStatus.setTextColor(Color.parseColor("#10B981")) // Green
        } else if (hidState == BluetoothProfile.STATE_CONNECTING || rfState == RfcommClientManager.STATE_CONNECTING) {
            binding.tvStatus.setTextColor(Color.parseColor("#F59E0B")) // Amber
        } else {
            binding.tvStatus.setTextColor(Color.parseColor("#EF4444")) // Red
        }

        updateConnectionProperties()
    }

    /**
     * 更新鼠标主界面的紧凑连接属性卡片
     */
    private fun updateConnectionProperties() {
        if (!connectionManager.isInitialized) return

        val activeDevice = connectionManager.getActiveDevice()
        val hidState = hidMouseManager.connectionState
        val isAnyConnected = connectionManager.isConnected()
        val isAnyConnecting = connectionManager.isConnecting()
        val isReconnecting = connectionManager.isReconnecting
        val isFailed = connectionManager.isConfirmedUnreachable

        // 1. Device name and action button
        if (isReconnecting) {
            val name = activeDevice?.let {
                try { it.name } catch (_: Exception) { null } ?: it.address
            } ?: "平板设备"
            binding.tvPropDevice.text = "正在重连: $name"
            binding.btnPropAction.text = getString(R.string.prop_btn_disconnect)
            binding.btnPropAction.setOnClickListener { disconnectAll() }
        } else if (activeDevice != null && isAnyConnected) {
            val name = try { activeDevice.name } catch (_: Exception) { null } ?: "平板设备"
            binding.tvPropDevice.text = name
            binding.btnPropAction.text = getString(R.string.prop_btn_disconnect)
            binding.btnPropAction.setOnClickListener { disconnectAll() }
        } else if (isAnyConnecting) {
            binding.tvPropDevice.text = "正在连接平板..."
            binding.btnPropAction.text = getString(R.string.prop_btn_settings)
            binding.btnPropAction.setOnClickListener { switchTab(false) }
        } else if (isFailed) {
            binding.tvPropDevice.text = "连接断开 (确认无法连接)"
            binding.btnPropAction.text = "去连接"
            binding.btnPropAction.setOnClickListener { switchTab(false) }
        } else {
            binding.tvPropDevice.text = getString(R.string.prop_device_none)
            binding.btnPropAction.text = "去连接"
            binding.btnPropAction.setOnClickListener { switchTab(false) }
        }

        // 2. HID Badge
        when {
            isReconnecting -> {
                updateBadge(binding.tvBadgeHid, "○ HID重连中", R.color.accent_amber, R.color.accent_amber_bg)
            }
            hidState == BluetoothProfile.STATE_CONNECTED -> {
                updateBadge(binding.tvBadgeHid, "● HID已连接", R.color.accent_green, R.color.accent_green_bg)
            }
            hidState == BluetoothProfile.STATE_CONNECTING -> {
                updateBadge(binding.tvBadgeHid, "○ HID连接中", R.color.accent_amber, R.color.accent_amber_bg)
            }
            isFailed -> {
                updateBadge(binding.tvBadgeHid, "✕ HID已断开", R.color.accent_red, R.color.accent_red_bg)
            }
            else -> {
                val text = if (hidMouseManager.isAppRegistered) "○ HID就绪" else "○ HID未就绪"
                updateBadge(binding.tvBadgeHid, text, R.color.badge_gray_text, R.color.badge_gray_bg)
            }
        }

        // 3. Mode Badge
        val rfConnected = rfcommClientManager.connectionState == RfcommClientManager.STATE_CONNECTED
        when {
            rfConnected -> {
                updateBadge(binding.tvBadgeCombo, "⚡ 剪贴板直通 (100%精准)", R.color.accent_green, R.color.accent_green_bg)
            }
            hidMouseManager.hidMode == HidMouseManager.MODE_TOUCHPAD -> {
                updateBadge(binding.tvBadgeCombo, "✨ 智能触控板", R.color.primary, R.color.tab_bg)
            }
            hidMouseManager.hidMode == HidMouseManager.MODE_COMBO -> {
                updateBadge(binding.tvBadgeCombo, "⌨ 拼音模拟打字", R.color.primary, R.color.tab_bg)
            }
            else -> {
                updateBadge(binding.tvBadgeCombo, "🖱 纯鼠标模式", R.color.badge_gray_text, R.color.badge_gray_bg)
            }
        }
    }

    private fun updateBadge(
        badge: TextView,
        text: String,
        textColorRes: Int,
        bgTintRes: Int
    ) {
        badge.text = text
        badge.setTextColor(ContextCompat.getColor(this, textColorRes))
        badge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, bgTintRes))
    }

    private fun showTabletGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("平板使用模式说明")
            .setMessage(
                "本应用支持三种打字工作模式：\n\n" +
                "【模式一：推荐！免切输入法 100% 精准直通 (方案B)】\n" +
                "1. 平板无需切换输入法，继续保留系统默认的【vivo 输入法】；\n" +
                "2. 平板端打开 AirCursor（后台保持运行），手机连接后顶部将亮起【⚡ 剪贴板直通 (100%精准)】徽章；\n" +
                "3. 手机端输入任何中文、生僻字、标点符号、中英混排，均通过蓝牙剪贴板中继 + 自动 Ctrl+V 毫秒级精准落字，零错别字！\n\n" +
                "【模式二：AirCursor 极简直接上屏模式 (方案A)】\n" +
                "1. 在平板【语言与输入法】中将默认输入法切换为【AirCursor】；\n" +
                "2. 点击输入框正常出现闪烁光标，且屏幕无键盘遮挡，手机输入直接通过底层接口落字上屏。\n\n" +
                "【模式三：纯 HID 物理外接键盘模式 (方案C)】\n" +
                "平板未安装 AirCursor 时的直连模式：自动通过拼音转译模拟物理键盘击键并按空格选词上屏。"
            )
            .setPositiveButton(getString(R.string.enable_ime_btn)) { _, _ ->
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                startActivity(intent)
            }
            .setNeutralButton(getString(R.string.select_ime_btn)) { _, _ ->
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showInputMethodPicker()
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    /**
     * 处理三指上滑与三指轻点手势：呼出平板后台多任务概览卡片，并停留在后台界面供用户自主选择应用
     */
    private fun handleThreeFingerOverview(gestureName: String) {
        val hidConnected = hidMouseManager.connectionState == BluetoothProfile.STATE_CONNECTED
        val rfConnected = rfcommClientManager.connectionState == RfcommClientManager.STATE_CONNECTED
        val shortcutMode = prefs.getString(KEY_RECENTS_SHORTCUT, SHORTCUT_RECENTS_OVERVIEW) ?: SHORTCUT_RECENTS_OVERVIEW

        if (rfConnected) {
            rfcommClientManager.sendCommand(RfcommConstants.CMD_RECENTS)
            Toast.makeText(this, "$gestureName：已呼出平板后台多任务界面", Toast.LENGTH_SHORT).show()
        } else if (hidConnected && hidMouseManager.isComboMode) {
            hidMouseManager.sendOverview(shortcutMode)
            val tip = when (shortcutMode) {
                SHORTCUT_ALT_TAB -> "$gestureName：已发送 Alt+Tab 切换应用"
                SHORTCUT_WIN_TAB -> "$gestureName：已发送 Win+Tab 切换任务视图"
                else -> "$gestureName：已呼出平板后台多任务界面并停留"
            }
            Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
        } else if (hidConnected && !hidMouseManager.isComboMode) {
            showEnableComboModeDialog(gestureName)
        } else {
            Toast.makeText(this, "未连接平板设备", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 处理三指横滑手势：直接在应用间快速切换（右滑下一个应用，左滑上一个应用）
     */
    private fun handleDirectAppSwitch(direction: Int) {
        val hidConnected = hidMouseManager.connectionState == BluetoothProfile.STATE_CONNECTED
        val rfConnected = rfcommClientManager.connectionState == RfcommClientManager.STATE_CONNECTED
        val shortcutMode = prefs.getString(KEY_RECENTS_SHORTCUT, SHORTCUT_RECENTS_OVERVIEW) ?: SHORTCUT_RECENTS_OVERVIEW
        val useAltTab = (shortcutMode != SHORTCUT_WIN_TAB)
        val reverse = (direction < 0)
        val desc = if (reverse) "三指左滑" else "三指右滑"

        if (rfConnected) {
            rfcommClientManager.sendCommand(RfcommConstants.CMD_RECENTS)
            Toast.makeText(this, "$desc：已通过串口切换应用", Toast.LENGTH_SHORT).show()
        } else if (hidConnected && hidMouseManager.isComboMode) {
            hidMouseManager.sendRecentApps(useAltTab, reverse)
            val shortcutDesc = if (useAltTab) {
                if (reverse) "Alt + Shift + Tab" else "Alt + Tab"
            } else {
                if (reverse) "Win + Shift + Tab" else "Win + Tab"
            }
            Toast.makeText(this, "$desc：已发送 $shortcutDesc 切换应用", Toast.LENGTH_SHORT).show()
        } else if (hidConnected && !hidMouseManager.isComboMode) {
            showEnableComboModeDialog(desc)
        } else {
            Toast.makeText(this, "未连接平板设备", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEnableComboModeDialog(gestureName: String) {
        AlertDialog.Builder(this)
            .setTitle("需开启外接键盘模式")
            .setMessage(
                "检测到已触发【$gestureName】，控制平板软件界面需要发送实体键盘/多任务快捷指令。\n\n" +
                "当前处于【纯鼠标模式】（仅发送鼠标报文，不包含键盘/多任务通道）。\n\n" +
                "是否立即切换为【外接键盘+鼠标复合模式】？\n\n" +
                "💡 提示：开启复合模式后，如平板鼠标坐标出现漂移，进入平板蓝牙设置将手机【取消配对】再重新连接一次即可恢复正常。"
            )
            .setPositiveButton("立即开启") { _, _ ->
                binding.rbModeCombo.isChecked = true
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 打开手机系统截屏手势设置，方便用户彻底关闭系统全局的三指下滑截屏手势
     */
    private fun openScreenshotSettings() {
        val targetIntents = listOf(
            Intent().setClassName("com.vivo.upslide", "com.vivo.upslide.setting.SCaptureSettingActivity"),
            Intent().setClassName("com.vivo.upslide", "com.vivo.upslide.setting.SuperCaptureSettingActivity"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$ShortcutsAndAccessibilityActivity"),
            Intent("com.vivo.settings.SHORTCUT_ACCESSIBILITY_SETTINGS"),
            Intent("miui.intent.action.GESTURE_SETTINGS"),
            Intent().setClassName("com.miui.securitycenter", "com.miui.gesture.GestureSettingsActivity"),
            Intent().setClassName("com.oplus.gesture", "com.oplus.gesture.GestureSettingsActivity"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in targetIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        Toast.makeText(this, "请前往手机【系统设置 -> 快捷与辅助 -> 超级截屏】中关闭三指下滑截屏", Toast.LENGTH_LONG).show()
    }

    /**
     * 打开手机系统分屏设置，方便用户彻底关闭系统全局的三指分屏手势
     */
    private fun openSplitScreenSettings() {
        val targetIntents = listOf(
            Intent().setClassName("com.vivo.upslide", "com.vivo.upslide.setting.SplitScreenSettingActivity"),
            Intent("com.vivo.settings.SPLIT_SCREEN_SETTINGS"),
            Intent("android.settings.SPLIT_SCREEN_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in targetIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        Toast.makeText(this, "请前往手机【系统设置 -> 快捷与辅助 -> 分屏多任务】中关闭三指分屏", Toast.LENGTH_LONG).show()
    }

    private fun launchBlackScreenMode() {
        val dontShow = prefs.getBoolean(KEY_DONT_SHOW_BLACK_SCREEN_DIALOG, false)
        if (dontShow) {
            startActivity(Intent(this, BlackScreenTouchpadActivity::class.java))
            return
        }

        AlertDialog.Builder(this)
            .setTitle("进入全屏黑屏触控板模式")
            .setMessage(
                "【黑屏触控特性】\n" +
                "• 界面将转为 100% 纯黑无光状态，并将屏幕调至最低微光极限，OLED 屏幕像素完全熄灭，极度省电且暗光不刺眼；\n" +
                "• 整块手机屏幕均为触控板无边界感应区，手指滑动毫无局限；\n" +
                "• 单指移动、轻触左键、双指右键/滚轮平移、三指多任务等全部功能正常使用。\n\n" +
                "【如何退出黑屏模式】\n" +
                "• 按手机返回键 / 侧滑返回；\n" +
                "• 按手机侧面任意音量键；\n" +
                "• 或在屏幕上四指同时轻点一次即可退出。"
            )
            .setPositiveButton("立即进入") { _, _ ->
                startActivity(Intent(this, BlackScreenTouchpadActivity::class.java))
            }
            .setNeutralButton("不再提示并进入") { _, _ ->
                prefs.edit().putBoolean(KEY_DONT_SHOW_BLACK_SCREEN_DIALOG, true).apply()
                startActivity(Intent(this, BlackScreenTouchpadActivity::class.java))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionManager.removeListener(connectionListener)
        // 注意：不要在此处调用 connectionManager.release()，以便退至后台后继续保持蓝牙连接
    }
}