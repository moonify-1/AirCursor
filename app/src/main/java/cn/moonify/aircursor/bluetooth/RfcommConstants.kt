package cn.moonify.aircursor.bluetooth

import java.util.UUID

object RfcommConstants {
    /**
     * Standard Bluetooth Serial Port Profile (SPP) UUID
     */
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    const val SERVICE_NAME = "RemoteUTF8Keyboard"

    const val CMD_BACKSPACE = "__CMD_BACKSPACE__"
    const val CMD_ENTER = "__CMD_ENTER__"
    const val CMD_SPACE = "__CMD_SPACE__"
}
