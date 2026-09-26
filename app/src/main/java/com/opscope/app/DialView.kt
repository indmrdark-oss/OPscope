package com.opscope.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin

class DialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onFrequency: ((Double) -> Unit)? = null
    var onCommit: ((Double) -> Unit)? = null

    var scaleMode: String = "linear"
        set(value) {
            field = value
            if (value == "precision") syncPrecisionSweep()
            invalidate()
        }

    var currentFrequency: Double = 1000.0
        private set

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val minFrequency = 1.0
    private val maxFrequency = 20000.0
    private val startAngle = -135.0
    private val endAngle = 135.0

    private val precisionDegPerDecade = 720.0
    private val precisionMaxSweep = precisionDegPerDecade * log10(maxFrequency / minFrequency)
    private var precisionSweep = precisionDegPerDecade * 3.0

    private var touching = false
    private var grabOffset = 0.0
    private var lastFingerAngle = 0.0

    fun setFrequency(f: Double) {
        currentFrequency = f.coerceIn(minFrequency, maxFrequency)
        if (scaleMode == "precision") syncPrecisionSweep()
        invalidate()
    }

    private fun syncPrecisionSweep() {
        precisionSweep = precisionDegPerDecade * log10(currentFrequency / minFrequency)
    }

    private fun angleForFrequency(f: Double): Double {
        val clamped = f.coerceIn(minFrequency, maxFrequency)
        val t = if (scaleMode == "log") {
            (log10(clamped) - log10(minFrequency)) / (log10(maxFrequency) - log10(minFrequency))
        } else {
            (clamped - minFrequency) / (maxFrequency - minFrequency)
        }
        return startAngle + t.coerceIn(0.0, 1.0) * (endAngle - startAngle)
    }

    private fun frequencyForAngle(a: Double): Double {
        val t = ((a - startAngle) / (endAngle - startAngle)).coerceIn(0.0, 1.0)
        return if (scaleMode == "log") {
            Math.pow(10.0, log10(minFrequency) + t * (log10(maxFrequency) - log10(minFrequency)))
        } else {
            minFrequency + t * (maxFrequency - minFrequency)
        }
    }

    private fun frequencyForSweep(s: Double): Double =
        (minFrequency * Math.pow(10.0, s / precisionDegPerDecade)).coerceIn(minFrequency, maxFrequency)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.38f
        val precision = scaleMode == "precision"

        paint.style = Paint.Style.FILL
        paint.color = 0xFF181818.toInt()
        canvas.drawCircle(cx, cy, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.color = 0xFF555555.toInt()
        canvas.drawCircle(cx, cy, radius, paint)

        val refRad = Math.toRadians(startAngle)
        val referenceRadius = radius * 0.90f
        paint.strokeWidth = 5f
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawLine(
            cx + cos(refRad).toFloat() * (radius * 0.72f),
            cy + sin(refRad).toFloat() * (radius * 0.72f),
            cx + cos(refRad).toFloat() * referenceRadius,
            cy + sin(refRad).toFloat() * referenceRadius,
            paint
        )
        val endRad = Math.toRadians(endAngle)
        canvas.drawLine(
            cx + cos(endRad).toFloat() * (radius * 0.72f),
            cy + sin(endRad).toFloat() * (radius * 0.72f),
            cx + cos(endRad).toFloat() * referenceRadius,
            cy + sin(endRad).toFloat() * referenceRadius,
            paint
        )

        paint.style = Paint.Style.FILL
        paint.textSize = radius * 0.12f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.color = 0xFFAAAAAA.toInt()
        if (!precision) {
            canvas.drawText("1 Hz", cx + cos(refRad).toFloat() * (radius * 0.55f), cy + sin(refRad).toFloat() * (radius * 0.55f), paint)
            canvas.drawText("20k", cx + cos(endRad).toFloat() * (radius * 0.55f), cy + sin(endRad).toFloat() * (radius * 0.55f), paint)
        } else {
            paint.textSize = radius * 0.09f
            canvas.drawText("2 turns = ×10 / ÷10", cx, cy + radius * 0.62f, paint)
        }

        paint.textSize = radius * 0.10f
        paint.color = 0xFF6F9A80.toInt()
        canvas.drawText(
            when (scaleMode) {
                "log" -> "LOG"
                "precision" -> "PREC"
                else -> "LIN"
            },
            cx, cy + radius * 0.45f, paint
        )

        val angle = if (precision) precisionSweep else angleForFrequency(currentFrequency)
        val rad = Math.toRadians(angle)
        val needleLength = radius * 0.68f
        paint.strokeWidth = 8f
        paint.color = 0xFFFF4444.toInt()
        canvas.drawLine(
            cx, cy,
            cx + cos(rad).toFloat() * needleLength,
            cy + sin(rad).toFloat() * needleLength,
            paint
        )
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, 10f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touching = true
                lastFingerAngle = angleFromPoint(event.x, event.y, cx, cy)
                grabOffset = lastFingerAngle - angleForFrequency(currentFrequency)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!touching) return true
                parent?.requestDisallowInterceptTouchEvent(true)

                val finger = angleFromPoint(event.x, event.y, cx, cy)

                if (scaleMode == "precision") {
                    var delta = finger - lastFingerAngle
                    while (delta > 180.0) delta -= 360.0
                    while (delta < -180.0) delta += 360.0
                    lastFingerAngle = finger

                    precisionSweep = (precisionSweep + delta).coerceIn(0.0, precisionMaxSweep)
                    val newFreq = frequencyForSweep(precisionSweep)
                    if (Math.abs(newFreq - currentFrequency) > 0.0005) {
                        currentFrequency = newFreq
                        invalidate()
                        onFrequency?.invoke(currentFrequency)
                    }
                    return true
                }

                val needleNow = angleForFrequency(currentFrequency)
                var target = finger - grabOffset

                while (target - needleNow > 180.0) target -= 360.0
                while (needleNow - target > 180.0) target += 360.0

                target = target.coerceIn(startAngle, endAngle)

                val newFreq = frequencyForAngle(target)
                if (Math.abs(newFreq - currentFrequency) > 0.0005) {
                    currentFrequency = newFreq
                    invalidate()
                    onFrequency?.invoke(currentFrequency)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    onCommit?.invoke(currentFrequency)
                }
                return true
            }
        }

        return true
    }

    private fun angleFromPoint(x: Float, y: Float, cx: Float, cy: Float): Double {
        return Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble()))
    }
}
