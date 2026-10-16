package cn.moonify.aircursor.bluetooth

/**
 * Represents a single touch contact point for HID Multi-touch Precision Touchpad.
 *
 * @property id Contact tracking identifier (0..15)
 * @property isDown Whether this contact is actively touching the surface (Tip Switch)
 * @property x Absolute X coordinate normalized to HID logical range (0..4095)
 * @property y Absolute Y coordinate normalized to HID logical range (0..4095)
 */
data class TouchContact(
    val id: Int,
    val isDown: Boolean,
    val x: Float,
    val y: Float
)
