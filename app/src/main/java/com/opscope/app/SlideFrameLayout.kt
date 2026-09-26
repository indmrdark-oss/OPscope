package com.opscope.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

class SlideFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onLeftEdgeSwipe: (() -> Unit)? = null

    private val edgePx = 48f * resources.displayMetrics.density
    private val slopPx = 24f
    private var downX = 0f
    private var downY = 0f
    private var inEdge = false
    private var claimed = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                inEdge = ev.x < edgePx
                claimed = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!inEdge || claimed) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (dx < -slopPx && abs(dx) > abs(dy)) claimed = true
                return claimed
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> claimed = false
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!claimed) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> return true
            MotionEvent.ACTION_UP -> {
                if (ev.x - downX < -width * 0.15f) onLeftEdgeSwipe?.invoke()
                claimed = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                claimed = false
                return true
            }
        }
        return true
    }
}
