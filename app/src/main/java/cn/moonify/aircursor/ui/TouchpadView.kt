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
        fun onThreeFingerTap()
        fun onThreeFingerHorizontalSwipe()
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

    // Three-finger gesture tracking
    private var hasThreeFingersInGesture = false
    private var hasTriggeredThreeFinger = false
    private var threeFingerStartTime = 0L
    private var threeFingerStartX = 0f
    private var threeFingerStartY = 0f
    private var lastThreeFingerFocusX = 0f
    private var lastThreeFingerFocusY = 0f
    private var threeFingerMaxDistance = 0f

    private val maxTapDurationMs = 180L
    private val maxTapDistancePx = 15f * resources.displayMetrics.density

    // 三指手势灵敏度阈值 (低位移快速识别，避开系统分屏手势拦截)
    private val minThreeFingerSwipeDistancePx get() = 18f * resources.displayMetrics.density
    private val minThreeFingerHorizontalSwipeDistancePx get() = 20f * resources.displayMetrics.density
    private val maxThreeFingerTapDistancePx get() = 12f * resources.displayMetrics.density
    private val maxThreeFingerTapDurationMs = 350L

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
                threeFingerMaxDistance = 0f
                scrollAccumulator = 0f
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                hasMultiFingerInGesture = true
                if (event.pointerCount >= 3) {
                    hasThreeFingersInGesture = true
                    hasTriggeredThreeFinger = false
                    isScrollMode = false
                    scrollAccumulator = 0f
                    threeFingerStartTime = System.currentTimeMillis()
                    threeFingerStartX = computeFocusX(event)
                    threeFingerStartY = computeFocusY(event)
                    lastThreeFingerFocusX = threeFingerStartX
                    lastThreeFingerFocusY = threeFingerStartY
                    threeFingerMaxDistance = 0f
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
                        hasTriggeredThreeFinger = false
                        isScrollMode = false
                        scrollAccumulator = 0f
                        threeFingerStartTime = System.currentTimeMillis()
                        threeFingerStartX = computeFocusX(event)
                        threeFingerStartY = computeFocusY(event)
                        lastThreeFingerFocusX = threeFingerStartX
                        lastThreeFingerFocusY = threeFingerStartY
                        threeFingerMaxDistance = 0f
                    }

                    val currentFocusX = computeFocusX(event)
                    val currentFocusY = computeFocusY(event)
                    lastThreeFingerFocusX = currentFocusX
                    lastThreeFingerFocusY = currentFocusY

                    val deltaX = currentFocusX - threeFingerStartX
                    val deltaY = currentFocusY - threeFingerStartY
                    val dist = hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat()
                    if (dist > threeFingerMaxDistance) {
                        threeFingerMaxDistance = dist
                    }

                    if (!hasTriggeredThreeFinger) {
                        // 1. 三指上滑：deltaY 为负数，且垂直位移占主导地位
                        if (deltaY < -minThreeFingerSwipeDistancePx && abs(deltaY) > abs(deltaX) * 0.7f) {
                            hasTriggeredThreeFinger = true
                            performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            callback?.onThreeFingerSwipeUp()
                        }
                        // 2. 三指横滑：水平位移占主导地位 (零冲突避开手机三指上滑分屏)
                        else if (abs(deltaX) > minThreeFingerHorizontalSwipeDistancePx && abs(deltaX) > abs(deltaY) * 1.2f) {
                            hasTriggeredThreeFinger = true
                            performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            callback?.onThreeFingerHorizontalSwipe()
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
                    if (!hasTriggeredThreeFinger) {
                        val deltaX = lastThreeFingerFocusX - threeFingerStartX
                        val deltaY = lastThreeFingerFocusY - threeFingerStartY
                        if (deltaY < -minThreeFingerSwipeDistancePx * 0.75f && abs(deltaY) > abs(deltaX) * 0.6f) {
                            hasTriggeredThreeFinger = true
                            performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            callback?.onThreeFingerSwipeUp()
                        } else if (abs(deltaX) > minThreeFingerHorizontalSwipeDistancePx * 0.75f && abs(deltaX) > abs(deltaY) * 1.0f) {
                            hasTriggeredThreeFinger = true
                            performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            callback?.onThreeFingerHorizontalSwipe()
                        } else {
                            val duration = System.currentTimeMillis() - threeFingerStartTime
                            if (duration <= maxThreeFingerTapDurationMs && threeFingerMaxDistance <= maxThreeFingerTapDistancePx) {
                                hasTriggeredThreeFinger = true
                                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                callback?.onThreeFingerTap()
                            }
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
                if (hasThreeFingersInGesture) {
                    if (!hasTriggeredThreeFinger) {
                        val deltaX = lastThreeFingerFocusX - threeFingerStartX
                        val deltaY = lastThreeFingerFocusY - threeFingerStartY
                        if (deltaY < -minThreeFingerSwipeDistancePx * 0.75f && abs(deltaY) > abs(deltaX) * 0.6f) {
                            hasTriggeredThreeFinger = true
                            performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            callback?.onThreeFingerSwipeUp()
                        } else if (abs(deltaX) > minThreeFingerHorizontalSwipeDistancePx * 0.75f && abs(deltaX) > abs(deltaY) * 1.0f) {
                            hasTriggeredThreeFinger = true
                            performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            callback?.onThreeFingerHorizontalSwipe()
                        } else {
                            val duration = System.currentTimeMillis() - threeFingerStartTime
                            if (duration <= maxThreeFingerTapDurationMs && threeFingerMaxDistance <= maxThreeFingerTapDistancePx) {
                                hasTriggeredThreeFinger = true
                                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                callback?.onThreeFingerTap()
                            }
                        }
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
                threeFingerMaxDistance = 0f
                scrollAccumulator = 0f
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (hasThreeFingersInGesture && !hasTriggeredThreeFinger) {
                    val deltaX = lastThreeFingerFocusX - threeFingerStartX
                    val deltaY = lastThreeFingerFocusY - threeFingerStartY
                    if (deltaY < -minThreeFingerSwipeDistancePx * 0.7f && abs(deltaY) > abs(deltaX) * 0.6f) {
                        hasTriggeredThreeFinger = true
                        performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                        callback?.onThreeFingerSwipeUp()
                    }
                }
                isScrollMode = false
                hasMultiFingerInGesture = false
                hasThreeFingersInGesture = false
                hasTriggeredThreeFinger = false
                threeFingerMaxDistance = 0f
                scrollAccumulator = 0f
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
