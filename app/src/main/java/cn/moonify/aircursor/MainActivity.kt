package cn.moonify.aircursor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
    }

    private lateinit var binding: ActivityMainBinding
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
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hidMouseManager = HidMouseManager(this)

        setupUI()
        setupListeners()
        checkAndRequestPermissions()
    }

    private fun setupUI() {
        spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spnDevices.adapter = spinnerAdapter
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
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

        // Touchpad movements and tap
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

        // Backspace button (with short click and auto-repeat on hold)
        binding.btnBackspace.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            rfcommClientManager.sendCommand(RfcommConstants.CMD_BACKSPACE)
        }

        // Space button
        binding.btnSpace.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            rfcommClientManager.sendCommand(RfcommConstants.CMD_SPACE)
        }

        // Enter button
        binding.btnEnter.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
                        rfcommClientManager.sendText(textToSend)
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

        // 1. Connect HID Mouse
        hidMouseManager.connect(device)

        // 2. Connect RFCOMM SPP Channel
        rfcommClientManager.connect(device)

        updateStatusText()
    }

    private fun disconnectAll() {
        hidMouseManager.disconnect()
        rfcommClientManager.disconnect()
        updateStatusText()
        Toast.makeText(this, "已断开连接", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatusText() {
        val hidState = hidMouseManager.connectionState
        val hidStateStr = when (hidState) {
            BluetoothProfile.STATE_CONNECTED -> "HID鼠标: 已连接"
            BluetoothProfile.STATE_CONNECTING -> "HID鼠标: 连接中…"
            else -> if (hidMouseManager.isAppRegistered) "HID鼠标: 就绪(未连接)" else "HID鼠标: 未就绪"
        }

        val rfState = rfcommClientManager.connectionState
        val rfStateStr = when (rfState) {
            RfcommClientManager.STATE_CONNECTED -> "键盘串口: 已连接"
            RfcommClientManager.STATE_CONNECTING -> "键盘串口: 连接中…"
            else -> "键盘串口: 未连接"
        }

        val fullStatus = "$hidStateStr  |  $rfStateStr"
        binding.tvStatus.text = fullStatus

        if (hidState == BluetoothProfile.STATE_CONNECTED && rfState == RfcommClientManager.STATE_CONNECTED) {
            binding.tvStatus.setTextColor(Color.parseColor("#10B981")) // Green
        } else if (hidState == BluetoothProfile.STATE_CONNECTING || rfState == RfcommClientManager.STATE_CONNECTING) {
            binding.tvStatus.setTextColor(Color.parseColor("#F59E0B")) // Amber
        } else {
            binding.tvStatus.setTextColor(Color.parseColor("#EF4444")) // Red
        }
    }

    private fun showTabletGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tablet_ime_title))
            .setMessage("【作为平板接收端使用说明】\n\n1. 将本应用安装在平板上；\n2. 在平板上打开【系统设置 -> 语言与输入法】启用【AirCursor 无界面输入法】；\n3. 切换默认输入法为 AirCursor（该输入法高度为 0dp，不遮挡平板屏幕）；\n4. 手机与平板完成经典蓝牙配对后，手机端点击连接即可同时遥控鼠标与汉字上屏！")
            .setPositiveButton(getString(R.string.enable_ime_btn)) { _, _ ->
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                startActivity(intent)
            }
            .setNeutralButton(getString(R.string.select_ime_btn)) { _, _ ->
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showInputMethodPicker()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        hidMouseManager.release()
        rfcommClientManager.release()
    }
}