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
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
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
import cn.moonify.aircursor.bluetooth.AirCursorConnectionManager
import cn.moonify.aircursor.bluetooth.HidMouseManager
import cn.moonify.aircursor.bluetooth.PinyinConverter
import cn.moonify.aircursor.bluetooth.RfcommClientManager
import cn.moonify.aircursor.bluetooth.RfcommConstants
import cn.moonify.aircursor.databinding.ActivityMainBinding
import java.util.concurrent.Executors
import kotlin.experimental.and
import kotlin.experimental.inv
import kotlin.experimental.or

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "aircursor_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_TOUCHPAD_SENS = "touchpad_sens"
        private const val KEY_WHEEL_SENS = "wheel_sens"
        private const val KEY_COMBO_MODE = "combo_mode"

        const val THEME_NIGHT = "night"
        const val THEME_LIGHT = "day"
        const val THEME_SYSTEM = "system"
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

    private var isInternalClearing = false

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

        Toast.makeText(this, "AirCursor v${BuildConfig.VERSION_NAME} (构建: ${BuildConfig.BUILD_TIME})", Toast.LENGTH_SHORT).show()
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

        val savedWheelSens = prefs.getFloat(KEY_WHEEL_SENS, 0.2f)
        binding.wheelBarView.sensitivity = savedWheelSens
        binding.touchpadView.scrollSensitivity = savedWheelSens
        binding.sbWheelSens.progress = (savedWheelSens * 10).toInt().coerceIn(1, 20)
        binding.tvWheelSensLabel.text = String.format("滚轮灵敏度: %.1fx", savedWheelSens)

        // Show version and build time
        binding.tvAboutVersion.text = "AirCursor v${BuildConfig.VERSION_NAME} (构建时间: ${BuildConfig.BUILD_TIME})"

        updateConnectionProperties()
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
        } else {
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

        binding.sbWheelSens.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sens = (progress.coerceAtLeast(1)) / 10f
                binding.wheelBarView.sensitivity = sens
                binding.touchpadView.scrollSensitivity = sens
                binding.tvWheelSensLabel.text = String.format("滚轮灵敏度: %.1fx", sens)
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

        // Touchpad movements, tap and two-finger scroll
        binding.touchpadView.callback = object : cn.moonify.aircursor.ui.TouchpadView.Callback {
            override fun onMove(dx: Int, dy: Int) {
                hidMouseManager.sendMouseReport(currentButtonState, dx, dy, 0)
            }

            override fun onTap() {
                // Send Left button click (Down then Up)
                val downState = currentButtonState or HidMouseManager.BUTTON_LEFT
                hidMouseManager.sendMouseReport(downState, 0, 0, 0)
                mainHandler.postDelayed({
                    hidMouseManager.sendMouseReport(currentButtonState, 0, 0, 0)
                }, 20)
            }

            override fun onWheel(wheel: Int) {
                hidMouseManager.sendMouseReport(currentButtonState, 0, 0, wheel)
            }
        }

        // 48dp Scroll wheel
        binding.wheelBarView.callback = object : cn.moonify.aircursor.ui.WheelBarView.Callback {
            override fun onWheel(wheel: Int) {
                hidMouseManager.sendMouseReport(currentButtonState, 0, 0, wheel)
            }
        }

        // Physical Left Button
        binding.btnLeftClick.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    currentButtonState = currentButtonState or HidMouseManager.BUTTON_LEFT
                    hidMouseManager.sendMouseReport(currentButtonState, 0, 0, 0)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    currentButtonState = currentButtonState and HidMouseManager.BUTTON_LEFT.inv()
                    hidMouseManager.sendMouseReport(currentButtonState, 0, 0, 0)
                    true
                }
                else -> false
            }
        }

        // Physical Right Button
        binding.btnRightClick.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    currentButtonState = currentButtonState or HidMouseManager.BUTTON_RIGHT
                    hidMouseManager.sendMouseReport(currentButtonState, 0, 0, 0)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    currentButtonState = currentButtonState and HidMouseManager.BUTTON_RIGHT.inv()
                    hidMouseManager.sendMouseReport(currentButtonState, 0, 0, 0)
                    true
                }
                else -> false
            }
        }

        // Switch Combo mode (Keyboard + Mouse) vs Pure Mouse
        val savedComboMode = prefs.getBoolean(KEY_COMBO_MODE, false)
        binding.switchComboMode.isChecked = savedComboMode
        if (hidMouseManager.isComboMode != savedComboMode) {
            hidMouseManager.switchMode(savedComboMode)
        }
        binding.switchComboMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_COMBO_MODE, isChecked).apply()
            hidMouseManager.switchMode(isChecked)
            updateStatusText()
            if (isChecked) {
                AlertDialog.Builder(this)
                    .setTitle("复合模式提示")
                    .setMessage("已切换为【外接键盘+鼠标】复合模式。\n\n⚠️ 重要提示：\n由于平板系统会缓存蓝牙 HID 描述符，开启后强烈建议进入平板【蓝牙设置】，将手机【取消配对 / 忽略设备】后再重新配对连接！\n否则可能因平板使用旧描述符导致鼠标坐标轴错位或乱窜。")
                    .setPositiveButton("知道了", null)
                    .show()
            } else {
                Toast.makeText(this, "已切换为【纯鼠标】标准模式（最稳定，推荐）", Toast.LENGTH_SHORT).show()
            }
        }

        // Backspace button (dispatches both HID hardware key and RFCOMM command)
        binding.btnBackspace.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            hidMouseManager.sendKeyboardKey(HidMouseManager.KEY_BACKSPACE)
            rfcommClientManager.sendCommand(RfcommConstants.CMD_BACKSPACE)
        }

        // Space button
        binding.btnSpace.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val currentText = binding.etInput.text?.toString() ?: ""
            if (currentText.isNotEmpty()) {
                sendTextToTablet(currentText)
                isInternalClearing = true
                binding.etInput.text?.clear()
                isInternalClearing = false
            } else {
                hidMouseManager.sendKeyboardKey(HidMouseManager.KEY_SPACE)
                rfcommClientManager.sendCommand(RfcommConstants.CMD_SPACE)
            }
        }

        // Enter button
        binding.btnEnter.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val currentText = binding.etInput.text?.toString() ?: ""
            if (currentText.isNotEmpty()) {
                sendTextToTablet(currentText)
                isInternalClearing = true
                binding.etInput.text?.clear()
                isInternalClearing = false
            } else {
                hidMouseManager.sendKeyboardKey(HidMouseManager.KEY_ENTER)
                rfcommClientManager.sendCommand(RfcommConstants.CMD_ENTER)
            }
        }

        // 软键盘退格与回车拦截监听（解决手机软键盘在输入框为空时点击删除或回车无法转发至平板的问题）
        binding.etInput.keyEventListener = object : cn.moonify.aircursor.ui.RemoteEditText.KeyEventListener {
            override fun onEmptyBackspace() {
                binding.etInput.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                hidMouseManager.sendKeyboardKey(HidMouseManager.KEY_BACKSPACE)
                rfcommClientManager.sendCommand(RfcommConstants.CMD_BACKSPACE)
            }

            override fun onEnterPressed() {
                binding.etInput.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val currentText = binding.etInput.text?.toString() ?: ""
                if (currentText.isNotEmpty()) {
                    sendTextToTablet(currentText)
                    isInternalClearing = true
                    binding.etInput.text?.clear()
                    isInternalClearing = false
                } else {
                    hidMouseManager.sendKeyboardKey(HidMouseManager.KEY_ENTER)
                    rfcommClientManager.sendCommand(RfcommConstants.CMD_ENTER)
                }
            }
        }

        // 软键盘回车/完成监听：当手机软键盘点击“完成/发送”或 Enter 键时，确保文本即时发送或转发物理回车
        binding.etInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_NEXT ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                val currentText = binding.etInput.text?.toString() ?: ""
                if (currentText.isNotEmpty()) {
                    sendTextToTablet(currentText)
                    isInternalClearing = true
                    binding.etInput.text?.clear()
                    isInternalClearing = false
                } else {
                    binding.etInput.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    hidMouseManager.sendKeyboardKey(HidMouseManager.KEY_ENTER)
                    rfcommClientManager.sendCommand(RfcommConstants.CMD_ENTER)
                }
                true
            } else {
                false
            }
        }

        // Core Chinese Input Logic: Preventing Pinyin Composing Truncation
        binding.etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isInternalClearing || s.isNullOrEmpty()) return

                // 核心防截断机制：
                // 必须先通过 BaseInputConnection.getComposingSpanStart(s) == -1 判断当前是否处于拼音组词状态！
                val composingStart = BaseInputConnection.getComposingSpanStart(s)
                if (composingStart == -1) {
                    val textToSend = s.toString()
                    if (textToSend.isNotEmpty()) {
                        sendTextToTablet(textToSend)

                        isInternalClearing = true
                        s.clear()
                        isInternalClearing = false
                    }
                }
            }
        })

    }

    /**
     * 智能双通道发送文本至平板：
     * 1. 串口已连接时：走 RFCOMM SPP 直接落字（适合平板运行 AirCursor 输入法，100% 原始 UTF-8 字符精准上屏）；
     * 2. 串口未开启/未连接时（走 HID 直连）：自动将中文转译为拼音击键并映射中英文标点，模拟外接物理键盘无缝触发 vivo 等平板候选栏与上屏；
     * 3. 纯鼠标模式且串口未连接时：友好弹出 Toast 提示开启复合模式。
     */
    private fun sendTextToTablet(text: String) {
        val rfConnected = rfcommClientManager.connectionState == RfcommClientManager.STATE_CONNECTED
        val hidConnected = hidMouseManager.connectionState == BluetoothProfile.STATE_CONNECTED

        if (rfConnected) {
            rfcommClientManager.sendText(text)
        } else if (hidConnected && hidMouseManager.isComboMode) {
            hidMouseManager.sendChineseTextAsPinyin(text)
        } else if (hidConnected && !hidMouseManager.isComboMode) {
            Toast.makeText(
                this,
                "当前为纯鼠标模式。输入中文请在设置中开启【外接键盘复合模式】或在平板启用 AirCursor 输入法",
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

        val modeTag = if (hidMouseManager.isComboMode) "HID键盘鼠标" else "HID纯鼠标"
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
        val rfState = rfcommClientManager.connectionState
        val isAnyConnected = connectionManager.isConnected()
        val isAnyConnecting = connectionManager.isConnecting()
        val isReconnecting = connectionManager.isReconnecting
        val isFailed = connectionManager.isConfirmedUnreachable

        // 1. Device name and action button
        if (isReconnecting) {
            val name = activeDevice?.let {
                try { it.name } catch (_: Exception) { null } ?: it.address
            } ?: "平板设备"
            val attempt = connectionManager.reconnectAttempt
            val max = AirCursorConnectionManager.MAX_RECONNECT_ATTEMPTS
            binding.tvPropDevice.text = "正在重连: $name ($attempt/$max)"
            binding.btnPropAction.text = getString(R.string.prop_btn_disconnect)
            binding.btnPropAction.setOnClickListener { disconnectAll() }
        } else if (activeDevice != null && isAnyConnected) {
            val name = try { activeDevice.name } catch (_: Exception) { null } ?: "平板设备"
            binding.tvPropDevice.text = "$name (${activeDevice.address})"
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

        // 3. RFCOMM Badge
        when {
            isReconnecting -> {
                updateBadge(binding.tvBadgeRfcomm, "○ 串口重连中", R.color.accent_amber, R.color.accent_amber_bg)
            }
            rfState == RfcommClientManager.STATE_CONNECTED -> {
                updateBadge(binding.tvBadgeRfcomm, "● 串口已连接", R.color.accent_green, R.color.accent_green_bg)
            }
            rfState == RfcommClientManager.STATE_CONNECTING -> {
                updateBadge(binding.tvBadgeRfcomm, "○ 串口连接中", R.color.accent_amber, R.color.accent_amber_bg)
            }
            else -> {
                val text = if (hidMouseManager.isComboMode && hidState == BluetoothProfile.STATE_CONNECTED) {
                    "○ 串口免用(走HID)"
                } else {
                    "○ 串口未连接"
                }
                updateBadge(binding.tvBadgeRfcomm, text, R.color.badge_gray_text, R.color.badge_gray_bg)
            }
        }

        // 4. Combo mode Badge
        if (hidMouseManager.isComboMode) {
            updateBadge(binding.tvBadgeCombo, "⌨ 物理键盘开启", R.color.primary, R.color.tab_bg)
        } else {
            updateBadge(binding.tvBadgeCombo, "🖱 纯鼠标模式", R.color.badge_gray_text, R.color.badge_gray_bg)
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
                "本应用支持两种平板工作模式：\n\n" +
                "【模式一：推荐！保留 vivo 默认输入法】\n" +
                "1. 平板保持系统默认的【vivo 输入法】（无需切换）；\n" +
                "2. 手机端开启【物理外接键盘模式】并连接平板；\n" +
                "3. 平板识别到外接物理键盘后，点击任何输入框均不会弹出全尺寸软键盘，而是自动弹出 vivo 专属的外接键盘悬浮候选栏与快捷条，光标正常闪烁！\n\n" +
                "【模式二：AirCursor 极简直接上屏模式】\n" +
                "1. 在平板【语言与输入法】中将默认输入法切换为【AirCursor】；\n" +
                "2. 已修复点击输入框无光标问题（已更新为 1px 响应视图）；\n" +
                "3. 点击输入框正常出现闪烁光标，且屏幕完全无键盘遮挡，手机端输入中文直接落字上屏！"
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

    override fun onDestroy() {
        super.onDestroy()
        connectionManager.removeListener(connectionListener)
        // 注意：不要在此处调用 connectionManager.release()，以便退至后台后继续保持蓝牙连接
    }
}