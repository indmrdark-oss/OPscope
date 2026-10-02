package com.opscope.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private var serial: UsbSerialManager? = null

    private lateinit var scopeView: ScopeView
    private lateinit var dialView: DialView

    private lateinit var slideRoot: SlideFrameLayout
    private lateinit var mainPage: View
    private lateinit var logbookPage: View
    private lateinit var scrimView: View
    private lateinit var drawerView: View
    private lateinit var menuBtnMain: View
    private lateinit var menuBtnLog: View
    private lateinit var drawerMainOpt: LinearLayout
    private lateinit var drawerLogOpt: LinearLayout

    private lateinit var liveBtn: Button
    private lateinit var refreshFilesBtn: Button
    private lateinit var viewerTitle: TextView
    private lateinit var logbookView: TextView
    private lateinit var logbookScroll: ScrollView
    private lateinit var sessionsList: LinearLayout

    private lateinit var connStatus: TextView
    private lateinit var connectBtn: Button
    private lateinit var captureChip: TextView
    private lateinit var streamText: TextView

    private lateinit var zoomInBtn: View
    private lateinit var zoomOutBtn: View
    private lateinit var resetZoomBtn: View

    private lateinit var logView: TextView
    private lateinit var clearLogBtn: Button

    private lateinit var rTarget: TextView
    private lateinit var rTargetBig: TextView
    private lateinit var rMeasured: TextView
    private lateinit var rDuty: TextView
    private lateinit var rMax: TextView
    private lateinit var rMin: TextView
    private lateinit var rError: TextView
    private lateinit var rVoltage: TextView
    private lateinit var lockStatusText: TextView
    private lateinit var outStateBadge: TextView
    private lateinit var pendText: TextView

    private lateinit var coarseModeBtn: Button
    private lateinit var fineModeBtn: Button
    private lateinit var precisionBtn: Button
    private lateinit var lockBtn: Button

    private lateinit var downFreqBtn: Button
    private lateinit var upFreqBtn: Button
    private lateinit var speed1xBtn: Button
    private lateinit var speed2xBtn: Button
    private lateinit var minus1Btn: Button
    private lateinit var minus01Btn: Button
    private lateinit var plus01Btn: Button
    private lateinit var plus1Btn: Button

    private lateinit var btnCmdH: Button
    private lateinit var btnCmdM: Button
    private lateinit var btnCmdD: Button
    private lateinit var btnCmdC: Button
    private lateinit var btnCmdS: Button

    private lateinit var manualFreqRow: LinearLayout
    private lateinit var manualFreqInput: EditText
    private lateinit var manualFreqSetBtn: Button
    private lateinit var manualDutyRow: LinearLayout
    private lateinit var manualDutyInput: EditText
    private lateinit var manualDutySetBtn: Button

    private lateinit var protStrip: LinearLayout
    private lateinit var protTitle: TextView
    private lateinit var protSub: TextView
    private lateinit var resetBtn: Button

    private lateinit var sessionLogger: SessionLogger

    private val ACTION_USB_PERMISSION = "com.opscope.app.USB_PERMISSION"
    private val BAUD_RATE = 250000

    private val handler = Handler(Looper.getMainLooper())
    private var readThread: Thread? = null

    @Volatile private var keepReading = false

    private val F_MIN = 1.0
    private val F_MAX = 20000.0

    // outputFrequency: what's actually commanded to the ESP32.
    // dialPreview: wherever the dial / nudge controls currently sit (always tracks touch/taps).
    // While isLocked, dialPreview can move freely but outputFrequency does not.
    private var outputFrequency = 1000.0
    private var dialPreview = 1000.0
    private var isLocked = false

    private var isConnected = false
    private var outputEnabled = false
    private var protectionTripped = false

    private var arrowSpeed = 1
    private var repeatDirection = 0
    private var sweepStartFreq = 0.0

    private var liveCapture = false
    private var capturing = false
    private var capRate = 0.0
    private var capSamples: IntArray? = null
    private var capIsReal = false
    private var capInFlight = false
    private var lastCapDurationMs = 400L

    private val pendingLogbookLines = StringBuilder()
    private var logbookFlushScheduled = false
    private var lastMeasuredHz = 0.0
    private var lastWaveform = false
    private var maxMeasuredHz: Double? = null
    private var minMeasuredHz: Double? = null

    private var lastErrWarnMs = 0L
    private var lastCaptureLabel: String? = null
    private var lastShownText = ""
    private var lastShownMs = 0L
    private var dialDragging = false

    private var lastSentFreq = 1000.0
    private var lastFreqTxMs = 0L

    private var currentScreen = 0
    private var drawerOpen = false
    private var drawerAnimating = false
    private var logbookIsLive = true
    private var drawerDownX = 0f

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (repeatDirection == 0) return
            changeFrequencyFromArrow()
            val delay = if (arrowSpeed == 1) 100L else 50L
            handler.postDelayed(this, delay)
        }
    }

    private val liveCaptureLoop = object : Runnable {
        override fun run() {
            if (!liveCapture) return
            if (!capturing && !capInFlight) {
                capInFlight = true
                sessionLogger.log("TX", "C")
                serial?.writeLine("C")
            }
            handler.postDelayed(this, lastCapDurationMs.coerceAtLeast(400L) + 60L)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? =
                        if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        appendLog("USB permission granted.", "info")
                        connectToDevice(device)
                    } else {
                        appendLog("USB permission denied.", "err")
                        sessionLogger.log("SYS", "USB permission denied")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendLog("USB device attached. Tap Connect.", "info")
                    sessionLogger.log("SYS", "USB device attached")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    appendLog("USB device detached.", "warn")
                    sessionLogger.log("SYS", "USB device detached")
                    disconnect("device detached")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        sessionLogger = SessionLogger(this)
        sessionLogger.onLogLine = { line ->
            synchronized(pendingLogbookLines) { pendingLogbookLines.append(line).append('\n') }
            scheduleLogbookFlush()
        }

        scopeView = findViewById(R.id.scopeView)
        dialView = findViewById(R.id.dialView)

        slideRoot = findViewById(R.id.slideRoot)
        mainPage = findViewById(R.id.mainPage)
        logbookPage = findViewById(R.id.logbookPage)
        scrimView = findViewById(R.id.scrimView)
        drawerView = findViewById(R.id.drawerView)
        menuBtnMain = findViewById(R.id.menuBtnMain)
        menuBtnLog = findViewById(R.id.menuBtnLog)
        drawerMainOpt = findViewById(R.id.drawerMainOpt)
        drawerLogOpt = findViewById(R.id.drawerLogOpt)

        liveBtn = findViewById(R.id.liveBtn)
        refreshFilesBtn = findViewById(R.id.refreshFilesBtn)
        viewerTitle = findViewById(R.id.viewerTitle)
        logbookView = findViewById(R.id.logbookView)
        logbookScroll = findViewById(R.id.logbookScroll)
        sessionsList = findViewById(R.id.sessionsList)

        connStatus = findViewById(R.id.connStatus)
        connectBtn = findViewById(R.id.connectBtn)
        captureChip = findViewById(R.id.captureChip)
        streamText = findViewById(R.id.streamText)

        zoomInBtn = findViewById(R.id.zoomInBtn)
        zoomOutBtn = findViewById(R.id.zoomOutBtn)
        resetZoomBtn = findViewById(R.id.resetZoomBtn)

        logView = findViewById(R.id.logView)
        clearLogBtn = findViewById(R.id.clearLogBtn)

        rTarget = findViewById(R.id.rTarget)
        rTargetBig = findViewById(R.id.rTargetBig)
        rMeasured = findViewById(R.id.rMeasured)
        rDuty = findViewById(R.id.rDuty)
        rMax = findViewById(R.id.rMax)
        rMin = findViewById(R.id.rMin)
        rError = findViewById(R.id.rError)
        rVoltage = findViewById(R.id.rVoltage)
        lockStatusText = findViewById(R.id.lockStatusText)
        outStateBadge = findViewById(R.id.outStateBadge)
        pendText = findViewById(R.id.pendText)

        coarseModeBtn = findViewById(R.id.coarseModeBtn)
        fineModeBtn = findViewById(R.id.fineModeBtn)
        precisionBtn = findViewById(R.id.precisionBtn)
        lockBtn = findViewById(R.id.lockBtn)

        downFreqBtn = findViewById(R.id.downFreqBtn)
        upFreqBtn = findViewById(R.id.upFreqBtn)
        speed1xBtn = findViewById(R.id.speed1xBtn)
        speed2xBtn = findViewById(R.id.speed2xBtn)
        minus1Btn = findViewById(R.id.minus1Btn)
        minus01Btn = findViewById(R.id.minus01Btn)
        plus01Btn = findViewById(R.id.plus01Btn)
        plus1Btn = findViewById(R.id.plus1Btn)

        btnCmdH = findViewById(R.id.btnCmdH)
        btnCmdM = findViewById(R.id.btnCmdM)
        btnCmdD = findViewById(R.id.btnCmdD)
        btnCmdC = findViewById(R.id.btnCmdC)
        btnCmdS = findViewById(R.id.btnCmdS)

        manualFreqRow = findViewById(R.id.manualFreqRow)
        manualFreqInput = findViewById(R.id.manualFreqInput)
        manualFreqSetBtn = findViewById(R.id.manualFreqSetBtn)
        manualDutyRow = findViewById(R.id.manualDutyRow)
        manualDutyInput = findViewById(R.id.manualDutyInput)
        manualDutySetBtn = findViewById(R.id.manualDutySetBtn)

        protStrip = findViewById(R.id.protStrip)
        protTitle = findViewById(R.id.protTitle)
        protSub = findViewById(R.id.protSub)
        resetBtn = findViewById(R.id.resetBtn)

        setupUsbReceiver()
        setupButtons()
        setupDial()
        setupDrawer()

        updateFreqDisplay()
        styleModeButtons("real")
        styleLockButton()
        styleProtection()
        styleConnState()
        styleToggle(speed1xBtn, true)
        appendLog("OPscope ready. Output stays off until Connect.", "info")
    }

    private fun setupUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun setupButtons() {
        connectBtn.setOnClickListener {
            if (isConnected) disconnect("user requested") else requestDevice()
        }

        clearLogBtn.setOnClickListener { logView.text = "" }

        liveBtn.setOnClickListener {
            logbookIsLive = true
            viewerTitle.text = "Live session"
            logbookScroll.post { logbookScroll.fullScroll(View.FOCUS_DOWN) }
        }
        refreshFilesBtn.setOnClickListener { refreshSessionList() }

        btnCmdH.setOnClickListener { fireSimpleCommand("H", "AI> help requested") }
        btnCmdS.setOnClickListener { fireSimpleCommand("S", "AI> diagnostics requested") }

        btnCmdC.setOnClickListener {
            if (!requireConnected()) return@setOnClickListener
            liveCapture = !liveCapture
            styleToggle(btnCmdC, liveCapture)
            if (liveCapture) {
                lastCaptureLabel = null
                appendLog("Live capture ON.", "ok")
                handler.post(liveCaptureLoop)
            } else {
                appendLog("Live capture OFF.", "info")
            }
            sessionLogger.log("APP", if (liveCapture) "live capture ON" else "live capture OFF")
        }

        btnCmdM.setOnClickListener {
            if (!requireConnected()) return@setOnClickListener
            manualDutyRow.visibility = View.GONE
            styleToggle(btnCmdD, false)
            val show = manualFreqRow.visibility != View.VISIBLE
            manualFreqRow.visibility = if (show) View.VISIBLE else View.GONE
            styleToggle(btnCmdM, show)
            if (show) {
                manualFreqInput.setText(String.format(Locale.US, "%.2f", dialPreview))
                manualFreqInput.requestFocus()
            }
        }
        manualFreqSetBtn.setOnClickListener { submitManualFrequency() }
        manualFreqInput.setOnEditorActionListener { _, _, _ -> submitManualFrequency(); true }

        btnCmdD.setOnClickListener {
            if (!requireConnected()) return@setOnClickListener
            manualFreqRow.visibility = View.GONE
            styleToggle(btnCmdM, false)
            val show = manualDutyRow.visibility != View.VISIBLE
            manualDutyRow.visibility = if (show) View.VISIBLE else View.GONE
            styleToggle(btnCmdD, show)
            if (show) manualDutyInput.requestFocus()
        }
        manualDutySetBtn.setOnClickListener { submitManualDuty() }
        manualDutyInput.setOnEditorActionListener { _, _, _ -> submitManualDuty(); true }

        zoomInBtn.setOnClickListener { scopeView.zoomIn() }
        zoomOutBtn.setOnClickListener { scopeView.zoomOut() }
        resetZoomBtn.setOnClickListener { scopeView.resetZoom() }

        coarseModeBtn.setOnClickListener {
            dialView.scaleMode = "linear"
            dialView.setFrequency(dialPreview)
            styleModeButtons("real")
        }
        fineModeBtn.setOnClickListener {
            dialView.scaleMode = "log"
            dialView.setFrequency(dialPreview)
            styleModeButtons("precise")
        }
        precisionBtn.setOnClickListener {
            dialView.scaleMode = "precision"
            dialView.setFrequency(dialPreview)
            styleModeButtons("fast")
        }

        lockBtn.setOnClickListener {
            isLocked = !isLocked
            dialView.isLocked = isLocked
            if (!isLocked && Math.abs(dialPreview - outputFrequency) > 0.004) {
                outputFrequency = dialPreview
                updateFreqDisplay()
                sendFrequency()
                appendLog("L OFF · applied ${String.format(Locale.US, "%.2f Hz", outputFrequency)}", "ok")
            } else {
                appendLog(if (isLocked) "L ON · output held, dial/nudges ignored" else "L OFF · unlocked", "warn")
            }
            styleLockButton()
            updateFreqDisplay()
            sessionLogger.log("APP", if (isLocked) "frequency lock ON" else "frequency lock OFF")
        }

        resetBtn.setOnClickListener {
            if (!requireConnected()) return@setOnClickListener
            sessionLogger.log("TX", "H")
            serial?.writeLine("H")
            appendLog("Reset sent (H).", "info")
        }

        speed1xBtn.setOnClickListener { arrowSpeed = 1; styleToggle(speed1xBtn, true); styleToggle(speed2xBtn, false) }
        speed2xBtn.setOnClickListener { arrowSpeed = 2; styleToggle(speed2xBtn, true); styleToggle(speed1xBtn, false) }

        minus1Btn.setOnClickListener { nudgeFrequency(-1.0) }
        minus01Btn.setOnClickListener { nudgeFrequency(-0.1) }
        plus01Btn.setOnClickListener { nudgeFrequency(0.1) }
        plus1Btn.setOnClickListener { nudgeFrequency(1.0) }

        setupArrowButton(downFreqBtn, -1)
        setupArrowButton(upFreqBtn, 1)
    }

    private fun setupArrowButton(button: Button, direction: Int) {
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    repeatDirection = direction
                    sweepStartFreq = dialPreview
                    changeFrequencyFromArrow()
                    handler.removeCallbacks(repeatRunnable)
                    handler.postDelayed(repeatRunnable, 250L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatDirection = 0
                    handler.removeCallbacks(repeatRunnable)
                    true
                }
                else -> true
            }
        }
    }

    private fun changeFrequencyFromArrow() {
        val step = if (arrowSpeed == 1) 1.0 else 5.0
        applyFrequencyChange((dialPreview + repeatDirection * step).coerceIn(F_MIN, F_MAX))
    }

    private fun nudgeFrequency(amount: Double) {
        applyFrequencyChange((dialPreview + amount).coerceIn(F_MIN, F_MAX))
        sessionLogger.log("APP", "nudge ${if (amount > 0) "+" else ""}$amount")
    }

    // Shared by dial, nudge buttons and arrow-hold: moves the preview, and
    // only pushes to outputFrequency / the ESP32 when not locked.
    private fun applyFrequencyChange(newPreview: Double) {
        dialPreview = newPreview
        dialView.setFrequency(dialPreview)
        if (!isLocked) {
            outputFrequency = dialPreview
            throttledFrequencySend()
        }
        updateFreqDisplay()
    }

    private fun requireConnected(): Boolean {
        if (!isConnected) {
            appendLog("Choose a transport and connect first.", "err")
            return false
        }
        return true
    }

    private fun fireSimpleCommand(cmd: String, logMsg: String) {
        if (!requireConnected()) return
        sessionLogger.log("TX", cmd)
        serial?.writeLine(cmd)
        appendLog(logMsg, "info")
    }

    private fun submitManualFrequency() {
        val value = manualFreqInput.text.toString().trim().toDoubleOrNull()
        if (value == null) {
            appendLog("Enter a valid frequency first.", "err")
            return
        }
        val clamped = value.coerceIn(F_MIN, F_MAX)
        applyFrequencyChange(clamped)
        if (isLocked) {
            appendLog("Frequency staged at ${String.format(Locale.US, "%.2f Hz", clamped)} — unlock (L) to apply.", "warn")
        } else {
            sendFrequency()
        }
        manualFreqRow.visibility = View.GONE
        styleToggle(btnCmdM, false)
    }

    private fun submitManualDuty() {
        val value = manualDutyInput.text.toString().trim().toDoubleOrNull()
        if (value == null) {
            appendLog("Enter a valid duty percentage first.", "err")
            return
        }
        val clamped = value.coerceIn(0.0, 100.0)
        val command = String.format(Locale.US, "P%.1f", clamped)
        sessionLogger.log("TX", command)
        serial?.writeLine(command)
        appendLog("Duty set → ${String.format(Locale.US, "%.1f%%", clamped)}", "info")
        manualDutyRow.visibility = View.GONE
        styleToggle(btnCmdD, false)
    }

    private fun setupDial() {
        dialView.onFrequency = { f ->
            dialDragging = true
            applyFrequencyChange(f)
        }
        dialView.onCommit = { f ->
            dialDragging = false
            applyFrequencyChange(f)
            if (!isLocked && Math.abs(f - lastSentFreq) > 0.0049) {
                sendFrequency()
                sessionLogger.log("APP", "dial → ${String.format(Locale.US, "%.2f Hz", f)}")
            } else if (isLocked) {
                sessionLogger.log("APP", "dial moved to ${String.format(Locale.US, "%.2f Hz", f)} while locked (ignored)")
            }
        }
    }

    private fun setupDrawer() {
        slideRoot.onLeftEdgeSwipe = { openDrawer() }
        menuBtnMain.setOnClickListener { openDrawer() }
        menuBtnLog.setOnClickListener { openDrawer() }
        scrimView.setOnClickListener { closeDrawer() }
        drawerMainOpt.setOnClickListener { goMain() }
        drawerLogOpt.setOnClickListener { goLogbook() }
        drawerView.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> drawerDownX = e.x
                MotionEvent.ACTION_UP -> if (e.x - drawerDownX > 120f) closeDrawer()
            }
            false
        }
    }

    private fun drawerWidth(): Float =
        if (drawerView.width > 0) drawerView.width.toFloat()
        else (220f * resources.displayMetrics.density)

    private fun openDrawer() {
        if (drawerOpen || drawerAnimating) return
        drawerAnimating = true
        updateDrawerHighlight()
        scrimView.visibility = View.VISIBLE
        drawerView.visibility = View.VISIBLE
        drawerView.translationX = -drawerWidth()
        drawerView.animate().translationX(0f).setDuration(200).withEndAction {
            drawerAnimating = false; drawerOpen = true
        }.start()
    }

    private fun closeDrawer() {
        if (!drawerOpen || drawerAnimating) return
        drawerAnimating = true
        scrimView.visibility = View.GONE
        drawerView.animate().translationX(-drawerWidth()).setDuration(200).withEndAction {
            drawerView.visibility = View.GONE; drawerAnimating = false; drawerOpen = false
        }.start()
    }

    private fun goMain() {
        if (currentScreen != 0) {
            currentScreen = 0
            logbookPage.visibility = View.GONE
            mainPage.visibility = View.VISIBLE
        }
        closeDrawer()
    }

    private fun goLogbook() {
        if (currentScreen != 1) {
            currentScreen = 1
            mainPage.visibility = View.GONE
            logbookPage.visibility = View.VISIBLE
            refreshSessionList()
        }
        closeDrawer()
    }

    private fun updateDrawerHighlight() {
        val activeBg = 0xFF122038.toInt(); val idleBg = 0x00000000
        val activeColor = 0xFF3D6FA8.toInt(); val idleColor = 0xFFBFD4EE.toInt()
        val mainTitle = drawerMainOpt.getChildAt(0) as TextView
        val logTitle = drawerLogOpt.getChildAt(0) as TextView
        drawerMainOpt.setBackgroundColor(if (currentScreen == 0) activeBg else idleBg)
        mainTitle.setTextColor(if (currentScreen == 0) activeColor else idleColor)
        drawerLogOpt.setBackgroundColor(if (currentScreen == 1) activeBg else idleBg)
        logTitle.setTextColor(if (currentScreen == 1) activeColor else idleColor)
    }

    private fun scheduleLogbookFlush() {
        if (logbookFlushScheduled) return
        logbookFlushScheduled = true
        handler.postDelayed({ flushLogbookLines() }, 150L)
    }

    private fun flushLogbookLines() {
        logbookFlushScheduled = false
        val chunk: String
        synchronized(pendingLogbookLines) {
            if (pendingLogbookLines.isEmpty()) return
            chunk = pendingLogbookLines.toString()
            pendingLogbookLines.setLength(0)
        }
        if (!logbookIsLive) return
        logbookView.append(chunk)
        val s = logbookView.text.toString()
        if (s.length > 200000) logbookView.setText(s.substring(s.length - 100000))
        val child = logbookScroll.getChildAt(0) ?: return
        val diff = child.bottom - (logbookScroll.height + logbookScroll.scrollY)
        if (diff <= 200) logbookScroll.post { logbookScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refreshSessionList() {
        sessionsList.removeAllViews()
        val files = sessionLogger.listSessions()
        if (files.isEmpty()) {
            val t = makeRow("No saved sessions yet.")
            t.setTextColor(0xFF5A7099.toInt())
            sessionsList.addView(t)
            return
        }
        val dateFmt = SimpleDateFormat("dd MMM yyyy · HH:mm:ss", Locale.US)
        for (f in files) {
            val t = makeRow("${f.name}\n${dateFmt.format(Date(f.lastModified()))} · ${f.length() / 1024} KB")
            t.setTextColor(0xFFBFD4EE.toInt())
            t.isClickable = true
            t.setOnClickListener { openSessionFile(f) }
            sessionsList.addView(t)
        }
    }

    private fun makeRow(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 10.5f
        t.typeface = Typeface.MONOSPACE
        t.setPadding(16, 12, 16, 12)
        return t
    }

    private fun openSessionFile(f: File) {
        logbookIsLive = false
        viewerTitle.text = f.name
        logbookView.text = "Loading…"
        Thread {
            val text = try { f.readText() } catch (e: Exception) { "Failed to read: ${e.message}" }
            val shown = if (text.length > 300000)
                text.substring(0, 300000) + "\n\n…(truncated — ${text.length} chars total)"
            else text
            handler.post { logbookView.text = shown; logbookScroll.scrollTo(0, 0) }
        }.start()
    }

    private fun updateFreqDisplay() {
        rTargetBig.text = String.format(Locale.US, "%.2f Hz", outputFrequency)
        rTarget.text = String.format(Locale.US, "%.2f Hz", outputFrequency)
        pendText.text = if (isLocked && Math.abs(dialPreview - outputFrequency) > 0.004)
            "DIAL → ${String.format(Locale.US, "%.2f Hz", dialPreview)} (applies on unlock)"
        else ""
        updateOutputBadge()
    }

    private fun updateOutputBadge() {
        when {
            protectionTripped -> {
                outStateBadge.text = "CUT · PROTECTION"
                outStateBadge.setTextColor(Color.parseColor("#FF6B6B"))
            }
            outputEnabled -> {
                outStateBadge.text = "OUTPUT ON"
                outStateBadge.setTextColor(Color.parseColor("#3AC8A0"))
            }
            else -> {
                outStateBadge.text = "OUTPUT OFF"
                outStateBadge.setTextColor(Color.parseColor("#8A97AD"))
            }
        }
        rTargetBig.setTextColor(if (outputEnabled && !protectionTripped) Color.parseColor("#4A90FF") else Color.parseColor("#3A4E70"))
    }

    private fun throttledFrequencySend() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFreqTxMs >= 100L) sendFrequency()
    }

    private fun sendFrequency() {
        val command = String.format(Locale.US, "F%.2f", outputFrequency)
        lastFreqTxMs = SystemClock.elapsedRealtime()
        lastSentFreq = outputFrequency
        sessionLogger.log("TX", command)
        serial?.writeLine(command)
    }

    private fun requestDevice() {
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) { appendLog("No USB device found.", "err"); return }
        val device = devices.first()
        if (usbManager.hasPermission(device)) { connectToDevice(device); return }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
        usbManager.requestPermission(device, permissionIntent)
        appendLog("Requesting USB permission…", "info")
        sessionLogger.log("SYS", "requesting USB permission for ${deviceDesc(device)}")
    }

    private fun connectToDevice(device: UsbDevice) {
        try {
            val manager = UsbSerialManager(usbManager, device)
            val opened = manager.open(BAUD_RATE)
            if (!opened) {
                appendLog("Could not open USB serial port.", "err")
                sessionLogger.log("SYS", "open failed for ${deviceDesc(device)}")
                manager.close()
                return
            }
            serial = manager
            isConnected = true
            maxMeasuredHz = null
            minMeasuredHz = null
            sessionLogger.startSession("${deviceDesc(device)} @ $BAUD_RATE baud")
            startReading()
            appendLog("USB connected.", "ok")

            serial?.writeLine("E1")
            sessionLogger.log("TX", "E1")
            outputEnabled = true
            appendLog("ESP32 output ENABLED.", "ok")

            sendFrequency()
            styleConnState()
            updateFreqDisplay()
        } catch (e: Exception) {
            serial = null
            isConnected = false
            appendLog("Connection failed: ${e.message}", "err")
            sessionLogger.log("SYS", "exception: ${e.message}")
            styleConnState()
        }
    }

    private fun deviceDesc(d: UsbDevice): String =
        String.format(Locale.US, "USB %04X:%04X %s", d.vendorId, d.productId, d.deviceName)

    private fun startReading() {
        if (keepReading) return
        keepReading = true
        readThread = Thread {
            val buffer = ByteArray(4096)
            val lineBuilder = StringBuilder()
            while (keepReading) {
                try {
                    val count = serial?.read(buffer, 100) ?: -1
                    if (count > 0) {
                        val chunk = String(buffer, 0, count, Charsets.US_ASCII)
                        lineBuilder.append(chunk)
                        val batch = ArrayList<String>()
                        while (true) {
                            val newlineIndex = lineBuilder.indexOf("\n")
                            if (newlineIndex < 0) break
                            val line = lineBuilder.substring(0, newlineIndex).trim()
                            lineBuilder.delete(0, newlineIndex + 1)
                            if (line.isNotEmpty()) batch.add(line)
                        }
                        if (batch.isNotEmpty()) {
                            handler.post { for (line in batch) processSerialLine(line) }
                        }
                    }
                } catch (e: Exception) {
                    if (keepReading) {
                        handler.post { appendLog("Read error: ${e.message}", "err") }
                        sessionLogger.log("SYS", "read error: ${e.message}")
                    }
                    break
                }
            }
        }
        readThread?.start()
    }

    private fun processSerialLine(line: String) {
        val text = line.trim()
        if (text.isEmpty()) return

        if (capturing && text.matches(Regex("^[0-9,]+$")) && text.length > 120) {
            sessionLogger.log("RX", text.substring(0, 120) + " …(sample data, ${text.length} chars total)")
        } else {
            sessionLogger.log("RX", text)
        }

        if (text.startsWith("AI>")) {
            val lower = text.lowercase(Locale.US)
            when {
                lower.contains("fault") && lower.contains("detected") -> {
                    protectionTripped = true
                    styleProtection()
                    updateOutputBadge()
                    if (!dialDragging) showOnce(text, "err")
                    return
                }
                lower.contains("fault cleared") -> {
                    protectionTripped = false
                    styleProtection()
                    updateOutputBadge()
                    if (!dialDragging) showOnce(text, "ok")
                    return
                }
            }
            if (!dialDragging) showOnce(text, "info")
            return
        }

        if (text.startsWith("CAP,")) {
            val parts = text.split(",")
            capRate = parts.getOrNull(2)?.toDoubleOrNull() ?: parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            capIsReal = parts.getOrNull(3)?.trim()?.uppercase(Locale.US)?.let { it == "REAL" } ?: true
            if (!capIsReal) {
                capturing = false; capSamples = null
                appendLog("ESP32 sent a non-REAL capture; ignored.", "warn")
                return
            }
            capturing = true; capSamples = null
            return
        }
        if (capturing) {
            if (text == "ENDCAP") { capturing = false; onCaptureComplete(); return }
            if (text.matches(Regex("^[0-9,]+$"))) {
                val arr = text.split(",").mapNotNull { it.trim().toIntOrNull() }.toIntArray()
                val prev = capSamples
                capSamples = if (prev == null) arr else prev.plus(arr)
                return
            }
        }

        if (text.startsWith("Target:")) {
            val measuredHz = Regex("Measured:\\s*([\\d.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            val dutyStr = Regex("Duty:\\s*([\\d.]+)").find(text)?.groupValues?.get(1)
            val errStr = Regex("Err:\\s*([\\d.]+)").find(text)?.groupValues?.get(1)
            val noSignal = text.contains("NO SIGNAL")

            dutyStr?.let { rDuty.text = "$it%" }
            errStr?.let { rError.text = "$it%" }

            lastWaveform = !noSignal
            if (lastWaveform && measuredHz != null) {
                lastMeasuredHz = measuredHz
                maxMeasuredHz = maxMeasuredHz?.let { maxOf(it, measuredHz) } ?: measuredHz
                minMeasuredHz = minMeasuredHz?.let { minOf(it, measuredHz) } ?: measuredHz
                rMax.text = String.format(Locale.US, "%.2f Hz", maxMeasuredHz)
                rMin.text = String.format(Locale.US, "%.2f Hz", minMeasuredHz)
            }
            rMeasured.text = if (lastWaveform && measuredHz != null)
                String.format(Locale.US, "%.2f Hz", measuredHz) else "NO SIGNAL"

            val err = errStr?.toDoubleOrNull() ?: 0.0
            val now = SystemClock.elapsedRealtime()
            if (err > 2.0 && now - lastErrWarnMs > 5000) {
                lastErrWarnMs = now
                appendLog("Err ${errStr}% — measured disagrees with target", "warn")
            }
            return
        }
    }

    private fun onCaptureComplete() {
        capInFlight = false
        val samples = capSamples ?: return
        if (!capIsReal) return
        if (capRate > 0 && samples.isNotEmpty()) {
            lastCapDurationMs = (1000.0 * samples.size / capRate).toLong().coerceIn(50L, 500L)
        }
        if (samples.isNotEmpty()) {
            val maxAdc = if (samples.max() > 1023) 4095.0 else 1023.0
            val avgV = (samples.average() / maxAdc) * 3.3
            rVoltage.text = String.format(Locale.US, "%.2fV", avgV)
        }
        val label = "${samples.size} smp @ ${String.format(Locale.US, "%.0f", capRate)}Hz"
        sessionLogger.log("APP", "capture done: $label")
        if (!liveCapture || label != lastCaptureLabel) {
            lastCaptureLabel = label
            appendLog("Capture: $label", "ok")
        }
        captureChip.text = "LIVE"
        scopeView.showCaptured(samples, "REAL ADC · $label", capRate)
    }

    private fun disconnect(reason: String) {
        val wasConnected = isConnected
        keepReading = false
        repeatDirection = 0
        liveCapture = false
        capturing = false
        capInFlight = false
        handler.removeCallbacks(repeatRunnable)
        styleToggle(btnCmdC, false)
        styleToggle(btnCmdM, false)
        styleToggle(btnCmdD, false)
        manualFreqRow.visibility = View.GONE
        manualDutyRow.visibility = View.GONE
        try { readThread?.interrupt() } catch (_: Exception) {}
        readThread = null

        if (wasConnected) {
            try {
                sessionLogger.log("TX", "E0")
                serial?.writeLine("E0")
            } catch (_: Exception) {}
        }
        try { serial?.close() } catch (_: Exception) {}
        serial = null
        isConnected = false
        outputEnabled = false

        if (wasConnected) {
            rMeasured.text = "--"
            rVoltage.text = "--"
            scopeView.clearCapture()
            captureChip.text = "WAITING"
            lastWaveform = false
            sessionLogger.endSession(reason)
            refreshSessionList()
            appendLog("Disconnected. Output off.", "warn")
        }
        styleConnState()
        updateFreqDisplay()
    }

    private fun appendLog(message: String, kind: String) {
        handler.post {
            val color = when (kind) {
                "err" -> Color.parseColor("#FF6B6B")
                "ok" -> Color.parseColor("#3AC8A0")
                "warn" -> Color.parseColor("#E0A84A")
                else -> Color.parseColor("#7FA6D9")
            }
            val t = SpannableString(message + "\n")
            t.setSpan(ForegroundColorSpan(color), 0, message.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            logView.append(t)
            val parent = logView.parent
            if (parent is ScrollView) parent.post { parent.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun showOnce(text: String, kind: String, gapMs: Long = 2000L) {
        val now = SystemClock.elapsedRealtime()
        if (text == lastShownText && now - lastShownMs < gapMs) return
        lastShownText = text
        lastShownMs = now
        appendLog(text.removePrefix("AI>").trim(), kind)
    }

    private fun styleModeButtons(selected: String) {
        val buttons = listOf("real" to coarseModeBtn, "precise" to fineModeBtn, "fast" to precisionBtn)
        for ((key, button) in buttons) {
            val isSelected = key == selected
            val shape = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(if (isSelected) 3 else 1, if (isSelected) Color.parseColor("#4A90FF") else Color.parseColor("#2C4A72"))
                cornerRadius = 8f
            }
            val rippleColor = ColorStateList.valueOf(Color.parseColor("#663D6FA8"))
            button.background = RippleDrawable(rippleColor, shape, shape)
            button.setTextColor(Color.parseColor("#4A90FF"))
        }
    }

    private fun styleToggle(button: Button, on: Boolean) {
        val shape = GradientDrawable().apply {
            setColor(if (on) Color.parseColor("#14284A") else Color.TRANSPARENT)
            setStroke(2, if (on) Color.parseColor("#6AAAFF") else Color.parseColor("#2C4A72"))
            cornerRadius = 8f
        }
        val rippleColor = ColorStateList.valueOf(Color.parseColor("#663D6FA8"))
        button.background = RippleDrawable(rippleColor, shape, shape)
        button.setTextColor(if (on) Color.parseColor("#8FBBFF") else Color.parseColor("#4A90FF"))
    }

    private fun styleLockButton() {
        val shape = GradientDrawable().apply {
            setColor(if (isLocked) Color.parseColor("#2A1010") else Color.TRANSPARENT)
            setStroke(2, if (isLocked) Color.parseColor("#FF6666") else Color.parseColor("#2C4A72"))
            cornerRadius = 8f
        }
        val rippleColor = ColorStateList.valueOf(Color.parseColor("#66FF6666"))
        lockBtn.background = RippleDrawable(rippleColor, shape, shape)
        lockBtn.setTextColor(if (isLocked) Color.parseColor("#FF6666") else Color.parseColor("#4A90FF"))
        lockStatusText.text = if (isLocked) "L · LOCKED" else "L · UNLOCKED"
        lockStatusText.setTextColor(if (isLocked) Color.parseColor("#FF6666") else Color.parseColor("#3AC8A0"))
    }

    private fun styleConnState() {
        val shape = GradientDrawable().apply {
            setColor(if (isConnected) Color.parseColor("#0E2A1F") else Color.TRANSPARENT)
            setStroke(2, if (isConnected) Color.parseColor("#3AC8A0") else Color.parseColor("#2C4A72"))
            cornerRadius = 8f
        }
        connectBtn.background = shape
        connectBtn.text = if (isConnected) "DISCONNECT" else "CONNECT"
        connectBtn.setTextColor(if (isConnected) Color.parseColor("#3AC8A0") else Color.parseColor("#4A90FF"))
        connStatus.text = if (isConnected) "USB session active" else "No active session"
        connStatus.setTextColor(if (isConnected) Color.parseColor("#7FA6D9") else Color.parseColor("#6B7686"))
        streamText.text = if (isConnected) "STREAMING FROM ESP32" else "NOT CONNECTED"
        streamText.setTextColor(if (isConnected) Color.parseColor("#3AA0C8") else Color.parseColor("#4A5568"))
    }

    private fun styleProtection() {
        if (protectionTripped) {
            protStrip.setBackgroundColor(Color.parseColor("#1A0A0A"))
            protTitle.text = "PROTECTION TRIPPED"
            protTitle.setTextColor(Color.parseColor("#FF6B6B"))
            protSub.text = "Output cut — press RESET to recover"
            protSub.setTextColor(Color.parseColor("#B07070"))
        } else {
            protStrip.setBackgroundColor(Color.parseColor("#07160F"))
            protTitle.text = "PROTECTION CLEAR"
            protTitle.setTextColor(Color.parseColor("#3AC8A0"))
            protSub.text = "Reverse / over-voltage guard armed"
            protSub.setTextColor(Color.parseColor("#6FA08E"))
        }
    }

    override fun onDestroy() {
        repeatDirection = 0
        handler.removeCallbacks(repeatRunnable)
        keepReading = false
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        disconnect("app closed")
        super.onDestroy()
    }
}
