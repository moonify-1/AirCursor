package cn.moonify.aircursor.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 48dp-wide vertical scroll bar for sending HID wheel reports.
 */
class WheelBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Callback {
        fun onWheel(wheel: Int)
    }

    var callback: Callback? = null
    var sensitivity: Float = 0.2f
    // 滚轮滑动方向：默认 true 为两指滑动方向（自然滚动，手指下滑为正向 wheel，与 TouchpadView 一致）；false 为反向传统滚轮
    var isNaturalDirection: Boolean = true

    private var lastY = 0f
    private var wheelAccumulator = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, cn.moonify.aircursor.R.color.wheel_bg))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

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
                lastY = event.y
                wheelAccumulator = 0f
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
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

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                wheelAccumulator = 0f
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
