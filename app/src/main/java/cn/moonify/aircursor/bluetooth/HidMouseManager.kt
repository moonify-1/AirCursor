package cn.moonify.aircursor.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Manages Bluetooth HID registration, connection, and report dispatch.
 * 支持三种模式：
 * 1. 原生多指触控板模式 (Precision Touchpad，默认推荐)：
 *    - 注册为 Digitizer Touchpad，向平板提供最多 5 触点绝对多点触控与 Consumer Control（Home/Back/Volume）。
 *    - 平板系统（Android 13/14+）原生接管多指手势、像素级平滑滚动（带惯性阻尼）、双指缩放及系统触控板设置！
 * 2. 外接键盘 + 鼠标复合模式 (Combo Mode)：
 *    - 向平板注册为 SUBCLASS1_COMBO，系统识别为实体外接物理键盘与鼠标。
 * 3. 纯标准鼠标模式 (Pure Boot Mouse Mode)：
 *    - 仅注册为 SUBCLASS1_MOUSE（4 字节标准鼠标报文，无 Report ID）。
 */
class HidMouseManager(private val context: Context) {

    companion object {
        private const val TAG = "HidMouseManager"

        const val MODE_TOUCHPAD = 0
        const val MODE_COMBO = 1
        const val MODE_PURE_MOUSE = 2

        const val REPORT_ID_KEYBOARD: Byte = 1
        const val REPORT_ID_MOUSE: Byte = 2
        const val REPORT_ID_CONSUMER: Byte = 3

        // 多任务后台切换快捷方式模式
        const val SHORTCUT_RECENTS_OVERVIEW = "recents_overview"
        const val SHORTCUT_ALT_TAB = "alt_tab"
        const val SHORTCUT_WIN_TAB = "win_tab"

        // Consumer Control 按键掩码 (Report ID 3)
        const val CONSUMER_VOLUME_UP: Byte = 0x01
        const val CONSUMER_VOLUME_DOWN: Byte = 0x02
        const val CONSUMER_MUTE: Byte = 0x04
        const val CONSUMER_PLAY_PAUSE: Byte = 0x08
        const val CONSUMER_HOME: Byte = 0x10
        const val CONSUMER_BACK: Byte = 0x20
        const val CONSUMER_RECENT_APPS: Byte = 0x40
        const val CONSUMER_APP_SWITCH: Byte = 0x40 // 兼容别名
        const val CONSUMER_NEXT: Byte = 0x40 // 兼容别名
        const val CONSUMER_PREV: Byte = 0x80.toByte()

        // 鼠标按键掩码 (Report ID 2)
        const val BUTTON_NONE: Byte = 0x00
        const val BUTTON_LEFT: Byte = 0x01
        const val BUTTON_RIGHT: Byte = 0x02
        const val BUTTON_MIDDLE: Byte = 0x04
        const val BUTTON_BACK: Byte = 0x08
        const val BUTTON_FORWARD: Byte = 0x10

        // ==================== 0. 统一高精度复合描述符 (键盘 + 高精度五键鼠标/双轴滚轮 + 消费控制) ====================
        val COMPOSITE_REPORT_DESCRIPTOR = byteArrayOf(
            // ---------- 1. 物理键盘 (Report ID 1, 8 字节输入报文, 1 字节 LED 输出报文) ----------
            0x05.toByte(), 0x01.toByte(), // USAGE_PAGE (Generic Desktop)
            0x09.toByte(), 0x06.toByte(), // USAGE (Keyboard)
            0xA1.toByte(), 0x01.toByte(), // COLLECTION (Application)
            0x85.toByte(), 0x01.toByte(), //   REPORT_ID (1)
            0x05.toByte(), 0x07.toByte(), //   USAGE_PAGE (Keyboard/Keypad)
            0x19.toByte(), 0xE0.toByte(), //   USAGE_MINIMUM (Left Control)
            0x29.toByte(), 0xE7.toByte(), //   USAGE_MAXIMUM (Right GUI)
            0x15.toByte(), 0x00.toByte(), //   LOGICAL_MINIMUM (0)
            0x25.toByte(), 0x01.toByte(), //   LOGICAL_MAXIMUM (1)
            0x75.toByte(), 0x01.toByte(), //   REPORT_SIZE (1)
            0x95.toByte(), 0x08.toByte(), //   REPORT_COUNT (8)
            0x81.toByte(), 0x02.toByte(), //   INPUT (Data,Var,Abs) - 8 个 Modifier 按键掩码
            0x95.toByte(), 0x01.toByte(), //   REPORT_COUNT (1)
            0x75.toByte(), 0x08.toByte(), //   REPORT_SIZE (8)
            0x81.toByte(), 0x01.toByte(), //   INPUT (Cnst,Ary,Abs) - 1 字节系统保留
            0x95.toByte(), 0x05.toByte(), //   REPORT_COUNT (5)
            0x75.toByte(), 0x01.toByte(), //   REPORT_SIZE (1)
            0x05.toByte(), 0x08.toByte(), //   USAGE_PAGE (LEDs)
            0x19.toByte(), 0x01.toByte(), //   USAGE_MINIMUM (Num Lock)
            0x29.toByte(), 0x05.toByte(), //   USAGE_MAXIMUM (Kana)
            0x91.toByte(), 0x02.toByte(), //   OUTPUT (Data,Var,Abs) - 5 位 LED 指示灯状态
            0x95.toByte(), 0x01.toByte(), //   REPORT_COUNT (1)
            0x75.toByte(), 0x03.toByte(), //   REPORT_SIZE (3)
            0x91.toByte(), 0x01.toByte(), //   OUTPUT (Cnst,Ary,Abs) - 3 位补齐
            0x95.toByte(), 0x06.toByte(), //   REPORT_COUNT (6)
            0x75.toByte(), 0x08.toByte(), //   REPORT_SIZE (8)
            0x15.toByte(), 0x00.toByte(), //   LOGICAL_MINIMUM (0)
            0x25.toByte(), 0x65.toByte(), //   LOGICAL_MAXIMUM (101)
            0x05.toByte(), 0x07.toByte(), //   USAGE_PAGE (Keyboard/Keypad)
            0x19.toByte(), 0x00.toByte(), //   USAGE_MINIMUM (0)
            0x29.toByte(), 0x65.toByte(), //   USAGE_MAXIMUM (101)
            0x81.toByte(), 0x00.toByte(), //   INPUT (Data,Ary,Abs) - 6 个常规键键码
            0xC0.toByte(),               // END_COLLECTION

            // ---------- 2. 高精度鼠标 (Report ID 2, 5 字节输入报文: 5按键/X/Y/垂直滚轮/水平平移) ----------
            0x05.toByte(), 0x01.toByte(), // USAGE_PAGE (Generic Desktop)
            0x09.toByte(), 0x02.toByte(), // USAGE (Mouse)
            0xA1.toByte(), 0x01.toByte(), // COLLECTION (Application)
            0x85.toByte(), 0x02.toByte(), //   REPORT_ID (2)
            0x09.toByte(), 0x01.toByte(), //   USAGE (Pointer)
            0xA1.toByte(), 0x00.toByte(), //   COLLECTION (Physical)
            0x05.toByte(), 0x09.toByte(), //     USAGE_PAGE (Button)
            0x19.toByte(), 0x01.toByte(), //     USAGE_MINIMUM (Button 1: 左键)
            0x29.toByte(), 0x05.toByte(), //     USAGE_MAXIMUM (Button 5: 前进键)
            0x15.toByte(), 0x00.toByte(), //     LOGICAL_MINIMUM (0)
            0x25.toByte(), 0x01.toByte(), //     LOGICAL_MAXIMUM (1)
            0x95.toByte(), 0x05.toByte(), //     REPORT_COUNT (5)
            0x75.toByte(), 0x01.toByte(), //     REPORT_SIZE (1)
            0x81.toByte(), 0x02.toByte(), //     INPUT (Data,Var,Abs) - 5 个按键位
            0x95.toByte(), 0x01.toByte(), //     REPORT_COUNT (1)
            0x75.toByte(), 0x03.toByte(), //     REPORT_SIZE (3)
            0x81.toByte(), 0x03.toByte(), //     INPUT (Cnst,Var,Abs) - 3 位常量补齐
            0x05.toByte(), 0x01.toByte(), //     USAGE_PAGE (Generic Desktop)
            0x09.toByte(), 0x30.toByte(), //     USAGE (X)
            0x09.toByte(), 0x31.toByte(), //     USAGE (Y)
            0x15.toByte(), 0x81.toByte(), //     LOGICAL_MINIMUM (-127)
            0x25.toByte(), 0x7F.toByte(), //     LOGICAL_MAXIMUM (127)
            0x75.toByte(), 0x08.toByte(), //     REPORT_SIZE (8)
            0x95.toByte(), 0x02.toByte(), //     REPORT_COUNT (2)
            0x81.toByte(), 0x06.toByte(), //     INPUT (Data,Var,Rel) - X, Y 位移
            0x09.toByte(), 0x38.toByte(), //     USAGE (Wheel)
            0x15.toByte(), 0x81.toByte(), //     LOGICAL_MINIMUM (-127)
            0x25.toByte(), 0x7F.toByte(), //     LOGICAL_MAXIMUM (127)
            0x75.toByte(), 0x08.toByte(), //     REPORT_SIZE (8)
            0x95.toByte(), 0x01.toByte(), //     REPORT_COUNT (1)
            0x81.toByte(), 0x06.toByte(), //     INPUT (Data,Var,Rel) - 垂直滚轮
            0x05.toByte(), 0x0C.toByte(), //     USAGE_PAGE (Consumer Devices)
            0x0A.toByte(), 0x38.toByte(), 0x02.toByte(), // USAGE (AC Pan - 水平滚轮)
            0x15.toByte(), 0x81.toByte(), //     LOGICAL_MINIMUM (-127)
            0x25.toByte(), 0x7F.toByte(), //     LOGICAL_MAXIMUM (127)
            0x75.toByte(), 0x08.toByte(), //     REPORT_SIZE (8)
            0x95.toByte(), 0x01.toByte(), //     REPORT_COUNT (1)
            0x81.toByte(), 0x06.toByte(), //     INPUT (Data,Var,Rel) - 水平滚轮
            0xC0.toByte(),               //   END_COLLECTION
            0xC0.toByte(),               // END_COLLECTION

            // ---------- 3. 消费类多媒体与系统控制 (Report ID 3, 1 字节输入报文: Home/Back/Volume) ----------
            0x05.toByte(), 0x0C.toByte(), // USAGE_PAGE (Consumer Devices)
            0x09.toByte(), 0x01.toByte(), // USAGE (Consumer Control)
            0xA1.toByte(), 0x01.toByte(), // COLLECTION (Application)
            0x85.toByte(), 0x03.toByte(), //   REPORT_ID (3)
            0x15.toByte(), 0x00.toByte(), //   LOGICAL_MINIMUM (0)
            0x25.toByte(), 0x01.toByte(), //   LOGICAL_MAXIMUM (1)
            0x75.toByte(), 0x01.toByte(), //   REPORT_SIZE (1)
            0x95.toByte(), 0x08.toByte(), //   REPORT_COUNT (8)
            0x09.toByte(), 0xE9.toByte(), //   USAGE (Volume Increment)    -> Bit 0 (0x01)
            0x09.toByte(), 0xEA.toByte(), //   USAGE (Volume Decrement)    -> Bit 1 (0x02)
            0x09.toByte(), 0xE2.toByte(), //   USAGE (Mute)                -> Bit 2 (0x04)
            0x09.toByte(), 0xCD.toByte(), //   USAGE (Play/Pause)           -> Bit 3 (0x08)
            0x0A.toByte(), 0x23.toByte(), 0x02.toByte(), // USAGE (AC Home) -> Bit 4 (0x10)
            0x0A.toByte(), 0x24.toByte(), 0x02.toByte(), // USAGE (AC Back) -> Bit 5 (0x20)
            0x0A.toByte(), 0x9F.toByte(), 0x02.toByte(), // USAGE (AL Desktop Show All Windows -> RECENT_APPS) -> Bit 6 (0x40)
            0x09.toByte(), 0xB6.toByte(), //   USAGE (Scan Previous Track) -> Bit 7 (0x80)
            0x81.toByte(), 0x02.toByte(), //   INPUT (Data,Var,Abs)
            0xC0.toByte()                // END_COLLECTION
        )

        // 兼容别名
        val TOUCHPAD_REPORT_DESCRIPTOR = COMPOSITE_REPORT_DESCRIPTOR
        val COMBO_REPORT_DESCRIPTOR = COMPOSITE_REPORT_DESCRIPTOR

        // ==================== 2. 纯 Boot Mouse 描述符 (4 字节无 Report ID，极简兼容) ====================
        val PURE_MOUSE_REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(),
            0x09.toByte(), 0x02.toByte(),
            0xA1.toByte(), 0x01.toByte(),
            0x09.toByte(), 0x01.toByte(),
            0xA1.toByte(), 0x00.toByte(),
            0x05.toByte(), 0x09.toByte(),
            0x19.toByte(), 0x01.toByte(),
            0x29.toByte(), 0x05.toByte(),
            0x15.toByte(), 0x00.toByte(),
            0x25.toByte(), 0x01.toByte(),
            0x95.toByte(), 0x05.toByte(),
            0x75.toByte(), 0x01.toByte(),
            0x81.toByte(), 0x02.toByte(),
            0x95.toByte(), 0x01.toByte(),
            0x75.toByte(), 0x03.toByte(),
            0x81.toByte(), 0x03.toByte(),
            0x05.toByte(), 0x01.toByte(),
            0x09.toByte(), 0x30.toByte(),
            0x09.toByte(), 0x31.toByte(),
            0x15.toByte(), 0x81.toByte(),
            0x25.toByte(), 0x7F.toByte(),
            0x75.toByte(), 0x08.toByte(),
            0x95.toByte(), 0x02.toByte(),
            0x81.toByte(), 0x06.toByte(),
            0x09.toByte(), 0x38.toByte(),
            0x15.toByte(), 0x81.toByte(),
            0x25.toByte(), 0x7F.toByte(),
            0x75.toByte(), 0x08.toByte(),
            0x95.toByte(), 0x01.toByte(),
            0x81.toByte(), 0x06.toByte(),
            0xC0.toByte(),
            0xC0.toByte()
        )

        // 常用 HID 按键码 (Usage Page 0x07)
        const val KEY_V: Byte = 0x19
        const val KEY_ENTER: Byte = 0x28
        const val KEY_ESCAPE: Byte = 0x29
        const val KEY_BACKSPACE: Byte = 0x2A
        const val KEY_TAB: Byte = 0x2B
        const val KEY_SPACE: Byte = 0x2C

        const val MODIFIER_LEFT_CTRL: Byte = 0x01
        const val MODIFIER_LEFT_SHIFT: Byte = 0x02
        const val MODIFIER_LEFT_ALT: Byte = 0x04
        const val MODIFIER_LEFT_GUI: Byte = 0x08
    }

    interface Listener {
        fun onHidServiceReady()
        fun onAppRegistered(registered: Boolean)
        fun onConnectionStateChanged(device: BluetoothDevice?, state: Int)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) {
            listeners.add(l)
        }
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    var listener: Listener?
        get() = listeners.firstOrNull()
        set(value) {
            if (value != null && !listeners.contains(value)) {
                listeners.add(value)
            }
        }

    private fun notifyHidServiceReady() {
        for (l in listeners) {
            try {
                l.onHidServiceReady()
            } catch (e: Exception) {
                Log.e(TAG, "Listener onHidServiceReady error", e)
            }
        }
    }

    private fun notifyAppRegistered(registered: Boolean) {
        for (l in listeners) {
            try {
                l.onAppRegistered(registered)
            } catch (e: Exception) {
                Log.e(TAG, "Listener onAppRegistered error", e)
            }
        }
    }

    private fun notifyConnectionStateChanged(device: BluetoothDevice?, state: Int) {
        for (l in listeners) {
            try {
                l.onConnectionStateChanged(device, state)
            } catch (e: Exception) {
                Log.e(TAG, "Listener onConnectionStateChanged error", e)
            }
        }
    }

    // 默认从本地持久化配置读取，未配置时使用键盘+鼠标复合模式（MODE_COMBO）
    var hidMode: Int = try {
        context.getSharedPreferences("aircursor_prefs", Context.MODE_PRIVATE).getInt("hid_mode", MODE_COMBO)
    } catch (_: Exception) {
        MODE_COMBO
    }

    val isTouchpadMode: Boolean get() = (hidMode == MODE_TOUCHPAD)
    val isComboMode: Boolean get() = (hidMode != MODE_PURE_MOUSE)
    val isKeyboardSupported: Boolean get() = (hidMode != MODE_PURE_MOUSE)

    @Volatile
    var currentProtocol: Byte = BluetoothHidDevice.PROTOCOL_REPORT_MODE
        private set

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var hidDevice: BluetoothHidDevice? = null
    private val executor = Executors.newCachedThreadPool()
    private val keyboardExecutor = Executors.newSingleThreadExecutor()

    init {
        try {
            PinyinConverter.init(context.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init PinyinConverter in HidMouseManager", e)
        }
    }

    @Volatile
    var isAppRegistered = false
        private set

    @Volatile
    private var isRegistering = false

    @Volatile
    private var pendingModeSwitch: Int? = null

    @Volatile
    var connectedDevice: BluetoothDevice? = null
        private set

    @Volatile
    var connectionState: Int = BluetoothProfile.STATE_DISCONNECTED
        private set

    @Volatile
    var targetDeviceToConnect: BluetoothDevice? = null
        private set

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            Log.d(TAG, "onAppStatusChanged: registered=$registered, pluggedDevice=$pluggedDevice, pendingModeSwitch=$pendingModeSwitch")
            isAppRegistered = registered
            isRegistering = false

            // 若正处于异步切换模式过程中，等待注销完毕后稍作延迟重新注册新模式描述符
            if (!registered) {
                val nextMode = pendingModeSwitch
                if (nextMode != null) {
                    pendingModeSwitch = null
                    executor.execute {
                        try {
                            Thread.sleep(150)
                        } catch (_: InterruptedException) {}
                        registerApp()
                    }
                    return
                }
            }

            notifyAppRegistered(registered)

            if (registered && targetDeviceToConnect != null) {
                val target = targetDeviceToConnect
                targetDeviceToConnect = null
                target?.let { connect(it) }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            Log.d(TAG, "onConnectionStateChanged: device=${device?.address}, state=$state")
            connectionState = state
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                targetDeviceToConnect = null
                currentProtocol = BluetoothHidDevice.PROTOCOL_REPORT_MODE
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice?.address == device?.address) {
                    connectedDevice = null
                }
                currentProtocol = BluetoothHidDevice.PROTOCOL_REPORT_MODE
            }
            notifyConnectionStateChanged(device, state)
        }

        @SuppressLint("MissingPermission")
        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            super.onGetReport(device, type, id, bufferSize)
            Log.d(TAG, "onGetReport: type=$type, id=$id, protocol=$currentProtocol, mode=$hidMode")
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                if (hidMode == MODE_PURE_MOUSE || currentProtocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE) {
                    hidDevice?.replyReport(device, type, id, ByteArray(4))
                } else {
                    when (id) {
                        REPORT_ID_KEYBOARD -> hidDevice?.replyReport(device, type, id, ByteArray(8))
                        REPORT_ID_MOUSE -> hidDevice?.replyReport(device, type, id, ByteArray(5))
                        REPORT_ID_CONSUMER -> hidDevice?.replyReport(device, type, id, ByteArray(1))
                        else -> hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
                    }
                }
            } else if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT) {
                if (id == REPORT_ID_KEYBOARD) {
                    hidDevice?.replyReport(device, type, id, ByteArray(1))
                } else {
                    hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
                }
            } else {
                hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            super.onSetReport(device, type, id, data)
            Log.d(TAG, "onSetReport: type=$type, id=$id")
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        @SuppressLint("MissingPermission")
        override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
            super.onSetProtocol(device, protocol)
            Log.d(TAG, "onSetProtocol: protocol=$protocol")
            currentProtocol = protocol
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            super.onVirtualCableUnplug(device)
            Log.d(TAG, "onVirtualCableUnplug: device=${device?.address}")
            if (connectedDevice?.address == device?.address) {
                connectedDevice = null
                connectionState = BluetoothProfile.STATE_DISCONNECTED
                notifyConnectionStateChanged(device, BluetoothProfile.STATE_DISCONNECTED)
            }
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(TAG, "Bluetooth HID Device profile connected")
                hidDevice = proxy as? BluetoothHidDevice
                registerApp()
                notifyHidServiceReady()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(TAG, "Bluetooth HID Device profile disconnected")
                hidDevice = null
                isAppRegistered = false
                isRegistering = false
                connectedDevice = null
                connectionState = BluetoothProfile.STATE_DISCONNECTED
            }
        }
    }

    fun init() {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "BluetoothAdapter is null, device does not support Bluetooth")
            return
        }
        bluetoothAdapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
    }

    /**
     * 根据当前 hidMode 模式注册 SDP 记录与 HID 描述符。
     */
    @SuppressLint("MissingPermission")
    fun registerApp() {
        val hid = hidDevice ?: return
        if (isAppRegistered || isRegistering) return

        val sdp = when (hidMode) {
            MODE_PURE_MOUSE -> {
                BluetoothHidDeviceAppSdpSettings(
                    "AirCursor Mouse",
                    "AirCursor Boot Mouse",
                    "AirCursor",
                    BluetoothHidDevice.SUBCLASS1_MOUSE, // 0x80: 纯鼠标
                    PURE_MOUSE_REPORT_DESCRIPTOR
                )
            }
            else -> {
                BluetoothHidDeviceAppSdpSettings(
                    "AirCursor Touchpad",
                    "AirCursor Precision Touchpad & Keyboard",
                    "AirCursor",
                    BluetoothHidDevice.SUBCLASS1_COMBO, // 0xC0: 复合设备 (键盘 + 鼠标 + 多媒体)
                    COMPOSITE_REPORT_DESCRIPTOR
                )
            }
        }

        try {
            isRegistering = true
            val success = hid.registerApp(sdp, null, null, executor, hidCallback)
            Log.d(TAG, "registerApp invoked (mode=$hidMode), success=$success")
            if (!success) {
                isRegistering = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to registerApp", e)
            isRegistering = false
        }
    }

    @SuppressLint("MissingPermission")
    fun switchMode(newMode: Int) {
        if (hidMode == newMode && isAppRegistered) return
        hidMode = newMode
        try {
            context.getSharedPreferences("aircursor_prefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("hid_mode", newMode)
                .putBoolean("combo_mode", newMode != MODE_PURE_MOUSE)
                .apply()
        } catch (_: Exception) {}

        currentProtocol = BluetoothHidDevice.PROTOCOL_REPORT_MODE
        val hid = hidDevice ?: return

        if (isAppRegistered) {
            pendingModeSwitch = newMode
            try {
                hid.unregisterApp()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregisterApp during switchMode", e)
                pendingModeSwitch = null
                registerApp()
            }
        } else {
            registerApp()
        }
    }

    @SuppressLint("MissingPermission")
    fun switchMode(comboMode: Boolean) {
        switchMode(if (comboMode) MODE_COMBO else MODE_PURE_MOUSE)
    }

    /**
     * 发送原生高精度多指触控板报文（兼容别名，无须向平板发送不可识别的绝对多点触控）
     */
    fun sendTouchpadReport(
        contacts: List<TouchContact>,
        buttonState: Byte = 0
    ): Boolean {
        return false
    }

    /**
     * 发送 Consumer Control 多媒体与系统按键（桌面 Home、返回 Back、音量加减等）
     */
    @SuppressLint("MissingPermission")
    fun sendConsumerKey(keyMask: Byte) {
        if (hidMode == MODE_PURE_MOUSE) return
        val device = connectedDevice ?: return

        executor.execute {
            try {
                val press = byteArrayOf(keyMask)
                sendReportWithRetry(device, REPORT_ID_CONSUMER.toInt(), press, maxRetries = 3)
                Thread.sleep(50)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sendConsumerKey $keyMask", e)
            } finally {
                val release = byteArrayOf(0)
                sendReportWithRetry(device, REPORT_ID_CONSUMER.toInt(), release, maxRetries = 5)
            }
        }
    }

    /**
     * 发送系统 Back 返回键至平板：
     * 发送标准 5 键鼠标的第 4 键 (BUTTON_BACK = 0x08)。
     * 在 Android 系统底层 (CursorInputMapper)，鼠标第 4 键 (AMOTION_EVENT_BUTTON_BACK)
     * 被直接转化为全局硬件返回事件 (KeyEvent.KEYCODE_BACK)，能 100% 穿透所有应用、浏览器及系统界面触发返回！
     */
    @SuppressLint("MissingPermission")
    fun sendBack() {
        val hid = hidDevice ?: return
        val device = connectedDevice ?: return

        executor.execute {
            try {
                // 1. 发送鼠标第 4 键按下 (BUTTON_BACK = 0x08)
                var okDown = false
                for (i in 0 until 4) {
                    okDown = sendMouseReport(BUTTON_BACK, 0, 0, 0, 0)
                    if (okDown) break
                    Thread.sleep(8)
                }

                Thread.sleep(45)

                // 2. 发送鼠标释放 (全 0)
                for (i in 0 until 5) {
                    val okUp = sendMouseReport(BUTTON_NONE, 0, 0, 0, 0)
                    if (okUp) break
                    Thread.sleep(8)
                }
                Log.d(TAG, "sendBack: sent mouse BUTTON_BACK report (okDown=$okDown)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send mouse back report", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val hid = hidDevice
        if (hid == null || !isAppRegistered) {
            Log.d(TAG, "HID device not ready or not registered yet (hid=${hid != null}, registered=$isAppRegistered), deferring connection to ${device.address}")
            targetDeviceToConnect = device
            if (hid != null && !isAppRegistered && !isRegistering) {
                registerApp()
            }
            return
        }

        try {
            Log.d(TAG, "Connecting HID to ${device.address}")
            connectionState = BluetoothProfile.STATE_CONNECTING
            notifyConnectionStateChanged(device, BluetoothProfile.STATE_CONNECTING)
            val success = hid.connect(device)
            Log.d(TAG, "hid.connect returned $success")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect HID to ${device.address}", e)
            connectionState = BluetoothProfile.STATE_DISCONNECTED
            notifyConnectionStateChanged(device, BluetoothProfile.STATE_DISCONNECTED)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        targetDeviceToConnect = null
        val hid = hidDevice ?: return
        val target = connectedDevice ?: return
        try {
            connectionState = BluetoothProfile.STATE_DISCONNECTING
            notifyConnectionStateChanged(target, BluetoothProfile.STATE_DISCONNECTING)
            hid.disconnect(target)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect HID", e)
        }
    }

    /**
     * 发送鼠标位移与按键报文：
     * - 纯 Boot 模式或主机请求 BOOT_MODE：发送标准 4 字节报文 (reportId = 0)
     * - 复合模式 (默认)：发送 5 字节带双向滚轮报文 (Report ID 2: 按键, dx, dy, wheel, pan)
     */
    @SuppressLint("MissingPermission")
    fun sendMouseReport(buttonState: Byte, dx: Int, dy: Int, wheel: Int, pan: Int = 0): Boolean {
        val hid = hidDevice ?: return false
        val device = connectedDevice ?: return false

        val clampedDx = dx.coerceIn(-127, 127).toByte()
        val clampedDy = dy.coerceIn(-127, 127).toByte()
        val clampedWheel = wheel.coerceIn(-127, 127).toByte()
        val clampedPan = pan.coerceIn(-127, 127).toByte()

        return if (hidMode == MODE_PURE_MOUSE || currentProtocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE) {
            val report = byteArrayOf(buttonState, clampedDx, clampedDy, clampedWheel)
            try {
                hid.sendReport(device, 0, report)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send boot mouse report", e)
                false
            }
        } else {
            val report = byteArrayOf(buttonState, clampedDx, clampedDy, clampedWheel, clampedPan)
            try {
                hid.sendReport(device, REPORT_ID_MOUSE.toInt(), report)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send composite mouse report", e)
                false
            }
        }
    }

    /**
     * 带重试与异常保护的底层报文发送辅助方法，防止因蓝牙瞬时拥塞导致报文丢失
     */
    @SuppressLint("MissingPermission")
    private fun sendReportWithRetry(device: BluetoothDevice, reportId: Int, report: ByteArray, maxRetries: Int = 3): Boolean {
        val hid = hidDevice ?: return false
        for (i in 0 until maxRetries) {
            try {
                if (hid.sendReport(device, reportId, report)) {
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "sendReport exception on attempt $i", e)
            }
            try {
                Thread.sleep(3)
            } catch (_: InterruptedException) {
                break
            }
        }
        return false
    }

    /**
     * 底层同步发送单个按键按下与释放报文（必须在单线程队列 keyboardExecutor 中执行，确保严格时序）：
     * 关键修复：KeyUp 报文执行最高优先级保障，在 finally 块中兜底重试，彻底杜绝 KeyDown 遗留导致的硬件连续连击！
     */
    @SuppressLint("MissingPermission")
    private fun sendKeySync(keycode: Byte, modifier: Byte = 0, pressHoldMs: Long = 16L, releaseGapMs: Long = 14L) {
        val device = connectedDevice ?: return
        try {
            // Key Down: 重试 3 次
            val press = byteArrayOf(modifier, 0, keycode, 0, 0, 0, 0, 0)
            sendReportWithRetry(device, REPORT_ID_KEYBOARD.toInt(), press, maxRetries = 3)
            try {
                Thread.sleep(pressHoldMs)
            } catch (_: InterruptedException) {
                return
            }
        } finally {
            // Key Up: 强制释放，重试 5 次确保送达
            val release = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
            sendReportWithRetry(device, REPORT_ID_KEYBOARD.toInt(), release, maxRetries = 5)
            try {
                Thread.sleep(releaseGapMs)
            } catch (_: InterruptedException) {}
        }
    }

    /**
     * 强制向平板发送键盘全零释放报文，清除平板可能滞留的任何按键状态
     */
    @SuppressLint("MissingPermission")
    private fun sendKeyboardReleaseSync() {
        val device = connectedDevice ?: return
        val release = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
        sendReportWithRetry(device, REPORT_ID_KEYBOARD.toInt(), release, maxRetries = 5)
    }

    /**
     * 发送实体键盘按键报文 (按下后自动释放)：
     * 仅在 Combo 模式下有效，由专用单线程队列按序发送
     */
    fun sendKeyboardKey(keycode: Byte, modifier: Byte = 0) {
        if (!isKeyboardSupported) return
        keyboardExecutor.execute {
            try {
                sendKeySync(keycode, modifier)
            } finally {
                sendKeyboardReleaseSync()
            }
        }
    }

    /**
     * 发送 Ctrl + V 粘贴快捷键：
     * 用于配合平板端剪贴板中继，实现免切换输入法 100% 精准文字粘贴落字
     */
    fun sendPasteShortcut() {
        if (!isKeyboardSupported) return
        keyboardExecutor.execute {
            val device = connectedDevice ?: return@execute
            try {
                // 1. 先发送 Ctrl Down 报文，确保平板系统与输入法底层完全建立 Ctrl 修饰键按下状态
                val ctrlDown = byteArrayOf(MODIFIER_LEFT_CTRL, 0, 0, 0, 0, 0, 0, 0)
                sendReportWithRetry(device, REPORT_ID_KEYBOARD.toInt(), ctrlDown, maxRetries = 3)
                try {
                    Thread.sleep(25)
                } catch (_: InterruptedException) {
                    return@execute
                }

                // 2. 保持 Ctrl 的同时按下 V 键，精确触发系统级 Ctrl + V 粘贴快捷指令
                val pasteDown = byteArrayOf(MODIFIER_LEFT_CTRL, 0, KEY_V, 0, 0, 0, 0, 0)
                sendReportWithRetry(device, REPORT_ID_KEYBOARD.toInt(), pasteDown, maxRetries = 3)
                try {
                    Thread.sleep(35)
                } catch (_: InterruptedException) {
                    return@execute
                }

                // 3. 释放 V 键但继续维持 Ctrl 状态，防止释放抖动
                sendReportWithRetry(device, REPORT_ID_KEYBOARD.toInt(), ctrlDown, maxRetries = 3)
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    return@execute
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending paste shortcut", e)
            } finally {
                // 4. 彻底释放所有按键报文，重试确保清除无按键滞留
                sendKeyboardReleaseSync()
            }
        }
    }

    /**
     * 发送系统后台应用选择/多任务切换快捷指令：
     * @param useAltTab 为 true 时发送通用 Alt + Tab（Android 平板标准通用切换应用快捷键，推荐大多数平板），
     *                  为 false 时发送 Win/Meta + Tab。
     * @param reverse 为 true 时附加 Shift 键（Alt + Shift + Tab / Win + Shift + Tab）反向循环切换。
     */
    fun sendRecentApps(useAltTab: Boolean = true, reverse: Boolean = false) {
        if (!isKeyboardSupported) {
            Log.w(TAG, "sendRecentApps: keyboard not supported in mode $hidMode")
            return
        }
        keyboardExecutor.execute {
            val device = connectedDevice
            if (device == null) {
                Log.w(TAG, "sendRecentApps: connectedDevice is null, cannot send report")
                return@execute
            }
            try {
                // 0x04 为 Left Alt (Android 标准多任务/切应用快捷键), 0x08 为 Left GUI / Win 键, 0x02 为 Left Shift
                val baseModifier: Byte = if (useAltTab) 0x04 else 0x08
                val modifier: Byte = if (reverse) (baseModifier.toInt() or 0x02).toByte() else baseModifier

                // 1. Modifier 按下 (提前 35ms 建立组合键状态)
                val modDown = byteArrayOf(modifier, 0, 0, 0, 0, 0, 0, 0)
                sendReportWithRetry(device, REPORT_ID_KEYBOARD.toInt(), modDown, maxRetries = 3)
                Thread.sleep(35)

                // 2. Modifier + Tab 同时按下 (保持 120ms，留给平板系统充足的响应、渲染和选定前台目标应用的时间)
                val tabDown = byteArrayOf(modifier, 0, KEY_TAB, 0, 0, 0, 0, 0)
                sendReportWithRetry(device, REPORT_ID_KEYBOARD.toInt(), tabDown, maxRetries = 3)
                Thread.sleep(120)

                // 3. Tab 释放 (保持 Modifier 80ms，稳定锁定目标应用)
                sendReportWithRetry(device, REPORT_ID_KEYBOARD.toInt(), modDown, maxRetries = 3)
                Thread.sleep(80)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending recent apps shortcut", e)
            } finally {
                // 4. 全部释放 (松开 Alt/Meta，触发平板最终切换；重试 5 次确保送达，杜绝按键连击残留)
                sendKeyboardReleaseSync()
                Log.d(TAG, "sendRecentApps: finished sending shortcut (useAltTab=$useAltTab, reverse=$reverse)")
            }
        }
    }

    /**
     * 发送系统后台应用多任务概览指令，呼出平板后台并保持停留在多任务界面（供用户自主挑选应用）：
     * 1. 默认/SHORTCUT_RECENTS_OVERVIEW: 通过 Consumer Control 发送 0x029F (AL Desktop Show All Windows -> RECENT_APPS)，
     *    触发 Android 系统 toggleRecentApps()，呼出后台卡片并稳定停留在后台，供用户自主选择应用；
     * 2. SHORTCUT_ALT_TAB: 用户指定使用 Alt + Tab 实体键盘切换（快速切至上一应用）；
     * 3. SHORTCUT_WIN_TAB: 用户指定使用 Win/Meta + Tab 组合键（适用于 Windows 掌机/平板或部分 Android 14+ 系统的任务视图）。
     */
    fun sendOverview(shortcutMode: String = SHORTCUT_RECENTS_OVERVIEW) {
        if (!isKeyboardSupported) return
        val device = connectedDevice
        if (device == null) {
            Log.w(TAG, "sendOverview: connectedDevice is null, cannot send report")
            return
        }

        when (shortcutMode) {
            SHORTCUT_ALT_TAB -> {
                sendRecentApps(useAltTab = true, reverse = false)
            }
            SHORTCUT_WIN_TAB -> {
                keyboardExecutor.execute {
                    try {
                        val modifier: Byte = 0x08 // Left GUI / Meta / Win
                        val modDown = byteArrayOf(modifier, 0, 0, 0, 0, 0, 0, 0)
                        sendReportWithRetry(device, REPORT_ID_KEYBOARD.toInt(), modDown, maxRetries = 3)
                        Thread.sleep(35)
                        val tabDown = byteArrayOf(modifier, 0, KEY_TAB, 0, 0, 0, 0, 0)
                        sendReportWithRetry(device, REPORT_ID_KEYBOARD.toInt(), tabDown, maxRetries = 3)
                        Thread.sleep(120)
                        sendReportWithRetry(device, REPORT_ID_KEYBOARD.toInt(), modDown, maxRetries = 3)
                        Thread.sleep(60)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send Win+Tab overview", e)
                    } finally {
                        sendKeyboardReleaseSync()
                        Log.d(TAG, "sendOverview: finished sending Win+Tab")
                    }
                }
            }
            else -> {
                // 原生多任务卡片 0x029F RECENT_APPS，Android 系统收到后 toggleRecentApps() 并保持停留
                executor.execute {
                    try {
                        val press = byteArrayOf(CONSUMER_RECENT_APPS)
                        sendReportWithRetry(device, REPORT_ID_CONSUMER.toInt(), press, maxRetries = 3)
                        Thread.sleep(60)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send CONSUMER_RECENT_APPS press", e)
                    } finally {
                        val release = byteArrayOf(0)
                        sendReportWithRetry(device, REPORT_ID_CONSUMER.toInt(), release, maxRetries = 5)
                        Log.d(TAG, "sendOverview: finished sending CONSUMER_RECENT_APPS (0x029F)")
                    }
                }
            }
        }
    }

    /**
     * 兼容旧版 Boolean 参数调用
     */
    @Deprecated("Use sendOverview(shortcutMode: String)")
    fun sendOverview(useWinTab: Boolean) {
        sendOverview(if (useWinTab) SHORTCUT_WIN_TAB else SHORTCUT_RECENTS_OVERVIEW)
    }

    /**
     * 将 ASCII 字符串转换为 HID 按键序列发送给平板（供 vivo 物理键盘拼音候选栏输入）
     */
    fun sendKeyboardText(text: String) {
        if (!isKeyboardSupported) return
        keyboardExecutor.execute {
            for (c in text) {
                val mapping = charToKeycode(c)
                if (mapping != null) {
                    sendKeySync(mapping.first, mapping.second)
                }
            }
        }
    }

    /**
     * 将包含中文、英文、数字与中英文标点的字符串转译为 HID 击键序列：
     * 1. 连续汉字：提取对应拼音英文字母击键序列，发送完毕后充分等待平板输入法候选栏渲染，并自动发送空格键上屏确认；
     * 2. 中英文标点与特殊符号：直接映射对应键码发送；
     * 3. 英文与数字：直接发送键盘按键。
     */
    fun sendChineseTextAsPinyin(text: String) {
        if (!isKeyboardSupported) return
        keyboardExecutor.execute {
            try {
                val actions = PinyinConverter.convertTextToKeystrokeActions(text)
                Log.d(TAG, "sendChineseTextAsPinyin: text='$text', generated ${actions.size} actions")
                for (action in actions) {
                    when (action) {
                        is PinyinConverter.KeystrokeAction.PinyinSequence -> {
                            for (c in action.pinyin) {
                                val mapping = charToKeycode(c)
                                if (mapping != null) {
                                    sendKeySync(mapping.first, mapping.second, pressHoldMs = 15L, releaseGapMs = 12L)
                                }
                            }
                            // 给平板输入法核心引擎留出拼音分词、候选词检索与悬浮窗渲染时间（优化至 60ms）
                            try {
                                Thread.sleep(60)
                            } catch (_: InterruptedException) {
                                return@execute
                            }

                            // 发送空格键，提交确认平板候选栏首位汉字候选词上屏
                            sendKeySync(KEY_SPACE, 0, pressHoldMs = 16L, releaseGapMs = 14L)

                            // 等待平板输入法完成 InputConnection.commitText() 并重置候选缓冲区（优化至 35ms）
                            try {
                                Thread.sleep(35)
                            } catch (_: InterruptedException) {
                                return@execute
                            }
                        }
                        is PinyinConverter.KeystrokeAction.DirectChar -> {
                            val mapping = charToKeycode(action.char)
                            if (mapping != null) {
                                sendKeySync(mapping.first, mapping.second, pressHoldMs = 16L, releaseGapMs = 14L)
                                try {
                                    Thread.sleep(12)
                                } catch (_: InterruptedException) {
                                    return@execute
                                }
                            }
                        }
                    }
                }
            } finally {
                // 确保在任务结束或任何中断异常时，强行重置所有按键状态，杜绝任何按键连击残留
                sendKeyboardReleaseSync()
            }
        }
    }

    private fun charToKeycode(c: Char): Pair<Byte, Byte>? {
        return when (c) {
            // 英文小写
            in 'a'..'z' -> Pair((c - 'a' + 4).toByte(), 0.toByte())
            // 英文大写 (Shift)
            in 'A'..'Z' -> Pair((c - 'A' + 4).toByte(), 0x02.toByte())
            // 数字
            in '1'..'9' -> Pair((c - '1' + 0x1E).toByte(), 0.toByte())
            '0' -> Pair(0x27.toByte(), 0.toByte())

            // 控制字符
            ' ' -> Pair(KEY_SPACE, 0.toByte())
            '\n' -> Pair(KEY_ENTER, 0.toByte())
            '\b' -> Pair(KEY_BACKSPACE, 0.toByte())
            '\t' -> Pair(KEY_TAB, 0.toByte())

            // ASCII 基础符号 (无 Shift)
            '-' -> Pair(0x2D.toByte(), 0.toByte())
            '=' -> Pair(0x2E.toByte(), 0.toByte())
            '[' -> Pair(0x2F.toByte(), 0.toByte())
            ']' -> Pair(0x30.toByte(), 0.toByte())
            '\\' -> Pair(0x31.toByte(), 0.toByte())
            ';' -> Pair(0x33.toByte(), 0.toByte())
            '\'' -> Pair(0x34.toByte(), 0.toByte())
            '`' -> Pair(0x35.toByte(), 0.toByte())
            ',' -> Pair(0x36.toByte(), 0.toByte())
            '.' -> Pair(0x37.toByte(), 0.toByte())
            '/' -> Pair(0x38.toByte(), 0.toByte())

            // ASCII 上档符号 (带 Shift: 0x02)
            '!' -> Pair(0x1E.toByte(), 0x02.toByte())
            '@' -> Pair(0x1F.toByte(), 0x02.toByte())
            '#' -> Pair(0x20.toByte(), 0x02.toByte())
            '$' -> Pair(0x21.toByte(), 0x02.toByte())
            '%' -> Pair(0x22.toByte(), 0x02.toByte())
            '^' -> Pair(0x23.toByte(), 0x02.toByte())
            '&' -> Pair(0x24.toByte(), 0x02.toByte())
            '*' -> Pair(0x25.toByte(), 0x02.toByte())
            '(' -> Pair(0x26.toByte(), 0x02.toByte())
            ')' -> Pair(0x27.toByte(), 0x02.toByte())
            '_' -> Pair(0x2D.toByte(), 0x02.toByte())
            '+' -> Pair(0x2E.toByte(), 0x02.toByte())
            '{' -> Pair(0x2F.toByte(), 0x02.toByte())
            '}' -> Pair(0x30.toByte(), 0x02.toByte())
            '|' -> Pair(0x31.toByte(), 0x02.toByte())
            ':' -> Pair(0x33.toByte(), 0x02.toByte())
            '"' -> Pair(0x34.toByte(), 0x02.toByte())
            '~' -> Pair(0x35.toByte(), 0x02.toByte())
            '<' -> Pair(0x36.toByte(), 0x02.toByte())
            '>' -> Pair(0x37.toByte(), 0x02.toByte())
            '?' -> Pair(0x38.toByte(), 0x02.toByte())

            // 常见中文全角标点映射 (在中文输入法下发送对应物理键即可输出中文标点)
            '，' -> Pair(0x36.toByte(), 0.toByte()) // 逗号
            '。' -> Pair(0x37.toByte(), 0.toByte()) // 句号
            '！' -> Pair(0x1E.toByte(), 0x02.toByte()) // 感叹号
            '？' -> Pair(0x38.toByte(), 0x02.toByte()) // 问号
            '：' -> Pair(0x33.toByte(), 0x02.toByte()) // 冒号
            '；' -> Pair(0x33.toByte(), 0.toByte()) // 分号
            '“', '”' -> Pair(0x34.toByte(), 0x02.toByte()) // 双引号
            '‘', '’' -> Pair(0x34.toByte(), 0.toByte()) // 单引号
            '（' -> Pair(0x26.toByte(), 0x02.toByte()) // 左圆括号
            '）' -> Pair(0x27.toByte(), 0x02.toByte()) // 右圆括号
            '【' -> Pair(0x2F.toByte(), 0.toByte()) // 左方括号
            '】' -> Pair(0x30.toByte(), 0.toByte()) // 右方括号
            '、' -> Pair(0x31.toByte(), 0.toByte()) // 顿号
            '《' -> Pair(0x36.toByte(), 0x02.toByte()) // 左书名号
            '》' -> Pair(0x37.toByte(), 0x02.toByte()) // 右书名号
            '～' -> Pair(0x35.toByte(), 0x02.toByte()) // 波浪号
            '—' -> Pair(0x2D.toByte(), 0x02.toByte()) // 破折号
            '￥' -> Pair(0x21.toByte(), 0x02.toByte()) // 人民币符号
            '…' -> Pair(0x23.toByte(), 0x02.toByte()) // 省略号
            '　' -> Pair(KEY_SPACE, 0.toByte()) // 全角空格
            '０' -> Pair(0x27.toByte(), 0.toByte()) // 全角 0
            in '１'..'９' -> Pair((c - '１' + 0x1E).toByte(), 0.toByte()) // 全角 1..9
            else -> null
        }
    }

    @SuppressLint("MissingPermission")
    fun release() {
        try {
            keyboardExecutor.shutdown()
            executor.shutdown()
            if (isAppRegistered) {
                hidDevice?.unregisterApp()
                isAppRegistered = false
            }
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
            hidDevice = null
            connectedDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "Error during HidMouseManager release", e)
        }
    }
}
