package com.opscope.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

// Live streaming scope. Samples are pushed in continuously with feed(); every
// frame draws the newest few cycles, locked to a rising edge so a steady signal
// stands still (like a real scope's trigger). Slow signals (< 5 Hz) just roll.
class ScopeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#050A06") }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(34, 77, 255, 160); strokeWidth = 1f
    }
    private val midlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 77, 255, 160); strokeWidth = 1f
    }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DFFA0"); strokeWidth = 3f
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DFFA0"); textSize = 28f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6F9A80"); textSize = 22f
    }
    private val zoomLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6AA8FF"); textSize = 22f
    }

    private val CAP = 16384
    private val ring = IntArray(CAP)
    private var head = 0L                       // total samples ever written
    private val lock = Any()

    @Volatile var sampleRateHz = 0.0
        private set
    @Volatile var targetFreqHz = 1000.0
    @Volatile var avgCounts = 0.0
        private set
    @Volatile var lastDataMs = 0L
        private set

    private var zoomLevel = 1f
    private val minZoom = 1f
    private val maxZoom = 16f

    private val path = Path()
    private val win = IntArray(CAP / 2)

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomLevel = (zoomLevel * detector.scaleFactor).coerceIn(minZoom, maxZoom)
                postInvalidateOnAnimation()
                return true
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }

    // Safe to call from any thread.
    fun feed(samples: IntArray, count: Int, rateHz: Double) {
        synchronized(lock) {
            for (i in 0 until count) ring[((head + i) % CAP).toInt()] = samples[i]
            head += count
        }
        if (rateHz > 0) sampleRateHz = rateHz
        lastDataMs = SystemClock.elapsedRealtime()
        postInvalidateOnAnimation()
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

        val now = SystemClock.elapsedRealtime()
        val fresh = lastDataMs != 0L && now - lastDataMs < 1500L
        val rate = sampleRateHz

        // ---- copy out the window to draw ----
        var n = 0
        synchronized(lock) {
            val avail = min(head, CAP.toLong()).toInt()
            if (avail >= 8 && rate > 0) {
                val f = targetFreqHz.coerceAtLeast(0.5)
                val period = (rate / f).coerceAtLeast(4.0)
                val wantW = (period * 3.0 / zoomLevel).toInt().coerceIn(32, win.size)
                val wSize = min(wantW, avail)
                var startAbs = head - wSize                       // newest window, no trigger

                if (f >= 5.0 && avail >= wSize + 2) {
                    // level from the newest window
                    var lo = Int.MAX_VALUE; var hi = Int.MIN_VALUE
                    for (i in 0 until wSize) {
                        val v = ring[((startAbs + i) % CAP).toInt()]
                        if (v < lo) lo = v
                        if (v > hi) hi = v
                    }
                    if (hi - lo > 40) {
                        val level = (lo + hi) / 2
                        val oldest = head - avail + 1
                        val searchEnd = max(oldest, startAbs - period.toLong())
                        var i = startAbs
                        while (i >= searchEnd) {
                            val a = ring[((i - 1 + CAP) % CAP).toInt()]
                            val b = ring[(i % CAP).toInt()]
                            if (a < level && b >= level) { startAbs = i; break }
                            i--
                        }
                    }
                }
                for (i in 0 until wSize) win[i] = ring[((startAbs + i) % CAP).toInt()]
                n = wSize
            }
        }

        val status = when {
            n == 0 -> "REAL ADC · WAITING FOR STREAM"
            fresh -> "LIVE · " + formatRate(rate)
            else -> "NO DATA"
        }
        canvas.drawText(status, 20f, 28f, labelPaint)

        if (n < 2) {
            canvas.drawText("CONNECT ESP32 · STREAM STARTS AUTOMATICALLY", 20f, h / 2f, mutedPaint)
            return
        }
        if (zoomLevel > 1.01f) {
            canvas.drawText("ZOOM %.1fx".format(zoomLevel), max(20f, w - 150f), 28f, zoomLabelPaint)
        }

        // ---- scale ----
        var lo = win[0]; var hi = win[0]; var sum = 0L
        for (i in 0 until n) {
            val v = win[i]
            if (v < lo) lo = v
            if (v > hi) hi = v
            sum += v
        }
        avgCounts = sum.toDouble() / n
        if (hi - lo < 200) {                       // flat signal: don't blow up noise
            val mid = (hi + lo) / 2
            lo = mid - 100; hi = mid + 100
        }
        val span = (hi - lo).toFloat()
        val top = h * 0.12f
        val bottom = h * 0.88f

        // ---- trace ----
        path.rewind()
        val step = max(1, (n / (w * 1.5f)).toInt())
        var i = 0
        var first = true
        while (i < n) {
            val x = i.toFloat() / (n - 1) * w
            val y = bottom - (win[i] - lo) / span * (bottom - top)
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            i += step
        }
        canvas.drawPath(path, tracePaint)

        canvas.drawText("%.2fV".format(hi / 4095.0 * 3.3), 20f, top - 6f, mutedPaint)
        canvas.drawText("%.2fV".format(lo / 4095.0 * 3.3), 20f, bottom + 24f, mutedPaint)
    }

    private fun formatRate(r: Double): String =
        if (r >= 1000) "%.1f kS/s".format(r / 1000.0) else "%.0f S/s".format(r)

    fun clearCapture() {
        synchronized(lock) { head = 0L }
        sampleRateHz = 0.0
        lastDataMs = 0L
        avgCounts = 0.0
        zoomLevel = 1f
        postInvalidateOnAnimation()
    }

    fun zoomIn() { zoomLevel = (zoomLevel * 1.5f).coerceIn(minZoom, maxZoom); postInvalidateOnAnimation() }
    fun zoomOut() { zoomLevel = (zoomLevel / 1.5f).coerceIn(minZoom, maxZoom); postInvalidateOnAnimation() }
    fun resetZoom() { zoomLevel = minZoom; postInvalidateOnAnimation() }
}
