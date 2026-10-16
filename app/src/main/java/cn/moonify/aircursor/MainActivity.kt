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
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import cn.moonify.aircursor.bluetooth.HidMouseManager
import cn.moonify.aircursor.bluetooth.RfcommClientManager
import cn.moonify.aircursor.bluetooth.RfcommConstants
import cn.moonify.aircursor.databinding.ActivityMainBinding
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

        const val THEME_NIGHT = "night"
        const val THEME_LIGHT = "day"
        const val THEME_SYSTEM = "system"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private lateinit var hidMouseManager: HidMouseManager
    private val rfcommClientManager = RfcommClientManager()

    private val pairedDevices = mutableListOf<BluetoothDevice>()
    private lateinit var spinnerAdapter: ArrayAdapter<String>

    @Volatile
    private var currentButtonState: Byte = 0x00

    private var isInternalClearing = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
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

        hidMouseManager = HidMouseManager(this)

        setupUI()
        setupListeners()
        checkAndRequestPermissions()
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

        val savedWheelSens = prefs.getFloat(KEY_WHEEL_SENS, 0.5f)
        binding.wheelBarView.sensitivity = savedWheelSens
        binding.touchpadView.scrollSensitivity = savedWheelSens
        binding.sbWheelSens.progress = (savedWheelSens * 10).toInt().coerceIn(1, 20)
        binding.tvWheelSensLabel.text = String.format("滚轮灵敏度: %.1fx", savedWheelSens)

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
        binding.switchComboMode.isChecked = hidMouseManager.isComboMode
        binding.switchComboMode.setOnCheckedChangeListener { _, isChecked ->
            hidMouseManager.switchMode(isChecked)
            updateStatusText()
            Toast.makeText(
                this,
                if (isChecked) "已切换为【键盘+鼠标】复合模式 (适配vivo候选栏)" else "已切换为【纯鼠标】模式",
                Toast.LENGTH_SHORT
            ).show()
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
            hidMouseManager.sendKeyboardKey(HidMouseManager.KEY_SPACE)
            rfcommClientManager.sendCommand(RfcommConstants.CMD_SPACE)
        }

        // Enter button
        binding.btnEnter.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            hidMouseManager.sendKeyboardKey(HidMouseManager.KEY_ENTER)
            rfcommClientManager.sendCommand(RfcommConstants.CMD_ENTER)
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
                        // 1. 发送给平板 RemoteImeService (如果平板当前输入法是 AirCursor)
                        rfcommClientManager.sendText(textToSend)

                        // 2. 如果是纯英文字母/拼音且处于外接键盘模式，也发送 HID 击键给 vivo 输入法候选栏
                        if (hidMouseManager.isComboMode && textToSend.all { it.code in 32..126 }) {
                            hidMouseManager.sendKeyboardText(textToSend)
                        }

                        isInternalClearing = true
                        s.clear()
                        isInternalClearing = false
                    }
                }
            }
        })

        // HID Manager Callback
        hidMouseManager.listener = object : HidMouseManager.Listener {
            override fun onHidServiceReady() {
                runOnUiThread { updateStatusText() }
            }

            override fun onAppRegistered(registered: Boolean) {
                runOnUiThread {
                    updateStatusText()
                    if (registered) {
                        Toast.makeText(this@MainActivity, "HID 鼠标服务注册成功", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                runOnUiThread { updateStatusText() }
            }
        }

        // RFCOMM Manager Callback
        rfcommClientManager.listener = object : RfcommClientManager.Listener {
            override fun onConnectionStateChanged(state: Int, device: BluetoothDevice?) {
                runOnUiThread { updateStatusText() }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    updateStatusText()
                }
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

        if (neededPermissions.isNotEmpty()) {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        } else {
            onPermissionsGranted()
        }
    }

    private fun onPermissionsGranted() {
        hidMouseManager.init()
        refreshPairedDevices()
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
        if (bonded.isNullOrEmpty()) {
            spinnerAdapter.add("未找到已配对设备 (请先在系统设置中配对)")
        } else {
            for (device in bonded) {
                pairedDevices.add(device)
                val deviceName = device.name ?: "未知设备"
                spinnerAdapter.add("$deviceName (${device.address})")
            }
        }
        spinnerAdapter.notifyDataSetChanged()
    }

    private fun connectToSelectedDevice() {
        val selectedIndex = binding.spnDevices.selectedItemPosition
        if (selectedIndex < 0 || selectedIndex >= pairedDevices.size) {
            Toast.makeText(this, "请选择一个已配对的平板设备", Toast.LENGTH_SHORT).show()
            return
        }

        val device = pairedDevices[selectedIndex]
        Toast.makeText(this, "正在连接平板: ${device.name ?: device.address}...", Toast.LENGTH_SHORT).show()

        // 1. Connect HID Mouse & Keyboard
        hidMouseManager.connect(device)

        // 2. Connect RFCOMM SPP Channel (静默连接，避免在 vivo 模式下弹出无谓的失败提示)
        rfcommClientManager.connect(device, silentIfFailed = hidMouseManager.isComboMode)

        updateStatusText()
    }

    private fun disconnectAll() {
        hidMouseManager.disconnect()
        rfcommClientManager.disconnect()
        updateStatusText()
        Toast.makeText(this, "已断开连接", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatusText() {
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
        val activeDevice = hidMouseManager.connectedDevice ?: rfcommClientManager.connectedDevice
        val hidState = hidMouseManager.connectionState
        val rfState = rfcommClientManager.connectionState
        val isAnyConnected = hidState == BluetoothProfile.STATE_CONNECTED || rfState == RfcommClientManager.STATE_CONNECTED
        val isAnyConnecting = hidState == BluetoothProfile.STATE_CONNECTING || rfState == RfcommClientManager.STATE_CONNECTING

        // 1. Device name and action button
        if (activeDevice != null && isAnyConnected) {
            val name = activeDevice.name ?: "平板设备"
            binding.tvPropDevice.text = "$name (${activeDevice.address})"
            binding.btnPropAction.text = getString(R.string.prop_btn_disconnect)
            binding.btnPropAction.setOnClickListener { disconnectAll() }
        } else if (isAnyConnecting) {
            binding.tvPropDevice.text = "正在连接平板..."
            binding.btnPropAction.text = getString(R.string.prop_btn_settings)
            binding.btnPropAction.setOnClickListener { switchTab(false) }
        } else {
            binding.tvPropDevice.text = getString(R.string.prop_device_none)
            binding.btnPropAction.text = "去连接"
            binding.btnPropAction.setOnClickListener { switchTab(false) }
        }

        // 2. HID Badge
        when (hidState) {
            BluetoothProfile.STATE_CONNECTED -> {
                updateBadge(binding.tvBadgeHid, "● HID已连接", R.color.accent_green, R.color.accent_green_bg)
            }
            BluetoothProfile.STATE_CONNECTING -> {
                updateBadge(binding.tvBadgeHid, "○ HID连接中", R.color.accent_amber, R.color.accent_amber_bg)
            }
            else -> {
                val text = if (hidMouseManager.isAppRegistered) "○ HID就绪" else "○ HID未就绪"
                updateBadge(binding.tvBadgeHid, text, R.color.badge_gray_text, R.color.badge_gray_bg)
            }
        }

        // 3. RFCOMM Badge
        when (rfState) {
            RfcommClientManager.STATE_CONNECTED -> {
                updateBadge(binding.tvBadgeRfcomm, "● 串口已连接", R.color.accent_green, R.color.accent_green_bg)
            }
            RfcommClientManager.STATE_CONNECTING -> {
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
        hidMouseManager.release()
        rfcommClientManager.release()
    }
}