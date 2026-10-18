package cn.moonify.aircursor.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import cn.moonify.aircursor.service.AirCursorService

/**
 * 集中管理蓝牙 HID 鼠标键盘与 RFCOMM 串口连接的单例管理器。
 *
 * 核心特性：
 * 1. 后台连接保持：配合 AirCursorService 前台服务，软件退至后台或 Activity 销毁时连接不中断。
 * 2. 目标设备记忆：持久化保存上次成功连接的目标设备地址与名称。
 * 3. 智能断线重连：在非用户主动断开的情况下，自动按阶梯间隔尝试重连最多 5 次，直到确认连接不上。
 * 4. 打开自动重连：每次启动应用时，若已获得权限且蓝牙可用，自动尝试连接上次连接成功的设备。
 */
class AirCursorConnectionManager private constructor() {

    companion object {
        private const val TAG = "AirCursorConnMgr"
        private const val PREFS_NAME = "aircursor_prefs"
        const val KEY_LAST_DEVICE_ADDR = "last_connected_device_addr"
        const val KEY_LAST_DEVICE_NAME = "last_connected_device_name"

        const val MAX_RECONNECT_ATTEMPTS = 5
        private val RECONNECT_DELAYS_MS = longArrayOf(1000L, 2000L, 3000L, 4000L, 5000L)
        private const val RECONNECT_WATCHDOG_TIMEOUT_MS = 7000L

        val instance: AirCursorConnectionManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            AirCursorConnectionManager()
        }
    }

    interface Listener {
        fun onStateChanged()
        fun onReconnectProgress(attempt: Int, maxAttempts: Int, message: String)
        fun onReconnectFailed(device: BluetoothDevice?)
        fun onError(message: String)
    }

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Listener>()

    lateinit var hidMouseManager: HidMouseManager
        private set

    val rfcommClientManager = RfcommClientManager()

    @Volatile
    var isInitialized: Boolean = false
        private set

    @Volatile
    var targetDevice: BluetoothDevice? = null
        private set

    @Volatile
    var isManualDisconnect: Boolean = false
        private set

    @Volatile
    var isReconnecting: Boolean = false
        private set

    @Volatile
    var reconnectAttempt: Int = 0
        private set

    @Volatile
    var isConfirmedUnreachable: Boolean = false
        private set

    private var reconnectRunnable: Runnable? = null
    private var watchdogRunnable: Runnable? = null

    fun init(context: Context) {
        if (isInitialized) return
        val app = context.applicationContext
        appContext = app
        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        hidMouseManager = HidMouseManager(app)

        hidMouseManager.addListener(object : HidMouseManager.Listener {
            override fun onHidServiceReady() {
                notifyStateChanged()
            }

            override fun onAppRegistered(registered: Boolean) {
                notifyStateChanged()
            }

            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                Log.d(TAG, "HID onConnectionStateChanged: device=${device?.address}, state=$state")
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    onDeviceConnected(device)
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    onDeviceDisconnected(device, isFromHid = true)
                }
                notifyStateChanged()
            }
        })

        rfcommClientManager.addListener(object : RfcommClientManager.Listener {
            override fun onConnectionStateChanged(state: Int, device: BluetoothDevice?) {
                Log.d(TAG, "RFCOMM onConnectionStateChanged: device=${device?.address}, state=$state")
                if (state == RfcommClientManager.STATE_CONNECTED) {
                    onDeviceConnected(device)
                } else if (state == RfcommClientManager.STATE_DISCONNECTED) {
                    onDeviceDisconnected(device, isFromHid = false)
                }
                notifyStateChanged()
            }

            override fun onError(message: String) {
                notifyError(message)
            }
        })

        hidMouseManager.init()
        isInitialized = true
        Log.d(TAG, "AirCursorConnectionManager initialized")
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    private fun notifyStateChanged() {
        mainHandler.post {
            synchronized(listeners) {
                for (listener in listeners) {
                    try {
                        listener.onStateChanged()
                    } catch (e: Exception) {
                        Log.e(TAG, "Listener onStateChanged error", e)
                    }
                }
            }
        }
    }

    private fun notifyReconnectProgress(attempt: Int, maxAttempts: Int, message: String) {
        mainHandler.post {
            synchronized(listeners) {
                for (listener in listeners) {
                    try {
                        listener.onReconnectProgress(attempt, maxAttempts, message)
                    } catch (e: Exception) {
                        Log.e(TAG, "Listener onReconnectProgress error", e)
                    }
                }
            }
        }
    }

    private fun notifyReconnectFailed(device: BluetoothDevice?) {
        mainHandler.post {
            synchronized(listeners) {
                for (listener in listeners) {
                    try {
                        listener.onReconnectFailed(device)
                    } catch (e: Exception) {
                        Log.e(TAG, "Listener onReconnectFailed error", e)
                    }
                }
            }
        }
    }

    private fun notifyError(message: String) {
        mainHandler.post {
            synchronized(listeners) {
                for (listener in listeners) {
                    try {
                        listener.onError(message)
                    } catch (e: Exception) {
                        Log.e(TAG, "Listener onError error", e)
                    }
                }
            }
        }
    }

    fun isConnected(): Boolean {
        if (!isInitialized) return false
        return hidMouseManager.connectionState == BluetoothProfile.STATE_CONNECTED ||
                rfcommClientManager.connectionState == RfcommClientManager.STATE_CONNECTED
    }

    fun isConnecting(): Boolean {
        if (!isInitialized) return false
        return hidMouseManager.connectionState == BluetoothProfile.STATE_CONNECTING ||
                hidMouseManager.targetDeviceToConnect != null ||
                rfcommClientManager.connectionState == RfcommClientManager.STATE_CONNECTING
    }

    fun isConnectedOrConnecting(): Boolean {
        return isConnected() || isConnecting() || isReconnecting
    }

    fun getActiveDevice(): BluetoothDevice? {
        if (!isInitialized) return null
        return hidMouseManager.connectedDevice
            ?: rfcommClientManager.connectedDevice
            ?: targetDevice
            ?: getLastSavedDevice()
    }

    fun getLastConnectedAddress(): String? {
        return prefs?.getString(KEY_LAST_DEVICE_ADDR, null)
    }

    fun getLastConnectedName(): String? {
        return prefs?.getString(KEY_LAST_DEVICE_NAME, null)
    }

    fun getLastSavedDevice(): BluetoothDevice? {
        val addr = getLastConnectedAddress() ?: return null
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        return try {
            adapter.getRemoteDevice(addr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get remote device for $addr", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun saveConnectedDevice(device: BluetoothDevice) {
        targetDevice = device
        val p = prefs ?: return
        val name = try { device.name } catch (_: Exception) { null } ?: ""
        p.edit()
            .putString(KEY_LAST_DEVICE_ADDR, device.address)
            .putString(KEY_LAST_DEVICE_NAME, name)
            .commit()
        Log.d(TAG, "Persisted last connected device: ${device.address} ($name)")
    }

    private fun onDeviceConnected(device: BluetoothDevice?) {
        val current = device ?: targetDevice ?: return
        Log.d(TAG, "onDeviceConnected: ${current.address}")
        isManualDisconnect = false
        isReconnecting = false
        reconnectAttempt = 0
        isConfirmedUnreachable = false
        cancelPendingReconnect()

        saveConnectedDevice(current)

        appContext?.let {
            AirCursorService.start(it)
        }
    }

    private fun onDeviceDisconnected(device: BluetoothDevice?, isFromHid: Boolean) {
        if (!isFromHid) {
            // RFCOMM 仅为辅助输入法通道。若 HID 仍然处于已连接、正在连接或等待 SDP 注册就绪状态，
            // 绝不能将整体连接标记为断开，直接忽略 RFCOMM 断开事件！
            if (hidMouseManager.connectionState == BluetoothProfile.STATE_CONNECTED ||
                hidMouseManager.connectionState == BluetoothProfile.STATE_CONNECTING ||
                hidMouseManager.targetDeviceToConnect != null) {
                Log.d(TAG, "RFCOMM disconnected but HID is active/connecting (${hidMouseManager.connectionState}), ignoring.")
                return
            }
        }

        // 若 HID 仍处于连接中，则整体连接仍然有效
        if (hidMouseManager.connectionState == BluetoothProfile.STATE_CONNECTED) {
            return
        }

        if (isManualDisconnect) {
            Log.d(TAG, "Manual disconnect detected, skip auto-reconnect")
            stopReconnecting()
            appContext?.let { AirCursorService.stop(it) }
            return
        }

        // 被动断开：触发断线自动重试逻辑
        handleUnexpectedDisconnect(device)
    }

    private fun handleUnexpectedDisconnect(device: BluetoothDevice?) {
        val target = device ?: targetDevice ?: getLastSavedDevice()
        if (target == null) {
            Log.w(TAG, "Unexpected disconnect but no target device known")
            return
        }

        if (isReconnecting) {
            Log.d(TAG, "Already reconnecting, scheduling next attempt for ${target.address}")
            scheduleNextReconnect(target)
            return
        }

        Log.w(TAG, "Unexpected disconnection from ${target.address}. Starting auto-reconnect flow...")
        isReconnecting = true
        reconnectAttempt = 0
        isConfirmedUnreachable = false

        appContext?.let { AirCursorService.start(it) }
        scheduleNextReconnect(target)
    }

    private fun scheduleNextReconnect(device: BluetoothDevice) {
        if (isManualDisconnect || isConnected()) {
            stopReconnecting()
            return
        }

        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            isReconnecting = false
            isConfirmedUnreachable = true
            cancelPendingReconnect()
            Log.w(TAG, "Reached max reconnect attempts ($MAX_RECONNECT_ATTEMPTS). Confirmed unreachable.")
            notifyReconnectFailed(device)
            notifyStateChanged()
            return
        }

        val delay = RECONNECT_DELAYS_MS.getOrElse(reconnectAttempt) { 5000L }
        val attemptNumber = reconnectAttempt + 1

        cancelPendingReconnect()

        val msg = "正在尝试重新连接 (${attemptNumber}/$MAX_RECONNECT_ATTEMPTS)..."
        Log.d(TAG, "Scheduling reconnect #$attemptNumber in ${delay}ms: $msg")
        notifyReconnectProgress(attemptNumber, MAX_RECONNECT_ATTEMPTS, msg)
        notifyStateChanged()

        reconnectRunnable = Runnable {
            if (isManualDisconnect || isConnected()) {
                stopReconnecting()
                return@Runnable
            }

            reconnectAttempt++
            targetDevice = device
            Log.d(TAG, "Executing reconnect attempt #$reconnectAttempt to ${device.address}")

            appContext?.let { AirCursorService.start(it) }

            // 执行 HID 与 RFCOMM 重连
            hidMouseManager.connect(device)
            rfcommClientManager.connect(device, silentIfFailed = hidMouseManager.isComboMode)
            notifyStateChanged()

            // 监视器：若在指定超时时间内未能建立成功连接且仍在重试，自动触发下一轮重试
            watchdogRunnable = Runnable {
                if (!isConnected() && isReconnecting && !isManualDisconnect) {
                    Log.d(TAG, "Watchdog: attempt #$reconnectAttempt timed out, advancing to next attempt...")
                    scheduleNextReconnect(device)
                }
            }
            mainHandler.postDelayed(watchdogRunnable!!, RECONNECT_WATCHDOG_TIMEOUT_MS)
        }

        mainHandler.postDelayed(reconnectRunnable!!, delay)
    }

    private fun cancelPendingReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun stopReconnecting() {
        isReconnecting = false
        reconnectAttempt = 0
        cancelPendingReconnect()
    }

    /**
     * 连接到指定设备
     */
    fun connect(device: BluetoothDevice) {
        isManualDisconnect = false
        isConfirmedUnreachable = false
        targetDevice = device
        saveConnectedDevice(device)
        cancelPendingReconnect()
        isReconnecting = false
        reconnectAttempt = 0

        appContext?.let { AirCursorService.start(it) }

        hidMouseManager.connect(device)
        rfcommClientManager.connect(device, silentIfFailed = hidMouseManager.isComboMode)

        notifyStateChanged()
    }

    /**
     * 每次软件打开时，自动尝试连接上次已连接成功的设备
     */
    fun autoConnectLastDevice(context: Context) {
        if (isConnected() || isConnecting() || isReconnecting) {
            Log.d(TAG, "Device already connected or connecting, skipping auto-connect")
            return
        }

        val lastAddress = getLastConnectedAddress()
        if (lastAddress.isNullOrEmpty()) {
            Log.d(TAG, "No previously connected device recorded")
            return
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available or not enabled, skipping auto-connect")
            return
        }

        val device = try {
            adapter.getRemoteDevice(lastAddress)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to getRemoteDevice for $lastAddress", e)
            null
        } ?: return

        val deviceName = try { device.name } catch (_: Exception) { null } ?: lastAddress
        Log.d(TAG, "Auto-connecting to last device: $deviceName ($lastAddress)")
        Toast.makeText(context, "正在尝试连接上次设备: $deviceName...", Toast.LENGTH_SHORT).show()

        connect(device)
    }

    /**
     * 发送系统返回键指令至平板（同时通过 HID Consumer Control / 鼠标侧键与 RFCOMM SPP 双通道）
     */
    fun sendSystemBack() {
        if (!isInitialized) return
        try {
            if (hidMouseManager.connectionState == BluetoothProfile.STATE_CONNECTED) {
                hidMouseManager.sendBack()
            }
            if (rfcommClientManager.connectionState == RfcommClientManager.STATE_CONNECTED) {
                rfcommClientManager.sendCommand(RfcommConstants.CMD_BACK)
            }
            Log.d(TAG, "sendSystemBack dispatched successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in sendSystemBack", e)
        }
    }

    /**
     * 断开所有连接
     */
    fun disconnectAll(manual: Boolean = true) {
        if (manual) {
            isManualDisconnect = true
        }
        stopReconnecting()
        isConfirmedUnreachable = false

        if (isInitialized) {
            hidMouseManager.disconnect()
            rfcommClientManager.disconnect()
        }

        if (manual) {
            appContext?.let { AirCursorService.stop(it) }
        }

        notifyStateChanged()
    }

    fun release() {
        disconnectAll(manual = true)
        if (isInitialized) {
            hidMouseManager.release()
            rfcommClientManager.release()
            isInitialized = false
        }
    }
}
