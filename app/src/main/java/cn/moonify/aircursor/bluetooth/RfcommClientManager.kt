package cn.moonify.aircursor.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Manages the client-side RFCOMM SPP Bluetooth socket connection to the tablet.
 * Sends UTF-8 Chinese strings and special control commands using DataOutputStream.writeUTF().
 *
 * 具备三级重试机制与降级容错：
 * 1. 尝试 Secure RFCOMM Socket
 * 2. 失败后降级尝试 Insecure RFCOMM Socket
 * 3. 失败后降级尝试底层反射通道 (Port 1)
 */
class RfcommClientManager {

    companion object {
        private const val TAG = "RfcommClientManager"

        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2
    }

    interface Listener {
        fun onConnectionStateChanged(state: Int, device: BluetoothDevice?)
        fun onError(message: String)
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

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private val connectExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var dataOutputStream: DataOutputStream? = null

    @Volatile
    var connectedDevice: BluetoothDevice? = null
        private set

    @Volatile
    var connectionState: Int = STATE_DISCONNECTED
        private set

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, silentIfFailed: Boolean = false) {
        if (connectionState == STATE_CONNECTING || connectionState == STATE_CONNECTED) {
            if (connectedDevice?.address == device.address && socket?.isConnected == true) {
                Log.d(TAG, "Already connected to ${device.address}")
                return
            }
            disconnect()
        }

        updateState(STATE_CONNECTING, device)

        connectExecutor.execute {
            var connectedSocket: BluetoothSocket? = null
            var lastException: Exception? = null

            bluetoothAdapter?.cancelDiscovery()

            // 1. 尝试专属 Secure RFCOMM
            try {
                Log.d(TAG, "Trying AirCursor Secure RFCOMM to ${device.address}")
                val s = device.createRfcommSocketToServiceRecord(RfcommConstants.AIRCURSOR_UUID)
                s.connect()
                connectedSocket = s
            } catch (e0: Exception) {
                lastException = e0
                Log.w(TAG, "AirCursor Secure RFCOMM failed: ${e0.message}, trying AirCursor Insecure...")
            }

            // 2. 尝试专属 Insecure RFCOMM
            if (connectedSocket == null) {
                try {
                    val s = device.createInsecureRfcommSocketToServiceRecord(RfcommConstants.AIRCURSOR_UUID)
                    s.connect()
                    connectedSocket = s
                } catch (e00: Exception) {
                    lastException = e00
                    Log.w(TAG, "AirCursor Insecure RFCOMM failed: ${e00.message}, trying Standard SPP...")
                }
            }

            // 3. 尝试标准 Secure RFCOMM
            if (connectedSocket == null) {
                try {
                    Log.d(TAG, "Trying Standard Secure RFCOMM to ${device.address}")
                    val s = device.createRfcommSocketToServiceRecord(RfcommConstants.SPP_UUID)
                    s.connect()
                    connectedSocket = s
                } catch (e1: Exception) {
                    lastException = e1
                    Log.w(TAG, "Standard Secure RFCOMM failed: ${e1.message}, trying Insecure RFCOMM...")
                }
            }

            // 4. 尝试标准 Insecure RFCOMM
            if (connectedSocket == null) {
                try {
                    val s = device.createInsecureRfcommSocketToServiceRecord(RfcommConstants.SPP_UUID)
                    s.connect()
                    connectedSocket = s
                } catch (e2: Exception) {
                    lastException = e2
                    Log.w(TAG, "Insecure RFCOMM failed: ${e2.message}, trying reflection fallback...")
                }
            }

            // 5. 尝试底层反射创建 RFCOMM Channel 1
            if (connectedSocket == null) {
                try {
                    val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    val s = method.invoke(device, 1) as BluetoothSocket
                    s.connect()
                    connectedSocket = s
                } catch (e3: Exception) {
                    lastException = e3
                    Log.w(TAG, "Reflection RFCOMM failed: ${e3.message}")
                }
            }

            if (connectedSocket != null && connectedSocket.isConnected) {
                socket = connectedSocket
                dataOutputStream = DataOutputStream(connectedSocket.outputStream)
                connectedDevice = device
                updateState(STATE_CONNECTED, device)
                Log.d(TAG, "RFCOMM connected successfully to ${device.address}")
            } else {
                Log.e(TAG, "All RFCOMM connection attempts failed to ${device.address}", lastException)
                closeInternal()
                updateState(STATE_DISCONNECTED, null)
                if (!silentIfFailed) {
                    notifyError("串口连接未就绪 (平板端未开启 AirCursor 服务)")
                }
            }
        }
    }

    fun sendText(text: String) {
        val dos = dataOutputStream
        if (connectionState != STATE_CONNECTED || dos == null) {
            Log.w(TAG, "Cannot send text, RFCOMM not connected")
            return
        }

        sendExecutor.execute {
            try {
                dos.writeUTF(text)
                dos.flush()
                Log.d(TAG, "Sent text via RFCOMM: $text")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send text, socket might be disconnected", e)
                disconnect()
                notifyError("发送失败，连接已断开: ${e.message}")
            }
        }
    }

    fun sendCommand(command: String) {
        sendText(command)
    }

    fun disconnect() {
        connectExecutor.execute {
            closeInternal()
            updateState(STATE_DISCONNECTED, null)
        }
    }

    private fun closeInternal() {
        try {
            dataOutputStream?.close()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}

        dataOutputStream = null
        socket = null
        connectedDevice = null
    }

    private fun updateState(newState: Int, device: BluetoothDevice?) {
        connectionState = newState
        for (l in listeners) {
            try {
                l.onConnectionStateChanged(newState, device)
            } catch (e: Exception) {
                Log.e(TAG, "Listener onConnectionStateChanged error", e)
            }
        }
    }

    private fun notifyError(message: String) {
        for (l in listeners) {
            try {
                l.onError(message)
            } catch (e: Exception) {
                Log.e(TAG, "Listener onError error", e)
            }
        }
    }

    fun release() {
        disconnect()
        sendExecutor.shutdown()
        connectExecutor.shutdown()
    }
}
