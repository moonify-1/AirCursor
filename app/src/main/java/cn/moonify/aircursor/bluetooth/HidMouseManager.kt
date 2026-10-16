package cn.moonify.aircursor.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * Manages Bluetooth HID Boot Mouse registration, connection, and report dispatch.
 * Conforms strictly to 4-byte standard Boot Mouse HID descriptor (no Report ID, reportId = 0).
 */
class HidMouseManager(private val context: Context) {

    companion object {
        private const val TAG = "HidMouseManager"

        // Standard 4-byte relative Boot Mouse HID Descriptor (No Report ID)
        val MOUSE_REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), // USAGE_PAGE (Generic Desktop)
            0x09.toByte(), 0x02.toByte(), // USAGE (Mouse)
            0xA1.toByte(), 0x01.toByte(), // COLLECTION (Application)
            0x09.toByte(), 0x01.toByte(), //   USAGE (Pointer)
            0xA1.toByte(), 0x00.toByte(), //   COLLECTION (Physical)
            // Byte 0: 3 Buttons (Left, Right, Middle)
            0x05.toByte(), 0x09.toByte(), //     USAGE_PAGE (Button)
            0x19.toByte(), 0x01.toByte(), //     USAGE_MINIMUM (Button 1)
            0x29.toByte(), 0x03.toByte(), //     USAGE_MAXIMUM (Button 3)
            0x15.toByte(), 0x00.toByte(), //     LOGICAL_MINIMUM (0)
            0x25.toByte(), 0x01.toByte(), //     LOGICAL_MAXIMUM (1)
            0x95.toByte(), 0x03.toByte(), //     REPORT_COUNT (3)
            0x75.toByte(), 0x01.toByte(), //     REPORT_SIZE (1)
            0x81.toByte(), 0x02.toByte(), //     INPUT (Data,Var,Abs)
            // Byte 0: 5-bit padding
            0x95.toByte(), 0x01.toByte(), //     REPORT_COUNT (1)
            0x75.toByte(), 0x05.toByte(), //     REPORT_SIZE (5)
            0x81.toByte(), 0x03.toByte(), //     INPUT (Cnst,Var,Abs)
            // Byte 1 & 2: X, Y relative movement (-127 to 127)
            0x05.toByte(), 0x01.toByte(), //     USAGE_PAGE (Generic Desktop)
            0x09.toByte(), 0x30.toByte(), //     USAGE (X)
            0x09.toByte(), 0x31.toByte(), //     USAGE (Y)
            0x15.toByte(), 0x81.toByte(), //     LOGICAL_MINIMUM (-127)
            0x25.toByte(), 0x7F.toByte(), //     LOGICAL_MAXIMUM (127)
            0x75.toByte(), 0x08.toByte(), //     REPORT_SIZE (8)
            0x95.toByte(), 0x02.toByte(), //     REPORT_COUNT (2)
            0x81.toByte(), 0x06.toByte(), //     INPUT (Data,Var,Rel)
            // Byte 3: Vertical Scroll Wheel (-127 to 127)
            0x09.toByte(), 0x38.toByte(), //     USAGE (Wheel)
            0x15.toByte(), 0x81.toByte(), //     LOGICAL_MINIMUM (-127)
            0x25.toByte(), 0x7F.toByte(), //     LOGICAL_MAXIMUM (127)
            0x75.toByte(), 0x08.toByte(), //     REPORT_SIZE (8)
            0x95.toByte(), 0x01.toByte(), //     REPORT_COUNT (1)
            0x81.toByte(), 0x06.toByte(), //     INPUT (Data,Var,Rel)
            0xC0.toByte(),               //   END_COLLECTION
            0xC0.toByte()                // END_COLLECTION
        )

        const val BUTTON_NONE: Byte = 0x00
        const val BUTTON_LEFT: Byte = 0x01
        const val BUTTON_RIGHT: Byte = 0x02
        const val BUTTON_MIDDLE: Byte = 0x04
    }

    interface Listener {
        fun onHidServiceReady()
        fun onAppRegistered(registered: Boolean)
        fun onConnectionStateChanged(device: BluetoothDevice?, state: Int)
    }

    var listener: Listener? = null

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var hidDevice: BluetoothHidDevice? = null
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    var isAppRegistered = false
        private set

    @Volatile
    var connectedDevice: BluetoothDevice? = null
        private set

    @Volatile
    var connectionState: Int = BluetoothProfile.STATE_DISCONNECTED
        private set

    private var targetDeviceToConnect: BluetoothDevice? = null

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            Log.d(TAG, "onAppStatusChanged: registered=$registered, pluggedDevice=$pluggedDevice")
            isAppRegistered = registered
            listener?.onAppRegistered(registered)

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
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice?.address == device?.address) {
                    connectedDevice = null
                }
            }
            listener?.onConnectionStateChanged(device, state)
        }

        @SuppressLint("MissingPermission")
        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            super.onGetReport(device, type, id, bufferSize)
            Log.d(TAG, "onGetReport: type=$type, id=$id")
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT && id == 0.toByte()) {
                hidDevice?.replyReport(device, type, id, byteArrayOf(0, 0, 0, 0))
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
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            super.onVirtualCableUnplug(device)
            Log.d(TAG, "onVirtualCableUnplug: device=${device?.address}")
            if (connectedDevice?.address == device?.address) {
                connectedDevice = null
                connectionState = BluetoothProfile.STATE_DISCONNECTED
                listener?.onConnectionStateChanged(device, BluetoothProfile.STATE_DISCONNECTED)
            }
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(TAG, "Bluetooth HID Device profile connected")
                hidDevice = proxy as? BluetoothHidDevice
                registerApp()
                listener?.onHidServiceReady()
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

    /**
     * Initializes the Bluetooth HID Device proxy.
     */
    fun init() {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "BluetoothAdapter is null, device does not support Bluetooth")
            return
        }
        bluetoothAdapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
    }

    /**
     * Registers the HID Boot Mouse application with Android Bluetooth subsystem.
     */
    @SuppressLint("MissingPermission")
    fun registerApp() {
        val hid = hidDevice ?: return
        if (isAppRegistered) return

        val sdp = BluetoothHidDeviceAppSdpSettings(
            "AirCursor Mouse",
            "AirCursor Bluetooth Mouse",
            "AirCursor",
            BluetoothHidDevice.SUBCLASS1_MOUSE,
            MOUSE_REPORT_DESCRIPTOR
        )

        try {
            val success = hid.registerApp(sdp, null, null, executor, hidCallback)
            Log.d(TAG, "registerApp invoked, success=$success")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to registerApp", e)
        }
    }

    /**
     * Connects to the host (tablet).
     */
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
            hid.connect(device)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect HID to ${device.address}", e)
        }
    }

    /**
     * Disconnects the current HID connection.
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        val hid = hidDevice ?: return
        val target = connectedDevice ?: return
        try {
            hid.disconnect(target)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect HID", e)
        }
    }

    /**
     * Sends a 4-byte standard mouse report:
     * Byte 0: Button state (bit 0: Left, bit 1: Right, bit 2: Middle)
     * Byte 1: Relative X displacement (-127..127)
     * Byte 2: Relative Y displacement (-127..127)
     * Byte 3: Relative Wheel displacement (-127..127)
     */
    @SuppressLint("MissingPermission")
    fun sendMouseReport(buttonState: Byte, dx: Int, dy: Int, wheel: Int): Boolean {
        val hid = hidDevice ?: return false
        val device = connectedDevice ?: return false

        val clampedDx = dx.coerceIn(-127, 127).toByte()
        val clampedDy = dy.coerceIn(-127, 127).toByte()
        val clampedWheel = wheel.coerceIn(-127, 127).toByte()

        val report = byteArrayOf(buttonState, clampedDx, clampedDy, clampedWheel)
        return try {
            // reportId = 0 since standard Boot Mouse descriptor has no Report ID
            hid.sendReport(device, 0, report)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sendReport", e)
            false
        }
    }

    /**
     * Release resources.
     */
    @SuppressLint("MissingPermission")
    fun release() {
        try {
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
