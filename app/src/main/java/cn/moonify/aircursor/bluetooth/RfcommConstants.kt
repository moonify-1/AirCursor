package cn.moonify.aircursor.bluetooth

import java.util.UUID

object RfcommConstants {
    /**
     * Dedicated AirCursor SPP UUID (prevents Android Bluetooth stack SPP UUID collisions)
     */
    val AIRCURSOR_UUID: UUID = UUID.fromString("e8a34b20-7b2a-11ed-a1eb-0242ac120002")

    /**
     * Standard Bluetooth Serial Port Profile (SPP) UUID
     */
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    const val SERVICE_NAME = "AirCursorUTF8"

    const val CMD_BACKSPACE = "__CMD_BACKSPACE__"
    const val CMD_ENTER = "__CMD_ENTER__"
    const val CMD_SPACE = "__CMD_SPACE__"
    const val CMD_RECENTS = "__CMD_RECENTS__"

    // Tablet Response ACKs
    const val ACK_COMMITTED = "__ACK_COMMITTED__"
    const val ACK_PASTE = "__ACK_PASTE__"
    const val ACK_HANDLED = "__ACK_HANDLED__"
    const val ACK_PASSTHROUGH = "__ACK_PASSTHROUGH__"
}
