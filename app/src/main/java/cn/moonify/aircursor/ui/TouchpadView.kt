package cn.moonify.aircursor.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Dedicated Touchpad View for capturing relative mouse movements, tap-to-click,
 * two-finger scrolling, and three-finger swipe up to switch background apps.
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
        fun onThreeFingerSwipeUp()
    }

    var callback: Callback? = null
    var sensitivity: Float = 1.2f
    var scrollSensitivity: Float = 0.2f

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

    // Three-finger swipe tracking
    private var hasThreeFingersInGesture = false
    private var hasTriggeredThreeFinger = false
    private var threeFingerStartX = 0f
    private var threeFingerStartY = 0f

    private val maxTapDurationMs = 180L
    private val maxTapDistancePx = 15f * resources.displayMetrics.density
    // 三指上滑触发有效距离 (约 45dp)
    private val minThreeFingerSwipeDistancePx get() = 45f * resources.displayMetrics.density

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateSystemGestureExclusion()
    }

    /**
     * 在 Android 10+ 上将触控板区域加入系统手势排除矩形，防止系统手势拦截三指等操作
     */
    private fun updateSystemGestureExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            post {
                if (width > 0 && height > 0) {
                    try {
                        systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun computeFocusX(event: MotionEvent, excludeIndex: Int = -1): Float {
        var sumX = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i != excludeIndex) {
                sumX += event.getX(i)
                count++
            }
        }
        return if (count > 0) sumX / count else 0f
    }

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
        parent?.requestDisallowInterceptTouchEvent(true)

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
                hasThreeFingersInGesture = false
                hasTriggeredThreeFinger = false
                scrollAccumulator = 0f
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                hasMultiFingerInGesture = true
                if (event.pointerCount >= 3) {
                    hasThreeFingersInGesture = true
                    isScrollMode = false
                    scrollAccumulator = 0f
                    threeFingerStartX = computeFocusX(event)
                    threeFingerStartY = computeFocusY(event)
                } else if (event.pointerCount == 2 && !hasThreeFingersInGesture) {
                    isScrollMode = true
                    scrollAccumulator = 0f
                    lastScrollY = computeFocusY(event)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (hasThreeFingersInGesture || event.pointerCount >= 3) {
                    if (!hasThreeFingersInGesture) {
                        hasThreeFingersInGesture = true
                        isScrollMode = false
                        scrollAccumulator = 0f
                        threeFingerStartX = computeFocusX(event)
                        threeFingerStartY = computeFocusY(event)
                    }

                    if (!hasTriggeredThreeFinger) {
                        val currentFocusX = computeFocusX(event)
                        val currentFocusY = computeFocusY(event)
                        val deltaX = currentFocusX - threeFingerStartX
                        val deltaY = currentFocusY - threeFingerStartY

                        // 上滑：deltaY 为负数，且垂直位移占主导地位（避免横滑误触）
                        if (deltaY < -minThreeFingerSwipeDistancePx && abs(deltaY) > abs(deltaX) * 0.8f) {
                            hasTriggeredThreeFinger = true
                            performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            callback?.onThreeFingerSwipeUp()
                        }
                    }
                } else if (isScrollMode && event.pointerCount == 2 && !hasThreeFingersInGesture) {
                    val currentFocusY = computeFocusY(event)
                    val dy = currentFocusY - lastScrollY
                    lastScrollY = currentFocusY

                    // Two-finger scroll: natural direction
                    // Moving fingers UP (dy < 0) scrolls page DOWN (negative wheel in HID mouse)
                    // Moving fingers DOWN (dy > 0) scrolls page UP (positive wheel in HID mouse)
                    val scrollStep = (10f * resources.displayMetrics.density).coerceAtLeast(1f)
                    scrollAccumulator += (dy / scrollStep) * scrollSensitivity
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
                if (hasThreeFingersInGesture) {
                    // 若在抬起前刚好达到快速滑动手势阈值，补触发
                    if (!hasTriggeredThreeFinger) {
                        val currentFocusX = computeFocusX(event, actionIndex)
                        val currentFocusY = computeFocusY(event, actionIndex)
                        val deltaX = currentFocusX - threeFingerStartX
                        val deltaY = currentFocusY - threeFingerStartY
                        val relaxedDistance = minThreeFingerSwipeDistancePx * 0.75f
                        if (deltaY < -relaxedDistance && abs(deltaY) > abs(deltaX) * 0.7f) {
                            hasTriggeredThreeFinger = true
                            performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            callback?.onThreeFingerSwipeUp()
                        }
                    }
                } else if (remainingPointers >= 2) {
                    lastScrollY = computeFocusY(event, actionIndex)
                } else {
                    val otherIndex = if (actionIndex == 0) 1 else 0
                    lastX = event.getX(otherIndex)
                    lastY = event.getY(otherIndex)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (hasThreeFingersInGesture && !hasTriggeredThreeFinger) {
                    val deltaX = event.x - threeFingerStartX
                    val deltaY = event.y - threeFingerStartY
                    val relaxedDistance = minThreeFingerSwipeDistancePx * 0.75f
                    if (deltaY < -relaxedDistance && abs(deltaY) > abs(deltaX) * 0.7f) {
                        hasTriggeredThreeFinger = true
                        performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                        callback?.onThreeFingerSwipeUp()
                    }
                } else if (!hasMultiFingerInGesture) {
                    val duration = System.currentTimeMillis() - downTime
                    if (duration <= maxTapDurationMs && totalDistance <= maxTapDistancePx) {
                        callback?.onTap()
                    }
                }
                isScrollMode = false
                hasMultiFingerInGesture = false
                hasThreeFingersInGesture = false
                hasTriggeredThreeFinger = false
                scrollAccumulator = 0f
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isScrollMode = false
                hasMultiFingerInGesture = false
                hasThreeFingersInGesture = false
                hasTriggeredThreeFinger = false
                scrollAccumulator = 0f
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
