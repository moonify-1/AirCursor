package cn.moonify.aircursor.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Vertical scroll bar extended to screen right edge for sending HID wheel reports.
 */
class WheelBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Callback {
        fun onWheel(wheel: Int)
        fun onSwipeBack() {}
    }

    var callback: Callback? = null
    var sensitivity: Float = 0.2f
    // 滚轮滑动方向：默认 true 为两指滑动方向（自然滚动，手指下滑为正向 wheel，与 TouchpadView 一致）；false 为反向传统滚轮
    var isNaturalDirection: Boolean = true

    // 是否将滚轮栏区域加入系统手势排除矩形（开启右侧侧滑映射平板返回时为 false，允许系统手势触发；关闭时为 true，屏蔽系统手势防滚轮误触）
    var isSystemGestureExclusionEnabled: Boolean = false
        set(value) {
            field = value
            updateSystemGestureExclusion()
        }

    // 横屏极简悬浮胶囊滚轮样式
    var isCapsuleStyle: Boolean = false
        set(value) {
            field = value
            if (value) {
                setBackgroundResource(cn.moonify.aircursor.R.drawable.bg_land_capsule)
            }
            invalidate()
        }

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var wheelAccumulator = 0f
    private var isHorizontalSwipe = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, cn.moonify.aircursor.R.color.wheel_bg))
    }

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
     * 在 Android 10+ 上根据配置将滚轮栏区域加入系统手势排除矩形。
     * 若开启了右侧侧滑返回映射，则不排除，确保手机系统能正常响应右边缘侧滑返回手势；
     * 若关闭了映射，则将滚轮栏区域加入手势排除矩形，防止贴边滑动时触发系统侧滑返回。
     */
    private fun updateSystemGestureExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            post {
                try {
                    systemGestureExclusionRects = if (isSystemGestureExclusionEnabled && width > 0 && height > 0) {
                        listOf(Rect(0, 0, width, height))
                    } else {
                        emptyList()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (isCapsuleStyle) {
            val barColor = androidx.core.content.ContextCompat.getColor(context, cn.moonify.aircursor.R.color.land_wheel_bar)
            val dotColor = androidx.core.content.ContextCompat.getColor(context, cn.moonify.aircursor.R.color.land_active_tab)

            // Top small dot
            paint.style = Paint.Style.FILL
            paint.color = dotColor
            canvas.drawCircle(w / 2f, 18f * resources.displayMetrics.density, 2.5f * resources.displayMetrics.density, paint)

            // Bottom small dot
            canvas.drawCircle(w / 2f, h - 18f * resources.displayMetrics.density, 2.5f * resources.displayMetrics.density, paint)

            // Middle horizontal handle bar
            paint.style = Paint.Style.STROKE
            paint.color = barColor
            paint.strokeWidth = 3.5f * resources.displayMetrics.density
            paint.strokeCap = Paint.Cap.ROUND
            val insetX = 4f * resources.displayMetrics.density
            canvas.drawLine(insetX, h / 2f, w - insetX, h / 2f, paint)
            return
        }

        // Draw central track line
        paint.color = androidx.core.content.ContextCompat.getColor(context, cn.moonify.aircursor.R.color.wheel_track)
        paint.strokeWidth = 3f * resources.displayMetrics.density
        canvas.drawLine(w / 2f, h * 0.1f, w / 2f, h * 0.9f, paint)

        // Draw small horizontal notches
        paint.color = androidx.core.content.ContextCompat.getColor(context, cn.moonify.aircursor.R.color.wheel_notch)
        paint.strokeWidth = 2f * resources.displayMetrics.density
        val notchSpacing = 28f * resources.displayMetrics.density
        var y = h * 0.2f
        while (y <= h * 0.8f) {
            canvas.drawLine(w * 0.3f, y, w * 0.7f, y, paint)
            y += notchSpacing
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastY = event.y
                wheelAccumulator = 0f
                isHorizontalSwipe = false
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val totalDx = Math.abs(event.x - downX)
                val totalDy = Math.abs(event.y - downY)

                // 若手指从右边缘向内（向左，即 event.x < downX）快速横滑，判定为内滑返回手势
                // 抑制滚轮累加，防止向平板误发滚轮滚动
                if (!isHorizontalSwipe && (downX - event.x) > 12f * resources.displayMetrics.density && (downX - event.x) > totalDy * 0.8f) {
                    isHorizontalSwipe = true
                }
                if (isHorizontalSwipe) {
                    return true
                }

                val dy = event.y - lastY
                lastY = event.y

                // 默认使用两指滑动方向 (isNaturalDirection = true):
                // 手指上推 (dy < 0) 页面下滚 (negative wheel in HID mouse)
                // 手指下滑 (dy > 0) 页面上滚 (positive wheel in HID mouse)
                val scrollStep = (10f * resources.displayMetrics.density).coerceAtLeast(1f)
                val factor = if (isNaturalDirection) 1f else -1f
                wheelAccumulator += (factor * dy / scrollStep) * sensitivity
                val wheel = wheelAccumulator.toInt()
                if (wheel != 0) {
                    wheelAccumulator -= wheel
                    val clampedWheel = wheel.coerceIn(-127, 127)
                    callback?.onWheel(clampedWheel)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (isHorizontalSwipe) {
                    callback?.onSwipeBack()
                }
                wheelAccumulator = 0f
                isHorizontalSwipe = false
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                wheelAccumulator = 0f
                isHorizontalSwipe = false
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
