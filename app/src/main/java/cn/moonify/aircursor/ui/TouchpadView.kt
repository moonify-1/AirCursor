package cn.moonify.aircursor.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import cn.moonify.aircursor.bluetooth.TouchContact
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 高精度虚拟智能触控板引擎 (Virtual Precision Trackpad Engine)。
 *
 * 核心特性：
 * 1. 动态弹道加速度曲线 (Dynamic Ballistics)：低速微移精准定点，高速轻甩快速跨屏。
 * 2. 真实物理惯性滑动 (Inertial Kinetic Coasting)：双指滑动松手后带阻尼惯性滑行，触碰即刹车。
 * 3. 丰富触控板手势：
 *    - 单指轻触：左键单击 (Tap to Click)
 *    - 双击拖拽：按下拖选窗口/文本 (Double Tap & Drag)
 *    - 双指轻触：右键单击 (Secondary Tap)
 *    - 双指滑动/拖动：垂直滚轮 + 水平平移 2D 平滑滚动与惯性滑行
 *    - 三指向上轻扫：切换平板多任务 / 最近任务
 *    - 三指向下轻扫：返回平板桌面
 *    - 三指左右轻扫：快速应用切换
 *    - 三指轻触：鼠标中键单击
 * 4. 防手势冲突与平滑过渡：集成 Android 10+ 系统手势排除，多指抬起零光标跳动。
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Callback {
        fun onMove(dx: Int, dy: Int)
        fun onTap()
        fun onSecondaryTap()
        fun onDragStart()
        fun onDragMove(dx: Int, dy: Int)
        fun onDragEnd()
        fun onScroll(wheel: Int, pan: Int)
        fun onThreeFingerSwipeUp()
        fun onThreeFingerSwipeDown()
        fun onThreeFingerHorizontalSwipe(direction: Int)
        fun onThreeFingerTap()

        // 兼容性缺省空实现
        fun onTouchpadReport(contacts: List<TouchContact>) {}
        fun onWheel(wheel: Int) {}
        fun onThreeFingerHorizontalSwipe() {}
        fun onFourFingerTap() {}
    }

    companion object {
        // 双指滑动步进缩放系数：基准 1.2x 灵敏度对应原 0.1x 物理步长 (0.1 / 1.2 = 1 / 12)
        const val SCROLL_SENS_RATIO = 0.1f / 1.2f
    }

    var callback: Callback? = null
    var sensitivity: Float = 1.2f
    var scrollSensitivity: Float = 1.2f
    var isNaturalScroll: Boolean = true
    var isNativeTouchpadMode: Boolean = false // 保留属性保持向后兼容

    // 单指位移与弹道加速度
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastMoveTime = 0L
    private var totalDistance = 0f
    private var moveAccumulatorX = 0f
    private var moveAccumulatorY = 0f

    // 双击拖拽 (Double-tap & Drag)
    private var lastTapUpTime = 0L
    private var lastTapUpX = 0f
    private var lastTapUpY = 0f
    private var isDragging = false
    private val doubleTapThresholdMs = 280L
    private val doubleTapDistanceMaxPx get() = 30f * resources.displayMetrics.density

    // 双指滚动与物理惯性滑动
    private var isScrollMode = false
    private var hasMultiFingerInGesture = false
    private var lastScrollFocusX = 0f
    private var lastScrollFocusY = 0f
    private var scrollAccumulatorX = 0f
    private var scrollAccumulatorY = 0f
    private var twoFingerDownTime = 0L
    private var twoFingerMaxDistance = 0f

    // 双指滑行速度采样队列
    private val velocityHistory = Array(5) { VelocitySample() }
    private var velocityHistoryIndex = 0
    private var velocityHistoryCount = 0

    private class VelocitySample {
        var time: Long = 0L
        var x: Float = 0f
        var y: Float = 0f
    }

    // 惯性滑行 (Kinetic Coasting)
    private var kineticVelocityX = 0f
    private var kineticVelocityY = 0f
    private var isKineticCoasting = false

    private val kineticRunnable = object : Runnable {
        override fun run() {
            if (!isKineticCoasting) return

            // 指数摩擦阻尼衰减
            kineticVelocityX *= 0.915f
            kineticVelocityY *= 0.915f

            val speed = hypot(kineticVelocityX.toDouble(), kineticVelocityY.toDouble()).toFloat()
            if (speed < 0.05f) {
                stopKineticScroll()
                return
            }

            // 将滑行速度按灵敏度换算为滚轮/平移步长 (以 1.2x 对应旧版 0.1x 物理步长等比缩放)
            val effectiveSens = scrollSensitivity * SCROLL_SENS_RATIO
            val rawWheelStep = kineticVelocityY * effectiveSens * 0.45f
            val rawPanStep = kineticVelocityX * effectiveSens * 0.45f

            val stepY = if (isNaturalScroll) rawWheelStep else -rawWheelStep
            val stepX = if (isNaturalScroll) -rawPanStep else rawPanStep

            scrollAccumulatorY += stepY
            scrollAccumulatorX += stepX

            val intWheel = scrollAccumulatorY.toInt()
            val intPan = scrollAccumulatorX.toInt()

            if (intWheel != 0 || intPan != 0) {
                scrollAccumulatorY -= intWheel
                scrollAccumulatorX -= intPan
                val clampedWheel = intWheel.coerceIn(-127, 127)
                val clampedPan = intPan.coerceIn(-127, 127)
                callback?.onScroll(clampedWheel, clampedPan)
                callback?.onWheel(clampedWheel)
            }

            postOnAnimation(this)
        }
    }

    // 三指手势
    private var hasThreeFingersInGesture = false
    private var hasTriggeredThreeFinger = false
    private var threeFingerStartTime = 0L
    private var threeFingerStartX = 0f
    private var threeFingerStartY = 0f
    private var lastThreeFingerFocusX = 0f
    private var lastThreeFingerFocusY = 0f
    private var threeFingerMaxDistance = 0f

    // 四指手势 (如全屏黑屏模式下四指轻点退出)
    private var hasFourFingersInGesture = false
    private var fourFingerStartTime = 0L
    private var fourFingerStartX = 0f
    private var fourFingerStartY = 0f
    private var fourFingerMaxDistance = 0f
    private val maxFourFingerTapDurationMs = 500L
    private val maxFourFingerTapDistancePx get() = 40f * resources.displayMetrics.density

    // 判定阈值 (根据设备密度自动缩放)
    private val maxTapDurationMs = 200L
    private val maxTapDistancePx get() = 15f * resources.displayMetrics.density
    private val minThreeFingerSwipeDistancePx get() = 14f * resources.displayMetrics.density
    private val minThreeFingerHorizontalSwipeDistancePx get() = 14f * resources.displayMetrics.density
    private val maxThreeFingerTapDistancePx get() = 36f * resources.displayMetrics.density
    private val maxThreeFingerTapDurationMs = 550L
    private val flingThresholdPxPerMs = 0.30f

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateSystemGestureExclusion()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSystemGestureExclusion()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            updateSystemGestureExclusion()
        }
    }

    /**
     * 在 Android 10+ 上将触控板区域加入系统手势排除矩形，防止手机侧滑返回截断手势
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

    private fun startKineticScroll(vx: Float, vy: Float) {
        val speed = hypot(vx.toDouble(), vy.toDouble()).toFloat()
        if (speed < flingThresholdPxPerMs) return
        kineticVelocityX = vx.coerceIn(-60f, 60f)
        kineticVelocityY = vy.coerceIn(-60f, 60f)
        isKineticCoasting = true
        removeCallbacks(kineticRunnable)
        postOnAnimation(kineticRunnable)
    }

    private fun stopKineticScroll() {
        if (isKineticCoasting) {
            isKineticCoasting = false
            removeCallbacks(kineticRunnable)
        }
    }

    private fun recordVelocitySample(time: Long, x: Float, y: Float) {
        val sample = velocityHistory[velocityHistoryIndex]
        sample.time = time
        sample.x = x
        sample.y = y
        velocityHistoryIndex = (velocityHistoryIndex + 1) % velocityHistory.size
        if (velocityHistoryCount < velocityHistory.size) {
            velocityHistoryCount++
        }
    }

    private fun computeReleaseVelocity(): Pair<Float, Float> {
        if (velocityHistoryCount < 2) return Pair(0f, 0f)
        val latestIdx = (velocityHistoryIndex - 1 + velocityHistory.size) % velocityHistory.size
        val latest = velocityHistory[latestIdx]

        // 查找 100ms 内最早的有效采样点
        var earliest = latest
        for (i in 1 until velocityHistoryCount) {
            val idx = (velocityHistoryIndex - 1 - i + velocityHistory.size) % velocityHistory.size
            val s = velocityHistory[idx]
            if (latest.time - s.time <= 120L) {
                earliest = s
            } else {
                break
            }
        }

        val dt = (latest.time - earliest.time).coerceAtLeast(1L).toFloat()
        val vx = (latest.x - earliest.x) / dt
        val vy = (latest.y - earliest.y) / dt
        return Pair(vx, vy)
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

    /**
     * 核心弹道加速度计算：
     * 根据手指瞬时划动速度非线性缩放位移，并进行亚像素累加，彻底消除鼠标卡顿与阶梯感！
     */
    private fun computeAcceleratedDelta(rawDx: Float, rawDy: Float, dtMs: Long): Pair<Int, Int> {
        val safeDt = dtMs.coerceAtLeast(1L).toFloat()
        val dist = hypot(rawDx.toDouble(), rawDy.toDouble()).toFloat()
        val speed = dist / safeDt // px / ms

        // 真实高精度触控板弹道曲线
        val accel = when {
            speed < 0.20f -> 0.85f // 微移慢调：微幅衰减，实现 1 像素级极致点选
            speed < 1.0f -> 0.85f + (speed - 0.20f) * 0.45f // 常规巡航：1.0x 左右顺手线性
            speed < 2.5f -> 1.21f + (speed - 1.0f) * 0.75f // 快速甩动：平滑放大
            else -> (2.335f + (speed - 2.5f) * 0.8f).coerceAtMost(3.6f) // 极速大跨步
        }

        val scaledDx = rawDx * sensitivity * accel + moveAccumulatorX
        val scaledDy = rawDy * sensitivity * accel + moveAccumulatorY

        val intDx = scaledDx.toInt().coerceIn(-127, 127)
        val intDy = scaledDy.toInt().coerceIn(-127, 127)

        moveAccumulatorX = scaledDx - intDx
        moveAccumulatorY = scaledDy - intDy

        return Pair(intDx, intDy)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val now = System.currentTimeMillis()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 触摸触控板瞬间执行物理刹车，停止滑行
                stopKineticScroll()

                // 双击拖拽判定 (Double-Tap & Drag)
                val timeSinceLastTap = now - lastTapUpTime
                val distFromLastTap = hypot((event.x - lastTapUpX).toDouble(), (event.y - lastTapUpY).toDouble()).toFloat()
                if (timeSinceLastTap in 40L..doubleTapThresholdMs && distFromLastTap <= doubleTapDistanceMaxPx) {
                    isDragging = true
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    callback?.onDragStart()
                } else {
                    isDragging = false
                }

                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                downTime = now
                lastMoveTime = now
                totalDistance = 0f
                moveAccumulatorX = 0f
                moveAccumulatorY = 0f

                isScrollMode = false
                hasMultiFingerInGesture = false
                hasThreeFingersInGesture = false
                hasTriggeredThreeFinger = false
                threeFingerMaxDistance = 0f
                hasFourFingersInGesture = false
                fourFingerMaxDistance = 0f
                scrollAccumulatorX = 0f
                scrollAccumulatorY = 0f
                velocityHistoryCount = 0
                velocityHistoryIndex = 0
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                hasMultiFingerInGesture = true
                stopKineticScroll()

                // 若在双击拖拽时放入第二指，安全取消拖拽状态
                if (isDragging) {
                    isDragging = false
                    callback?.onDragEnd()
                }

                if (event.pointerCount >= 4) {
                    hasFourFingersInGesture = true
                    fourFingerStartTime = now
                    fourFingerStartX = computeFocusX(event)
                    fourFingerStartY = computeFocusY(event)
                    fourFingerMaxDistance = 0f
                }

                if (event.pointerCount >= 3) {
                    hasThreeFingersInGesture = true
                    hasTriggeredThreeFinger = false
                    isScrollMode = false
                    threeFingerStartTime = now
                    threeFingerStartX = computeFocusX(event)
                    threeFingerStartY = computeFocusY(event)
                    lastThreeFingerFocusX = threeFingerStartX
                    lastThreeFingerFocusY = threeFingerStartY
                    threeFingerMaxDistance = 0f
                } else if (event.pointerCount == 2 && !hasThreeFingersInGesture) {
                    isScrollMode = true
                    scrollAccumulatorX = 0f
                    scrollAccumulatorY = 0f
                    twoFingerDownTime = now
                    twoFingerMaxDistance = 0f
                    lastScrollFocusX = computeFocusX(event)
                    lastScrollFocusY = computeFocusY(event)
                    velocityHistoryCount = 0
                    velocityHistoryIndex = 0
                    recordVelocitySample(now, lastScrollFocusX, lastScrollFocusY)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // 四指轻触位移追踪
                if (hasFourFingersInGesture) {
                    val currentFocusX = computeFocusX(event)
                    val currentFocusY = computeFocusY(event)
                    val dist = hypot((currentFocusX - fourFingerStartX).toDouble(), (currentFocusY - fourFingerStartY).toDouble()).toFloat()
                    if (dist > fourFingerMaxDistance) {
                        fourFingerMaxDistance = dist
                    }
                }

                // ==================== 1. 三指手势处理 ====================
                if (hasThreeFingersInGesture || event.pointerCount >= 3) {
                    if (!hasThreeFingersInGesture) {
                        hasThreeFingersInGesture = true
                        hasTriggeredThreeFinger = false
                        isScrollMode = false
                        threeFingerStartTime = now
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
                        // 1. 三指向上划动：进入后台/任务切换
                        if (deltaY < -minThreeFingerSwipeDistancePx && abs(deltaY) > abs(deltaX) * 0.6f) {
                            hasTriggeredThreeFinger = true
                            performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            callback?.onThreeFingerSwipeUp()
                        }
                        // 2. 三指向下划动：显示桌面
                        else if (deltaY > minThreeFingerSwipeDistancePx && abs(deltaY) > abs(deltaX) * 0.6f) {
                            hasTriggeredThreeFinger = true
                            performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            callback?.onThreeFingerSwipeDown()
                        }
                        // 3. 三指水平左右划动：切应用
                        else if (abs(deltaX) > minThreeFingerHorizontalSwipeDistancePx && abs(deltaX) > abs(deltaY) * 0.65f) {
                            hasTriggeredThreeFinger = true
                            performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            val direction = if (deltaX > 0) 1 else -1
                            callback?.onThreeFingerHorizontalSwipe(direction)
                        }
                    }
                }
                // ==================== 2. 双指平滑 2D 滚动处理 ====================
                else if (isScrollMode && event.pointerCount == 2 && !hasThreeFingersInGesture) {
                    val currentFocusX = computeFocusX(event)
                    val currentFocusY = computeFocusY(event)
                    val dx = currentFocusX - lastScrollFocusX
                    val dy = currentFocusY - lastScrollFocusY
                    lastScrollFocusX = currentFocusX
                    lastScrollFocusY = currentFocusY

                    twoFingerMaxDistance += hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    recordVelocitySample(now, currentFocusX, currentFocusY)

                    // 步进换算：支持垂直滚轮与水平横向平移 (AC Pan)
                    // 基准 1.2x 对应旧版 0.1x 物理步长等比缩放
                    val effectiveSens = scrollSensitivity * SCROLL_SENS_RATIO
                    val rawWheelStep = dy * effectiveSens * 0.45f
                    val rawPanStep = dx * effectiveSens * 0.45f

                    // 自然滑动方向：手指向上推 (dy < 0) 页面向下滚 (negative wheel)
                    // 传统滚轮方向：手指向上推 (dy < 0) 页面向上滚 (positive wheel)
                    val stepY = if (isNaturalScroll) rawWheelStep else -rawWheelStep
                    val stepX = if (isNaturalScroll) -rawPanStep else rawPanStep

                    scrollAccumulatorY += stepY
                    scrollAccumulatorX += stepX

                    val intWheel = scrollAccumulatorY.toInt()
                    val intPan = scrollAccumulatorX.toInt()

                    if (intWheel != 0 || intPan != 0) {
                        scrollAccumulatorY -= intWheel
                        scrollAccumulatorX -= intPan
                        val clampedWheel = intWheel.coerceIn(-127, 127)
                        val clampedPan = intPan.coerceIn(-127, 127)
                        callback?.onScroll(clampedWheel, clampedPan)
                        callback?.onWheel(clampedWheel)
                    }
                }
                // ==================== 3. 单指指针移动 / 拖拽选择 ====================
                else if (!isScrollMode && !hasMultiFingerInGesture) {
                    val rawDx = event.x - lastX
                    val rawDy = event.y - lastY
                    val dt = now - lastMoveTime

                    totalDistance += hypot(rawDx.toDouble(), rawDy.toDouble()).toFloat()
                    lastX = event.x
                    lastY = event.y
                    lastMoveTime = now

                    val (clampedDx, clampedDy) = computeAcceleratedDelta(rawDx, rawDy, dt)

                    if (clampedDx != 0 || clampedDy != 0) {
                        if (isDragging) {
                            callback?.onDragMove(clampedDx, clampedDy)
                        } else {
                            callback?.onMove(clampedDx, clampedDy)
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val actionIndex = event.actionIndex
                val remainingPointers = event.pointerCount - 1

                if (hasFourFingersInGesture) {
                    val duration = now - fourFingerStartTime
                    if (duration <= maxFourFingerTapDurationMs && fourFingerMaxDistance <= maxFourFingerTapDistancePx) {
                        hasFourFingersInGesture = false
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        callback?.onFourFingerTap()
                    } else {
                        hasFourFingersInGesture = false
                    }
                }

                if (hasThreeFingersInGesture) {
                    if (!hasTriggeredThreeFinger) {
                        val deltaX = lastThreeFingerFocusX - threeFingerStartX
                        val deltaY = lastThreeFingerFocusY - threeFingerStartY
                        // 1. 抬起时结算三指上滑
                        if (deltaY < -minThreeFingerSwipeDistancePx * 0.70f && abs(deltaY) > abs(deltaX) * 0.55f) {
                            hasTriggeredThreeFinger = true
                            performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            callback?.onThreeFingerSwipeUp()
                        }
                        // 2. 抬起时结算三指向下滑 (桌面)
                        else if (deltaY > minThreeFingerSwipeDistancePx * 0.70f && abs(deltaY) > abs(deltaX) * 0.55f) {
                            hasTriggeredThreeFinger = true
                            performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            callback?.onThreeFingerSwipeDown()
                        }
                        // 3. 抬起时结算三指横滑 (切应用)
                        else if (abs(deltaX) > minThreeFingerHorizontalSwipeDistancePx * 0.70f && abs(deltaX) > abs(deltaY) * 0.55f) {
                            hasTriggeredThreeFinger = true
                            performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            val direction = if (deltaX > 0) 1 else -1
                            callback?.onThreeFingerHorizontalSwipe(direction)
                        }
                        // 4. 抬起时结算三指轻点 (免除分屏干扰的核心稳定手势)
                        else {
                            val duration = now - threeFingerStartTime
                            if (duration <= maxThreeFingerTapDurationMs && threeFingerMaxDistance <= maxThreeFingerTapDistancePx) {
                                hasTriggeredThreeFinger = true
                                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                callback?.onThreeFingerTap()
                            } else if (duration <= 700L) {
                                // 容错兜底：静止停留短时抬手也触发轻点，杜绝任何手指微颤造成的丢手势
                                hasTriggeredThreeFinger = true
                                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                callback?.onThreeFingerTap()
                            }
                        }
                    }
                    // 三指抬起后立即结束三指与滚动状态，防止残余单指/双指引起滚轮或光标跳跃
                    hasThreeFingersInGesture = false
                    isScrollMode = false
                } else if (isScrollMode && event.pointerCount == 2) {
                    // 双指抬起：判定双指轻点 (右键) 或启动物理惯性滑行
                    val duration = now - twoFingerDownTime
                    if (duration <= 250L && twoFingerMaxDistance <= 18f * resources.displayMetrics.density) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        callback?.onSecondaryTap()
                    } else {
                        // 启动惯性滑行
                        val (vx, vy) = computeReleaseVelocity()
                        startKineticScroll(vx, vy)
                    }
                }

                // 平滑过渡：当手指抬起变为单指时，重置坐标为留下的单指，彻底杜绝光标暴跳
                if (remainingPointers == 1) {
                    val remainingIndex = if (actionIndex == 0) 1 else 0
                    lastX = event.getX(remainingIndex)
                    lastY = event.getY(remainingIndex)
                    lastMoveTime = now
                    moveAccumulatorX = 0f
                    moveAccumulatorY = 0f
                } else if (remainingPointers >= 2) {
                    lastScrollFocusX = computeFocusX(event, actionIndex)
                    lastScrollFocusY = computeFocusY(event, actionIndex)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    isDragging = false
                    callback?.onDragEnd()
                    lastTapUpTime = 0L
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }

                if (hasThreeFingersInGesture) {
                    if (!hasTriggeredThreeFinger) {
                        val deltaX = lastThreeFingerFocusX - threeFingerStartX
                        val deltaY = lastThreeFingerFocusY - threeFingerStartY
                        if (deltaY < -minThreeFingerSwipeDistancePx * 0.70f && abs(deltaY) > abs(deltaX) * 0.55f) {
                            hasTriggeredThreeFinger = true
                            performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            callback?.onThreeFingerSwipeUp()
                        } else if (deltaY > minThreeFingerSwipeDistancePx * 0.70f && abs(deltaY) > abs(deltaX) * 0.55f) {
                            hasTriggeredThreeFinger = true
                            performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            callback?.onThreeFingerSwipeDown()
                        } else if (abs(deltaX) > minThreeFingerHorizontalSwipeDistancePx * 0.70f && abs(deltaX) > abs(deltaY) * 0.55f) {
                            hasTriggeredThreeFinger = true
                            performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            val direction = if (deltaX > 0) 1 else -1
                            callback?.onThreeFingerHorizontalSwipe(direction)
                        } else {
                            val duration = now - threeFingerStartTime
                            if (duration <= maxThreeFingerTapDurationMs && threeFingerMaxDistance <= maxThreeFingerTapDistancePx) {
                                hasTriggeredThreeFinger = true
                                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                callback?.onThreeFingerTap()
                            } else if (duration <= 700L) {
                                hasTriggeredThreeFinger = true
                                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                callback?.onThreeFingerTap()
                            }
                        }
                    }
                } else if (isScrollMode) {
                    // 若此前以双指结束触摸，且滑行尚未触发，尝试启动惯性滑行
                    val (vx, vy) = computeReleaseVelocity()
                    startKineticScroll(vx, vy)
                } else if (!hasMultiFingerInGesture) {
                    val duration = now - downTime
                    if (duration <= maxTapDurationMs && totalDistance <= maxTapDistancePx) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        callback?.onTap()
                        lastTapUpTime = now
                        lastTapUpX = event.x
                        lastTapUpY = event.y
                    } else {
                        lastTapUpTime = 0L
                    }
                }

                isScrollMode = false
                hasMultiFingerInGesture = false
                hasThreeFingersInGesture = false
                hasTriggeredThreeFinger = false
                threeFingerMaxDistance = 0f
                hasFourFingersInGesture = false
                fourFingerMaxDistance = 0f
                scrollAccumulatorX = 0f
                scrollAccumulatorY = 0f
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    callback?.onDragEnd()
                }
                stopKineticScroll()

                // 核心防分屏拦截拯救机制：
                // 当手机系统（如 vivo OriginOS、OPPO ColorOS、小米 HyperOS）的三指手势服务
                // 截获三指划动/接触并强行向 App 派发 ACTION_CANCEL 时，根据此前已记录的位移特征紧急拯救并派发对应指令！
                if (hasThreeFingersInGesture && !hasTriggeredThreeFinger) {
                    val deltaX = lastThreeFingerFocusX - threeFingerStartX
                    val deltaY = lastThreeFingerFocusY - threeFingerStartY
                    val duration = now - threeFingerStartTime

                    // 1. 若检测到向上位移特征（哪怕仅微移 -8dp 系统便切断事件），精准判定为三指上滑！
                    if (deltaY < -8f * resources.displayMetrics.density) {
                        hasTriggeredThreeFinger = true
                        performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                        callback?.onThreeFingerSwipeUp()
                    }
                    // 2. 若检测到向下位移特征，判定为三指向下滑（显示桌面）
                    else if (deltaY > 10f * resources.displayMetrics.density) {
                        hasTriggeredThreeFinger = true
                        performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                        callback?.onThreeFingerSwipeDown()
                    }
                    // 3. 若检测到水平位移特征，判定为三指横滑切应用
                    else if (abs(deltaX) > 10f * resources.displayMetrics.density) {
                        hasTriggeredThreeFinger = true
                        performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                        val direction = if (deltaX > 0) 1 else -1
                        callback?.onThreeFingerHorizontalSwipe(direction)
                    }
                    // 4. 若位移很小且在有效时长内，拯救为三指轻点
                    else if (duration <= 700L) {
                        hasTriggeredThreeFinger = true
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        callback?.onThreeFingerTap()
                    }
                }

                isScrollMode = false
                hasMultiFingerInGesture = false
                hasThreeFingersInGesture = false
                hasTriggeredThreeFinger = false
                threeFingerMaxDistance = 0f
                hasFourFingersInGesture = false
                fourFingerMaxDistance = 0f
                scrollAccumulatorX = 0f
                scrollAccumulatorY = 0f
                lastTapUpTime = 0L
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
