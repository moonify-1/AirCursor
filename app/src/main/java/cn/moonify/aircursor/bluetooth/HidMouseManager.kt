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
 * 支持两种模式：
 * 1. 外接键盘 + 鼠标复合模式 (Combo Mode，默认推荐)：
 *    - 向平板注册为 SUBCLASS1_COMBO，系统识别为实体外接物理键盘与鼠标。
 *    - 平板保留默认 vivo 输入法时，点击输入栏不会弹出软键盘，而是自动唤起 vivo 物理键盘专属候选栏与悬浮输入条，光标正常显示与闪烁！
 * 2. 纯标准鼠标模式 (Pure Boot Mouse Mode)：
 *    - 仅注册为 SUBCLASS1_MOUSE（4 字节标准鼠标报文，无 Report ID）。
 */
class HidMouseManager(private val context: Context) {

    companion object {
        private const val TAG = "HidMouseManager"

        const val REPORT_ID_KEYBOARD: Byte = 1
        const val REPORT_ID_MOUSE: Byte = 2

        // ==================== 1. 标准复合设备描述符 (键盘 + 鼠标) ====================
        val COMBO_REPORT_DESCRIPTOR = byteArrayOf(
            // ---------- 键盘 (Report ID 1, 8 字节标准报文) ----------
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
            0x81.toByte(), 0x02.toByte(), //   INPUT (Data,Var,Abs) - Modifier 按键掩码
            0x95.toByte(), 0x01.toByte(), //   REPORT_COUNT (1)
            0x75.toByte(), 0x08.toByte(), //   REPORT_SIZE (8)
            0x81.toByte(), 0x01.toByte(), //   INPUT (Cnst,Ary,Abs) - 保留字节
            0x95.toByte(), 0x05.toByte(), //   REPORT_COUNT (5)
            0x75.toByte(), 0x01.toByte(), //   REPORT_SIZE (1)
            0x05.toByte(), 0x08.toByte(), //   USAGE_PAGE (LEDs)
            0x19.toByte(), 0x01.toByte(), //   USAGE_MINIMUM (Num Lock)
            0x29.toByte(), 0x05.toByte(), //   USAGE_MAXIMUM (Kana)
            0x91.toByte(), 0x02.toByte(), //   OUTPUT (Data,Var,Abs) - LED 状态
            0x95.toByte(), 0x01.toByte(), //   REPORT_COUNT (1)
            0x75.toByte(), 0x03.toByte(), //   REPORT_SIZE (3)
            0x91.toByte(), 0x01.toByte(), //   OUTPUT (Cnst,Ary,Abs) - LED 补齐位
            0x95.toByte(), 0x06.toByte(), //   REPORT_COUNT (6)
            0x75.toByte(), 0x08.toByte(), //   REPORT_SIZE (8)
            0x15.toByte(), 0x00.toByte(), //   LOGICAL_MINIMUM (0)
            0x25.toByte(), 0x65.toByte(), //   LOGICAL_MAXIMUM (101)
            0x05.toByte(), 0x07.toByte(), //   USAGE_PAGE (Keyboard/Keypad)
            0x19.toByte(), 0x00.toByte(), //   USAGE_MINIMUM (0)
            0x29.toByte(), 0x65.toByte(), //   USAGE_MAXIMUM (101)
            0x81.toByte(), 0x00.toByte(), //   INPUT (Data,Ary,Abs) - 6 个并发按键码
            0xC0.toByte(),               // END_COLLECTION

            // ---------- 鼠标 (Report ID 2, 4 字节标准相对报文) ----------
            0x05.toByte(), 0x01.toByte(), // USAGE_PAGE (Generic Desktop)
            0x09.toByte(), 0x02.toByte(), // USAGE (Mouse)
            0xA1.toByte(), 0x01.toByte(), // COLLECTION (Application)
            0x85.toByte(), 0x02.toByte(), //   REPORT_ID (2)
            0x09.toByte(), 0x01.toByte(), //   USAGE (Pointer)
            0xA1.toByte(), 0x00.toByte(), //   COLLECTION (Physical)
            0x05.toByte(), 0x09.toByte(), //     USAGE_PAGE (Button)
            0x19.toByte(), 0x01.toByte(), //     USAGE_MINIMUM (Button 1)
            0x29.toByte(), 0x03.toByte(), //     USAGE_MAXIMUM (Button 3)
            0x15.toByte(), 0x00.toByte(), //     LOGICAL_MINIMUM (0)
            0x25.toByte(), 0x01.toByte(), //     LOGICAL_MAXIMUM (1)
            0x95.toByte(), 0x03.toByte(), //     REPORT_COUNT (3)
            0x75.toByte(), 0x01.toByte(), //     REPORT_SIZE (1)
            0x81.toByte(), 0x02.toByte(), //     INPUT (Data,Var,Abs)
            0x95.toByte(), 0x01.toByte(), //     REPORT_COUNT (1)
            0x75.toByte(), 0x05.toByte(), //     REPORT_SIZE (5)
            0x81.toByte(), 0x03.toByte(), //     INPUT (Cnst,Var,Abs)
            0x05.toByte(), 0x01.toByte(), //     USAGE_PAGE (Generic Desktop)
            0x09.toByte(), 0x30.toByte(), //     USAGE (X)
            0x09.toByte(), 0x31.toByte(), //     USAGE (Y)
            0x15.toByte(), 0x81.toByte(), //     LOGICAL_MINIMUM (-127)
            0x25.toByte(), 0x7F.toByte(), //     LOGICAL_MAXIMUM (127)
            0x75.toByte(), 0x08.toByte(), //     REPORT_SIZE (8)
            0x95.toByte(), 0x02.toByte(), //     REPORT_COUNT (2)
            0x81.toByte(), 0x06.toByte(), //     INPUT (Data,Var,Rel)
            0x09.toByte(), 0x38.toByte(), //     USAGE (Wheel)
            0x15.toByte(), 0x81.toByte(), //     LOGICAL_MINIMUM (-127)
            0x25.toByte(), 0x7F.toByte(), //     LOGICAL_MAXIMUM (127)
            0x75.toByte(), 0x08.toByte(), //     REPORT_SIZE (8)
            0x95.toByte(), 0x01.toByte(), //     REPORT_COUNT (1)
            0x81.toByte(), 0x06.toByte(), //     INPUT (Data,Var,Rel)
            0xC0.toByte(),               //   END_COLLECTION
            0xC0.toByte()                // END_COLLECTION
        )

        // ==================== 2. 纯 Boot Mouse 描述符 (无 Report ID) ====================
        val PURE_MOUSE_REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(),
            0x09.toByte(), 0x02.toByte(),
            0xA1.toByte(), 0x01.toByte(),
            0x09.toByte(), 0x01.toByte(),
            0xA1.toByte(), 0x00.toByte(),
            0x05.toByte(), 0x09.toByte(),
            0x19.toByte(), 0x01.toByte(),
            0x29.toByte(), 0x03.toByte(),
            0x15.toByte(), 0x00.toByte(),
            0x25.toByte(), 0x01.toByte(),
            0x95.toByte(), 0x03.toByte(),
            0x75.toByte(), 0x01.toByte(),
            0x81.toByte(), 0x02.toByte(),
            0x95.toByte(), 0x01.toByte(),
            0x75.toByte(), 0x05.toByte(),
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

        const val BUTTON_NONE: Byte = 0x00
        const val BUTTON_LEFT: Byte = 0x01
        const val BUTTON_RIGHT: Byte = 0x02
        const val BUTTON_MIDDLE: Byte = 0x04

        // 常用 HID 按键码 (Usage Page 0x07)
        const val KEY_ENTER: Byte = 0x28
        const val KEY_ESCAPE: Byte = 0x29
        const val KEY_BACKSPACE: Byte = 0x2A
        const val KEY_TAB: Byte = 0x2B
        const val KEY_SPACE: Byte = 0x2C
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

    // 默认从本地持久化配置读取，未配置时使用纯标准 Boot 鼠标模式（最稳定、无延迟）
    var isComboMode: Boolean = try {
        context.getSharedPreferences("aircursor_prefs", Context.MODE_PRIVATE).getBoolean("combo_mode", false)
    } catch (_: Exception) {
        false
    }

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
            Log.d(TAG, "onAppStatusChanged: registered=$registered, pluggedDevice=$pluggedDevice")
            isAppRegistered = registered
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
            Log.d(TAG, "onGetReport: type=$type, id=$id, protocol=$currentProtocol, combo=$isComboMode")
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                if (isComboMode && currentProtocol == BluetoothHidDevice.PROTOCOL_REPORT_MODE) {
                    if (id == REPORT_ID_KEYBOARD) {
                        hidDevice?.replyReport(device, type, id, ByteArray(8))
                    } else if (id == REPORT_ID_MOUSE) {
                        hidDevice?.replyReport(device, type, id, ByteArray(4))
                    } else {
                        hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
                    }
                } else {
                    if (id == 0.toByte()) {
                        hidDevice?.replyReport(device, type, id, ByteArray(4))
                    } else {
                        hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
                    }
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
     * 根据当前 isComboMode 模式注册 SDP 记录与 HID 描述符。
     */
    @SuppressLint("MissingPermission")
    fun registerApp() {
        val hid = hidDevice ?: return
        if (isAppRegistered) return

        val sdp = if (isComboMode) {
            BluetoothHidDeviceAppSdpSettings(
                "AirCursor Combo",
                "AirCursor Keyboard & Mouse",
                "AirCursor",
                BluetoothHidDevice.SUBCLASS1_COMBO, // 0xC0: 复合设备 (键盘 + 鼠标)
                COMBO_REPORT_DESCRIPTOR
            )
        } else {
            BluetoothHidDeviceAppSdpSettings(
                "AirCursor Mouse",
                "AirCursor Boot Mouse",
                "AirCursor",
                BluetoothHidDevice.SUBCLASS1_MOUSE, // 0x80: 纯鼠标
                PURE_MOUSE_REPORT_DESCRIPTOR
            )
        }

        try {
            val success = hid.registerApp(sdp, null, null, executor, hidCallback)
            Log.d(TAG, "registerApp invoked (combo=$isComboMode), success=$success")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to registerApp", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun switchMode(comboMode: Boolean) {
        if (isComboMode == comboMode) return
        isComboMode = comboMode
        try {
            context.getSharedPreferences("aircursor_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("combo_mode", comboMode).apply()
        } catch (_: Exception) {}
        currentProtocol = BluetoothHidDevice.PROTOCOL_REPORT_MODE
        if (isAppRegistered) {
            try {
                hidDevice?.unregisterApp()
                isAppRegistered = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregisterApp during mode switch", e)
            }
            registerApp()
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val hid = hidDevice
        if (hid == null || !isAppRegistered) {
            Log.d(TAG, "HID device not ready or not registered yet, deferring connection to ${device.address}")
            targetDeviceToConnect = device
            if (hid != null && !isAppRegistered) {
                registerApp()
            }
            return
        }

        try {
            Log.d(TAG, "Connecting HID to ${device.address}")
            connectionState = BluetoothProfile.STATE_CONNECTING
            notifyConnectionStateChanged(device, BluetoothProfile.STATE_CONNECTING)
            hid.connect(device)
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
     * - 当处于 Combo 模式且 Host 协商为 REPORT_MODE 时使用 REPORT_ID_MOUSE (2)
     * - 纯标准 Boot 鼠标模式（默认）或 Host 为 BOOT_MODE 时严格使用 reportId = 0，发送标准 4 字节报文
     */
    @SuppressLint("MissingPermission")
    fun sendMouseReport(buttonState: Byte, dx: Int, dy: Int, wheel: Int): Boolean {
        val hid = hidDevice ?: return false
        val device = connectedDevice ?: return false

        val clampedDx = dx.coerceIn(-127, 127).toByte()
        val clampedDy = dy.coerceIn(-127, 127).toByte()
        val clampedWheel = wheel.coerceIn(-127, 127).toByte()

        val report = byteArrayOf(buttonState, clampedDx, clampedDy, clampedWheel)
        // 核心防错位逻辑：
        // 只有当明确开启 Combo 模式且主机未要求 BOOT_MODE 时才带 Report ID 2；
        // 否则一律发送 reportId = 0，确保平板按照 [button, X, Y, Wheel] 顺序解析，彻底根除坐标轴颠倒和滑轮乱窜！
        val reportId = if (isComboMode && currentProtocol == BluetoothHidDevice.PROTOCOL_REPORT_MODE) {
            REPORT_ID_MOUSE.toInt()
        } else {
            0
        }

        return try {
            hid.sendReport(device, reportId, report)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sendMouseReport", e)
            false
        }
    }

    /**
     * 底层同步发送单个按键按下与释放报文（必须在单线程队列 keyboardExecutor 中执行，确保严格时序）：
     */
    @SuppressLint("MissingPermission")
    private fun sendKeySync(keycode: Byte, modifier: Byte = 0) {
        val hid = hidDevice ?: return
        val device = connectedDevice ?: return
        try {
            // Key Down
            val press = byteArrayOf(modifier, 0, keycode, 0, 0, 0, 0, 0)
            hid.sendReport(device, REPORT_ID_KEYBOARD.toInt(), press)
            Thread.sleep(12)
            // Key Up
            val release = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
            hid.sendReport(device, REPORT_ID_KEYBOARD.toInt(), release)
            Thread.sleep(12)
        } catch (e: Exception) {
            Log.e(TAG, "Error in sendKeySync $keycode", e)
        }
    }

    /**
     * 发送实体键盘按键报文 (按下后自动释放)：
     * 仅在 Combo 模式下有效，由专用单线程队列按序发送
     */
    fun sendKeyboardKey(keycode: Byte, modifier: Byte = 0) {
        if (!isComboMode) return
        keyboardExecutor.execute {
            sendKeySync(keycode, modifier)
        }
    }

    /**
     * 发送系统后台应用选择/多任务切换快捷指令 (Meta/Win + Tab)，回到平板后台多任务界面
     */
    fun sendRecentApps() {
        if (!isComboMode) return
        keyboardExecutor.execute {
            val hid = hidDevice ?: return@execute
            val device = connectedDevice ?: return@execute
            try {
                // 1. Meta (Win) 按下 (0x08 为 Left GUI / Win 键)
                val metaDown = byteArrayOf(0x08.toByte(), 0, 0, 0, 0, 0, 0, 0)
                hid.sendReport(device, REPORT_ID_KEYBOARD.toInt(), metaDown)
                Thread.sleep(15)

                // 2. Meta + Tab 同时按下 (触发 Android PhoneWindowManager.toggleRecentApps)
                val tabDown = byteArrayOf(0x08.toByte(), 0, KEY_TAB, 0, 0, 0, 0, 0)
                hid.sendReport(device, REPORT_ID_KEYBOARD.toInt(), tabDown)
                Thread.sleep(35)

                // 3. Tab 释放 (保持 Meta)
                hid.sendReport(device, REPORT_ID_KEYBOARD.toInt(), metaDown)
                Thread.sleep(15)

                // 4. 全部释放
                val releaseAll = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
                hid.sendReport(device, REPORT_ID_KEYBOARD.toInt(), releaseAll)
                Thread.sleep(15)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending recent apps shortcut", e)
            }
        }
    }

    /**
     * 将 ASCII 字符串转换为 HID 按键序列发送给平板（供 vivo 物理键盘拼音候选栏输入）
     */
    fun sendKeyboardText(text: String) {
        if (!isComboMode) return
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
        if (!isComboMode) return
        keyboardExecutor.execute {
            val actions = PinyinConverter.convertTextToKeystrokeActions(text)
            Log.d(TAG, "sendChineseTextAsPinyin: text='$text', generated ${actions.size} actions")
            for (action in actions) {
                when (action) {
                    is PinyinConverter.KeystrokeAction.PinyinSequence -> {
                        for (c in action.pinyin) {
                            val mapping = charToKeycode(c)
                            if (mapping != null) {
                                sendKeySync(mapping.first, mapping.second)
                            }
                        }
                        // 关键等待：给平板输入法核心引擎留出充分的拼音分词、候选词检索与候选悬浮窗渲染时间（80ms）
                        try {
                            Thread.sleep(80)
                        } catch (_: InterruptedException) {
                            return@execute
                        }

                        // 发送空格键，提交确认平板候选栏首位汉字候选词上屏
                        sendKeySync(KEY_SPACE, 0)

                        // 关键等待：等待平板输入法完成 InputConnection.commitText() 并重置候选缓冲区（50ms）
                        try {
                            Thread.sleep(50)
                        } catch (_: InterruptedException) {
                            return@execute
                        }
                    }
                    is PinyinConverter.KeystrokeAction.DirectChar -> {
                        val mapping = charToKeycode(action.char)
                        if (mapping != null) {
                            sendKeySync(mapping.first, mapping.second)
                            try {
                                Thread.sleep(20)
                            } catch (_: InterruptedException) {
                                return@execute
                            }
                        }
                    }
                }
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
