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
    var sensitivity: Float = 0.5f

    private var lastY = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setBackgroundColor(Color.parseColor("#1E222B"))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Draw central track line
        paint.color = Color.parseColor("#3A4050")
        paint.strokeWidth = 3f * resources.displayMetrics.density
        canvas.drawLine(w / 2f, h * 0.1f, w / 2f, h * 0.9f, paint)

        // Draw small horizontal notches
        paint.color = Color.parseColor("#60687A")
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
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastY
                lastY = event.y

                // Moving finger UP scrolls UP (positive wheel in HID mouse)
                // Moving finger DOWN scrolls DOWN (negative wheel in HID mouse)
                val wheel = (-dy * sensitivity).toInt().coerceIn(-127, 127)
                if (wheel != 0) {
                    callback?.onWheel(wheel)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
