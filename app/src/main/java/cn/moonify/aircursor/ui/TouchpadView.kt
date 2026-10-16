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
    }

    var callback: Callback? = null
    var sensitivity: Float = 1.2f

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var totalDistance = 0f

    private val maxTapDurationMs = 180L
    private val maxTapDistancePx = 15f * resources.displayMetrics.density

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
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
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
                return true
            }

            MotionEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - downTime
                if (duration <= maxTapDurationMs && totalDistance <= maxTapDistancePx) {
                    callback?.onTap()
                }
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
