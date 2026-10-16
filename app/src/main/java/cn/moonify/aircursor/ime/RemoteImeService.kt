package cn.moonify.aircursor.ime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import cn.moonify.aircursor.bluetooth.RfcommConstants
import java.io.DataInputStream
import java.io.IOException

/**
 * Tablet Receiver: Zero-height, invisible InputMethodService.
 * Listens for RFCOMM SPP connections from the phone and commits incoming UTF-8 text/commands
 * directly into the focused input field without showing any on-screen keyboard.
 *
 * 关键修复：
 * 1. onEvaluateInputViewShown() 必须返回 true，确保系统 TextView / EditText 正常激活输入生命周期并显示闪烁插入光标与游标指示器。
 * 2. onCreateInputView() 返回高度为 1px 的透明占位视图，禁止设为 GONE，配合 onConfigureWindow 确保零像素遮挡屏幕。
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

    /**
     * 关键修复点：
     * 必须返回 true！告知 Android 框架层当前有输入法视图处于活动展示状态。
     * 只有这样，EditText / TextView 才会正常显示文本插入光标（Cursor）、开启光标闪烁动画并展示水滴选择指示器。
     */
    override fun onEvaluateInputViewShown(): Boolean = true

    /**
     * 创建一个透明且仅有 1 像素高（或 0 尺寸）的占位视图。
     * 注意：切勿设置为 View.GONE，否则 WindowManager 会跳过窗口分配导致光标位置与焦点丢失。
     */
    override fun onCreateInputView(): View {
        return FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
            )
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.VISIBLE
        }
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        // 将 IME 窗口高度压缩至最低并固定在屏幕底部，确保完全不遮挡平板上的任何界面
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        win.setGravity(Gravity.BOTTOM)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d(TAG, "onStartInput: inputType=${attribute?.inputType}, restarting=$restarting")
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView: input session started, cursor active")
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
                    Log.d(TAG, "Listening for RFCOMM SPP with UUID: ${RfcommConstants.AIRCURSOR_UUID}")
                    ss = try {
                        bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                            RfcommConstants.SERVICE_NAME,
                            RfcommConstants.AIRCURSOR_UUID
                        )
                    } catch (e1: Exception) {
                        Log.w(TAG, "listenUsingRfcomm AIRCURSOR_UUID failed, trying insecure...", e1)
                        try {
                            bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
                                RfcommConstants.SERVICE_NAME,
                                RfcommConstants.AIRCURSOR_UUID
                            )
                        } catch (e2: Exception) {
                            Log.w(TAG, "listenUsingInsecure AIRCURSOR_UUID failed, trying SPP_UUID fallback...", e2)
                            try {
                                bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                                    RfcommConstants.SERVICE_NAME,
                                    RfcommConstants.SPP_UUID
                                )
                            } catch (e3: Exception) {
                                bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
                                    RfcommConstants.SERVICE_NAME,
                                    RfcommConstants.SPP_UUID
                                )
                            }
                        }
                    }
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
        if (text == RfcommConstants.CMD_RECENTS) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_APP_SWITCH)
            return
        }

        val ic = currentInputConnection
        if (ic == null) {
            Log.w(TAG, "currentInputConnection is null, target app has no active focus")
            return
        }

        when (text) {
            RfcommConstants.CMD_BACKSPACE -> {
                val selected = ic.getSelectedText(0)
                if (!selected.isNullOrEmpty()) {
                    ic.commitText("", 1)
                } else {
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
                // Direct UTF-8 Chinese characters commit
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
