package cn.moonify.aircursor.ime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import cn.moonify.aircursor.bluetooth.RfcommConstants
import java.io.DataInputStream
import java.io.IOException

/**
 * Tablet Receiver: Zero-height, invisible InputMethodService.
 * Listens for RFCOMM SPP connections from the phone and commits incoming UTF-8 text/commands
 * directly into the focused input field without showing any on-screen keyboard.
 */
class RemoteImeService : InputMethodService() {

    companion object {
        private const val TAG = "RemoteImeService"
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isRunning = false

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    @Volatile
    private var clientSocket: BluetoothSocket? = null

    private var serverThread: Thread? = null

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onEvaluateInputViewShown(): Boolean = false

    override fun onCreateInputView(): View {
        return View(this).apply {
            layoutParams = ViewGroup.LayoutParams(0, 0)
            visibility = View.GONE
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "RemoteImeService onCreate, starting RFCOMM server thread")
        startRfcommServer()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "RemoteImeService onDestroy, stopping RFCOMM server")
        stopRfcommServer()
    }

    @SuppressLint("MissingPermission")
    private fun startRfcommServer() {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported on this device")
            return
        }

        isRunning = true
        serverThread = Thread({
            while (isRunning) {
                var ss: BluetoothServerSocket? = null
                try {
                    Log.d(TAG, "Listening for RFCOMM SPP with UUID: ${RfcommConstants.SPP_UUID}")
                    ss = bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                        RfcommConstants.SERVICE_NAME,
                        RfcommConstants.SPP_UUID
                    )
                    serverSocket = ss

                    // Accept loop: accepts connections and reconnects if dropped
                    while (isRunning) {
                        Log.d(TAG, "Waiting for client connection...")
                        val socket = try {
                            ss.accept()
                        } catch (e: IOException) {
                            if (!isRunning) {
                                Log.d(TAG, "Server socket closed intentionally")
                            } else {
                                Log.e(TAG, "Server accept error", e)
                            }
                            break
                        }

                        Log.d(TAG, "Accepted client connection from ${socket.remoteDevice?.address}")
                        clientSocket = socket
                        handleClient(socket)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in RFCOMM server loop", e)
                } finally {
                    try {
                        ss?.close()
                    } catch (_: Exception) {}
                    serverSocket = null
                }

                // If still running after loop termination (e.g., unexpected error), wait briefly and retry
                if (isRunning) {
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }, "AirCursor-RFCOMM-Server").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleClient(socket: BluetoothSocket) {
        var dis: DataInputStream? = null
        try {
            dis = DataInputStream(socket.inputStream)
            while (isRunning && socket.isConnected) {
                val message = dis.readUTF()
                Log.d(TAG, "Received message: $message")
                mainHandler.post {
                    handleIncomingCommand(message)
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Client connection terminated: ${e.message}")
        } finally {
            try {
                dis?.close()
            } catch (_: Exception) {}
            try {
                socket.close()
            } catch (_: Exception) {}
            if (clientSocket == socket) {
                clientSocket = null
            }
        }
    }

    /**
     * Dispatches commands or inserts UTF-8 characters to the currently focused input field.
     */
    private fun handleIncomingCommand(text: String) {
        val ic = currentInputConnection
        if (ic == null) {
            Log.w(TAG, "currentInputConnection is null, target app has no active focus")
            return
        }

        when (text) {
            RfcommConstants.CMD_BACKSPACE -> {
                val selected = ic.getSelectedText(0)
                if (!selected.isNullOrEmpty()) {
                    // Replace selected text with empty string
                    ic.commitText("", 1)
                } else {
                    // Delete 1 character before cursor
                    ic.deleteSurroundingText(1, 0)
                }
            }
            RfcommConstants.CMD_ENTER -> {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
            RfcommConstants.CMD_SPACE -> {
                ic.commitText(" ", 1)
            }
            else -> {
                // Direct UTF-8 Chinese characters / text commit
                ic.commitText(text, 1)
            }
        }
    }

    private fun stopRfcommServer() {
        isRunning = false
        try {
            clientSocket?.close()
        } catch (_: Exception) {}
        try {
            serverSocket?.close()
        } catch (_: Exception) {}

        serverThread?.interrupt()
        serverThread = null
        clientSocket = null
        serverSocket = null
    }
}
