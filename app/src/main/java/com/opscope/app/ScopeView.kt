package com.opscope.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class ScopeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#050A06") }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(34, 77, 255, 160)
        strokeWidth = 1f
    }
    private val midlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 77, 255, 160)
        strokeWidth = 1f
    }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DFFA0")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DFFA0")
        textSize = 28f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6F9A80")
        textSize = 22f
    }
    private val zoomLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6AA8FF")
        textSize = 22f
    }

    var capturedSamples: IntArray? = null
        private set
    var fidelityLabel: String = "REAL ADC · WAITING FOR CAPTURE"
        private set
    var capturedSampleRateHz: Double = 0.0
        private set

    private var zoomLevel = 1f
    private val minZoom = 1f
    private val maxZoom = 32f
    private var panCenter = 0.5f

    private fun hasSamples(): Boolean {
        val s = capturedSamples
        return s != null && s.isNotEmpty()
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!hasSamples()) return false
                zoomLevel = (zoomLevel * detector.scaleFactor).coerceIn(minZoom, maxZoom)
                invalidate()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (!hasSamples() || width <= 0) return false
                val windowFraction = 1f / zoomLevel
                val deltaFraction = (distanceX / width) * windowFraction
                panCenter = (panCenter + deltaFraction).coerceIn(
                    windowFraction / 2f,
                    1f - windowFraction / 2f
                )
                invalidate()
                return true
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val cols = 12
        val rows = 6
        for (i in 0..cols) canvas.drawLine(w / cols * i, 0f, w / cols * i, h, gridPaint)
        for (i in 0..rows) canvas.drawLine(0f, h / rows * i, w, h / rows * i, gridPaint)
        canvas.drawLine(0f, h / 2f, w, h / 2f, midlinePaint)

        canvas.drawText(fidelityLabel, 20f, 28f, labelPaint)

        val samples = capturedSamples
        if (samples == null || samples.isEmpty()) {
            canvas.drawText("CONNECT ESP32 · START LIVE CAPTURE", 20f, h / 2f, mutedPaint)
            canvas.drawText("NO SYNTHETIC WAVEFORM", 20f, h / 2f + 32f, mutedPaint)
            return
        }

        if (zoomLevel > 1.01f) {
            canvas.drawText(
                "ZOOM %.1fx".format(zoomLevel),
                max(20f, w - 150f),
                28f,
                zoomLabelPaint
            )
        }
        drawCaptured(canvas, w, h, samples)
    }

    private fun visibleWindow(totalSamples: Int): Pair<Int, Int> {
        val windowSize = (totalSamples / zoomLevel).toInt().coerceAtLeast(2)
        val centerIdx = (panCenter * (totalSamples - 1)).toInt()
        var start = centerIdx - windowSize / 2
        var end = start + windowSize
        if (start < 0) {
            end -= start
            start = 0
        }
        if (end > totalSamples) {
            start -= end - totalSamples
            end = totalSamples
        }
        return start.coerceAtLeast(0) to min(end, totalSamples)
    }

    private fun drawCaptured(canvas: Canvas, w: Float, h: Float, samples: IntArray) {
        val (start, end) = visibleWindow(samples.size)
        val count = end - start
        if (count < 2) return

        var minSample = samples[start]
        var maxSample = samples[start]
        for (i in start until end) {
            minSample = min(minSample, samples[i])
            maxSample = max(maxSample, samples[i])
        }
        val span = (maxSample - minSample).coerceAtLeast(1)
        val top = h * 0.12f
        val bottom = h * 0.88f

        var previousX = 0f
        var previousY = 0f
        for (i in 0 until count) {
            val value = (samples[start + i] - minSample).toFloat() / span
            val x = i.toFloat() / (count - 1) * w
            val y = bottom - value * (bottom - top)
            if (i > 0) canvas.drawLine(previousX, previousY, x, y, tracePaint)
            previousX = x
            previousY = y
        }
    }

    fun showCaptured(samples: IntArray, label: String, sampleRateHz: Double) {
        capturedSamples = samples.copyOf()
        fidelityLabel = label
        capturedSampleRateHz = sampleRateHz
        zoomLevel = 1f
        panCenter = 0.5f
        invalidate()
    }

    fun clearCapture() {
        capturedSamples = null
        fidelityLabel = "REAL ADC · WAITING FOR CAPTURE"
        capturedSampleRateHz = 0.0
        zoomLevel = 1f
        panCenter = 0.5f
        invalidate()
    }

    fun showReconstructed() = clearCapture()

    fun zoomIn() {
        if (!hasSamples()) return
        zoomLevel = (zoomLevel * 1.5f).coerceIn(minZoom, maxZoom)
        invalidate()
    }

    fun zoomOut() {
        if (!hasSamples()) return
        zoomLevel = (zoomLevel / 1.5f).coerceIn(minZoom, maxZoom)
        invalidate()
    }

    fun resetZoom() {
        zoomLevel = minZoom
        panCenter = 0.5f
        invalidate()
    }
}
