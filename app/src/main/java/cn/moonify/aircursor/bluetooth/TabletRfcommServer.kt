package cn.moonify.aircursor.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import cn.moonify.aircursor.ime.RemoteImeService
import cn.moonify.aircursor.service.AirCursorService
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 平板端高可靠 RFCOMM SPP 服务端 (支持免切输入法剪贴板中继 + IME 直接落字)：
 * 1. 当平板使用系统默认输入法 (如 vivo、搜狗、Gboard) 时：
 *    接收手机发送的 UTF-8 原始文本，写入平板系统剪贴板，并向手机回复 ACK_PASTE；
 *    手机收到 ACK_PASTE 后通过 HID 模拟发送 Ctrl+V，触发焦点输入框瞬时精准粘贴；
 * 2. 当平板启用 AirCursor 作为默认输入法时：
 *    检测到 RemoteImeService.activeInstance，直接调用 InputConnection.commitText() 落字，
 *    并向手机回复 ACK_COMMITTED；
 * 3. 彻底突破拼音转译同音字与候选栏限制，实现 100% 绝对精确打字！
 */
object TabletRfcommServer {

    private const val TAG = "TabletRfcommServer"

    interface Listener {
        fun onServerStateChanged(isRunning: Boolean, connectedClients: Int)
        fun onTextReceived(text: String, isCommittedDirectly: Boolean)
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

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var connectedClientCount: Int = 0
        private set

    private var serverThread: Thread? = null
    private val clientSockets = CopyOnWriteArrayList<BluetoothSocket>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    private val bluetoothAdapter: BluetoothAdapter?
        get() = BluetoothAdapter.getDefaultAdapter()

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        appContext = context.applicationContext
        // 自动拉起平板端前台保活服务，确保平板切换至任何输入应用时后台不被系统冻结或杀死
        appContext?.let {
            AirCursorService.start(it)
        }

        if (isRunning) {
            Log.d(TAG, "TabletRfcommServer already running")
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available or disabled, cannot start TabletRfcommServer")
            return
        }

        isRunning = true
        notifyStateChanged()

        serverThread = Thread({
            while (isRunning) {
                var ss: BluetoothServerSocket? = null
                try {
                    Log.d(TAG, "Listening for RFCOMM client on UUID: ${RfcommConstants.AIRCURSOR_UUID}")
                    ss = try {
                        adapter.listenUsingInsecureRfcommWithServiceRecord(
                            RfcommConstants.SERVICE_NAME,
                            RfcommConstants.AIRCURSOR_UUID
                        )
                    } catch (e1: Exception) {
                        Log.w(TAG, "listenUsingInsecure AIRCURSOR_UUID failed, trying secure...", e1)
                        try {
                            adapter.listenUsingRfcommWithServiceRecord(
                                RfcommConstants.SERVICE_NAME,
                                RfcommConstants.AIRCURSOR_UUID
                            )
                        } catch (e2: Exception) {
                            Log.w(TAG, "listenUsingRfcomm AIRCURSOR_UUID failed, trying SPP_UUID fallback...", e2)
                            try {
                                adapter.listenUsingInsecureRfcommWithServiceRecord(
                                    RfcommConstants.SERVICE_NAME,
                                    RfcommConstants.SPP_UUID
                                )
                            } catch (e3: Exception) {
                                adapter.listenUsingRfcommWithServiceRecord(
                                    RfcommConstants.SERVICE_NAME,
                                    RfcommConstants.SPP_UUID
                                )
                            }
                        }
                    }

                    while (isRunning) {
                        val socket = try {
                            ss?.accept() ?: break
                        } catch (e: IOException) {
                            if (!isRunning) {
                                Log.d(TAG, "Server socket closed intentionally")
                            } else {
                                Log.e(TAG, "Server accept error", e)
                            }
                            break
                        }

                        Log.d(TAG, "Accepted connection from ${socket.remoteDevice?.address}")
                        clientSockets.add(socket)
                        connectedClientCount = clientSockets.size
                        notifyStateChanged()

                        Thread({
                            handleClient(socket)
                        }, "AirCursor-ClientHandler-${socket.remoteDevice?.address}").start()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in RFCOMM server accept loop", e)
                } finally {
                    try {
                        ss?.close()
                    } catch (_: Exception) {}
                }

                if (isRunning) {
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            isRunning = false
            notifyStateChanged()
        }, "AirCursor-Tablet-RFCOMM-Server").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleClient(socket: BluetoothSocket) {
        var dis: DataInputStream? = null
        var dos: DataOutputStream? = null
        try {
            dis = DataInputStream(socket.inputStream)
            dos = DataOutputStream(socket.outputStream)

            while (isRunning && socket.isConnected) {
                val message = dis.readUTF()
                Log.d(TAG, "Tablet received message from ${socket.remoteDevice?.address}: $message")

                val ack = processIncomingMessage(message)
                dos.writeUTF(ack)
                dos.flush()
            }
        } catch (e: IOException) {
            Log.d(TAG, "Client socket disconnected (${socket.remoteDevice?.address}): ${e.message}")
        } finally {
            try { dis?.close() } catch (_: Exception) {}
            try { dos?.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
            clientSockets.remove(socket)
            connectedClientCount = clientSockets.size
            notifyStateChanged()
        }
    }

    /**
     * 核心处理：
     * 1. 若当前运行中的 RemoteImeService 具有输入焦点 (currentInputConnection != null)，
     *    则直接通过 IME commitText 落字，回复 ACK_COMMITTED；
     * 2. 否则通过系统剪贴板将文本写入，回复 ACK_PASTE，手机端将紧接着发送 Ctrl+V 完成无感粘贴！
     */
    private fun processIncomingMessage(text: String): String {
        // 1. 控制指令处理
        if (text == RfcommConstants.CMD_RECENTS) {
            val ime = RemoteImeService.activeInstance
            if (ime != null) {
                mainHandler.post {
                    ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_APP_SWITCH)
                }
                return RfcommConstants.ACK_HANDLED
            }
            return RfcommConstants.ACK_PASSTHROUGH
        }

        if (text == RfcommConstants.CMD_BACK) {
            val ime = RemoteImeService.activeInstance
            if (ime != null) {
                mainHandler.post {
                    ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_BACK)
                }
                return RfcommConstants.ACK_HANDLED
            }
            return RfcommConstants.ACK_PASSTHROUGH
        }

        if (text == RfcommConstants.CMD_BACKSPACE) {
            val ime = RemoteImeService.activeInstance
            val ic = ime?.currentInputConnection
            if (ic != null) {
                mainHandler.post {
                    val selected = ic.getSelectedText(0)
                    if (!selected.isNullOrEmpty()) {
                        ic.commitText("", 1)
                    } else {
                        ic.deleteSurroundingText(1, 0)
                    }
                }
                return RfcommConstants.ACK_HANDLED
            }
            return RfcommConstants.ACK_PASSTHROUGH
        }

        if (text == RfcommConstants.CMD_ENTER) {
            val ime = RemoteImeService.activeInstance
            if (ime != null && ime.currentInputConnection != null) {
                mainHandler.post {
                    ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                }
                return RfcommConstants.ACK_HANDLED
            }
            return RfcommConstants.ACK_PASSTHROUGH
        }

        // 2. 文本上屏处理
        val ime = RemoteImeService.activeInstance
        val ic = ime?.currentInputConnection
        if (ic != null) {
            mainHandler.post {
                ic.commitText(text, 1)
                for (l in listeners) {
                    l.onTextReceived(text, isCommittedDirectly = true)
                }
            }
            return RfcommConstants.ACK_COMMITTED
        } else {
            // 平板保留默认输入法（如 vivo 输入法）：执行剪贴板中继写入
            // 注意：此处必须先同步写入剪贴板，随后向手机返回 ACK_PASTE，手机才能发送物理 Ctrl+V 准确粘贴！
            try {
                val ctx = appContext
                if (ctx != null) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = ClipData.newPlainText("AirCursor", text)
                    cm?.setPrimaryClip(clip)
                    Log.d(TAG, "Copied text to clipboard via relay: '$text'")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy text to clipboard", e)
            }

            // 预留 35ms 等待 Android 系统 ClipboardService 完成跨进程数据同步与系统广播
            try {
                Thread.sleep(35)
            } catch (_: InterruptedException) {}

            mainHandler.post {
                for (l in listeners) {
                    l.onTextReceived(text, isCommittedDirectly = false)
                }
            }
            return RfcommConstants.ACK_PASTE
        }
    }

    fun stop() {
        isRunning = false
        for (s in clientSockets) {
            try { s.close() } catch (_: Exception) {}
        }
        clientSockets.clear()
        connectedClientCount = 0

        serverThread?.interrupt()
        serverThread = null
        notifyStateChanged()
    }

    private fun notifyStateChanged() {
        mainHandler.post {
            for (l in listeners) {
                try {
                    l.onServerStateChanged(isRunning, connectedClientCount)
                } catch (e: Exception) {
                    Log.e(TAG, "Listener onServerStateChanged error", e)
                }
            }
        }
    }
}
