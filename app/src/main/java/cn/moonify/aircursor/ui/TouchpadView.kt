package cn.moonify.aircursor.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Dedicated Touchpad View for capturing relative mouse movements and tap-to-click.
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Callback {
        fun onMove(dx: Int, dy: Int)
        fun onTap()
        fun onWheel(wheel: Int)
    }

    var callback: Callback? = null
    var sensitivity: Float = 1.2f
    var scrollSensitivity: Float = 0.5f

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var totalDistance = 0f

    // Two-finger scroll tracking
    private var isScrollMode = false
    private var hasMultiFingerInGesture = false
    private var lastScrollY = 0f
    private var scrollAccumulator = 0f

    private val maxTapDurationMs = 180L
    private val maxTapDistancePx = 15f * resources.displayMetrics.density

    private fun computeFocusY(event: MotionEvent, excludeIndex: Int = -1): Float {
        var sumY = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i != excludeIndex) {
                sumY += event.getY(i)
                count++
            }
        }
        return if (count > 0) sumY / count else 0f
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
                totalDistance = 0f
                isScrollMode = false
                hasMultiFingerInGesture = false
                scrollAccumulator = 0f
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                isScrollMode = true
                hasMultiFingerInGesture = true
                scrollAccumulator = 0f
                lastScrollY = computeFocusY(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    if (!isScrollMode) {
                        isScrollMode = true
                        hasMultiFingerInGesture = true
                        lastScrollY = computeFocusY(event)
                        scrollAccumulator = 0f
                    }
                    val currentFocusY = computeFocusY(event)
                    val dy = currentFocusY - lastScrollY
                    lastScrollY = currentFocusY

                    // Moving fingers UP scrolls UP (positive wheel in HID mouse)
                    // Moving fingers DOWN scrolls DOWN (negative wheel in HID mouse)
                    scrollAccumulator += (-dy * scrollSensitivity)
                    val wheel = scrollAccumulator.toInt()
                    if (wheel != 0) {
                        scrollAccumulator -= wheel
                        val clampedWheel = wheel.coerceIn(-127, 127)
                        callback?.onWheel(clampedWheel)
                    }
                } else if (!isScrollMode && !hasMultiFingerInGesture) {
                    val dx = (event.x - lastX) * sensitivity
                    val dy = (event.y - lastY) * sensitivity

                    totalDistance += hypot((event.x - lastX).toDouble(), (event.y - lastY).toDouble()).toFloat()

                    lastX = event.x
                    lastY = event.y

                    val clampedDx = dx.toInt().coerceIn(-127, 127)
                    val clampedDy = dy.toInt().coerceIn(-127, 127)

                    if (clampedDx != 0 || clampedDy != 0) {
                        callback?.onMove(clampedDx, clampedDy)
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val actionIndex = event.actionIndex
                val remainingPointers = event.pointerCount - 1
                if (remainingPointers >= 2) {
                    lastScrollY = computeFocusY(event, actionIndex)
                } else {
                    val otherIndex = if (actionIndex == 0) 1 else 0
                    lastX = event.getX(otherIndex)
                    lastY = event.getY(otherIndex)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - downTime
                if (!hasMultiFingerInGesture && duration <= maxTapDurationMs && totalDistance <= maxTapDistancePx) {
                    callback?.onTap()
                }
                isScrollMode = false
                hasMultiFingerInGesture = false
                scrollAccumulator = 0f
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isScrollMode = false
                hasMultiFingerInGesture = false
                scrollAccumulator = 0f
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
