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

    lateinit var usbManager: UsbManager
    var serial: UsbSerialManager? = null

    lateinit var scopeView: ScopeView
    lateinit var dialView: DialView

    lateinit var slideRoot: SlideFrameLayout
    lateinit var mainPage: View
    lateinit var logbookPage: View
    lateinit var scrimView: View
    lateinit var drawerView: View
    lateinit var menuBtnMain: View
    lateinit var menuBtnLog: View
    lateinit var drawerMainOpt: LinearLayout
    lateinit var drawerLogOpt: LinearLayout

    lateinit var liveBtn: Button
    lateinit var refreshFilesBtn: Button
    lateinit var viewerTitle: TextView
    lateinit var logbookView: TextView
    lateinit var logbookScroll: ScrollView
    lateinit var sessionsList: LinearLayout

    lateinit var connStatus: TextView
    lateinit var connectBtn: Button
    lateinit var captureChip: TextView
    lateinit var streamText: TextView

    lateinit var zoomInBtn: View
    lateinit var zoomOutBtn: View
    lateinit var resetZoomBtn: View

    lateinit var logView: TextView
    lateinit var clearLogBtn: Button

    lateinit var rTarget: TextView
    lateinit var rTargetBig: TextView
    lateinit var rMeasured: TextView
    lateinit var rDuty: TextView
    lateinit var rMax: TextView
    lateinit var rMin: TextView
    lateinit var rError: TextView
    lateinit var rVoltage: TextView
    lateinit var lockStatusText: TextView
    lateinit var outStateBadge: TextView
    lateinit var pendText: TextView

    lateinit var coarseModeBtn: Button
    lateinit var fineModeBtn: Button
    lateinit var precisionBtn: Button
    lateinit var lockBtn: Button

    lateinit var downFreqBtn: Button
    lateinit var upFreqBtn: Button
    lateinit var speed1xBtn: Button
    lateinit var speed2xBtn: Button
    lateinit var minus1Btn: Button
    lateinit var minus01Btn: Button
    lateinit var plus01Btn: Button
    lateinit var plus1Btn: Button

    lateinit var btnCmdH: Button
    lateinit var btnCmdM: Button
    lateinit var btnCmdD: Button
    lateinit var btnCmdC: Button
    lateinit var btnCmdS: Button

    lateinit var manualFreqRow: LinearLayout
    lateinit var manualFreqInput: EditText
    lateinit var manualFreqSetBtn: Button
    lateinit var manualDutyRow: LinearLayout
    lateinit var manualDutyInput: EditText
    lateinit var manualDutySetBtn: Button

    lateinit var protStrip: LinearLayout
    lateinit var protTitle: TextView
    lateinit var protSub: TextView
    lateinit var resetBtn: Button

    lateinit var sessionLogger: SessionLogger

    val ACTION_USB_PERMISSION = "com.opscope.app.USB_PERMISSION"
    val BAUD_RATE = 921600

    val handler = Handler(Looper.getMainLooper())
    var readThread: Thread? = null

    @Volatile var keepReading = false

    val F_MIN = 1.0
    val F_MAX = 20000.0

    // outputFrequency: what's actually commanded to the ESP32.
    // dialPreview: wherever the dial / nudge controls currently sit (always tracks touch/taps).
    // While isLocked, dialPreview can move freely but outputFrequency does not.
    var outputFrequency = 1000.0
    var dialPreview = 1000.0
    var isLocked = false

    var isConnected = false
    var outputEnabled = false
    var protectionTripped = false

    var arrowSpeed = 1
    var repeatDirection = 0
    var sweepStartFreq = 0.0

    // live stream: streamWanted = what the user wants (C button), sent as W1/W0
    var streamWanted = false
    var startupSent = false

    val pendingLogbookLines = StringBuilder()
    var logbookFlushScheduled = false
    var lastMeasuredHz = 0.0
    var lastWaveform = false
    var maxMeasuredHz: Double? = null
    var minMeasuredHz: Double? = null

    var lastErrWarnMs = 0L
    var lastShownText = ""
    var lastShownMs = 0L
    var dialDragging = false

    var lastSentFreq = 1000.0
    var lastFreqTxMs = 0L

    var currentScreen = 0
    var drawerOpen = false
    var drawerAnimating = false
    var logbookIsLive = true
    var drawerDownX = 0f

    val repeatRunnable = object : Runnable {
        override fun run() {
            if (repeatDirection == 0) return
            changeFrequencyFromArrow()
            val delay = if (arrowSpeed == 1) 100L else 50L
            handler.postDelayed(this, delay)
        }
    }

    // Opening the USB port resets the ESP32, so commands sent right away can be lost.
    // They are sent again when the ESP32 says it is ready, and on a timer as backup.
    val startupRunnable = Runnable { sendStartupCommands() }

    // Twice a second: refresh voltage, LIVE/WAITING chip and the "no data" label.
    val uiTick = object : Runnable {
        override fun run() {
            val fresh = isConnected && scopeView.lastDataMs != 0L &&
                SystemClock.elapsedRealtime() - scopeView.lastDataMs < 1500L
            if (fresh) {
                rVoltage.text = String.format(Locale.US, "%.2fV", scopeView.avgCounts / 4095.0 * 3.3)
            }
            captureChip.text = if (fresh) "LIVE" else "WAITING"
            scopeView.postInvalidate()
            handler.postDelayed(this, 500L)
        }
    }

    val usbReceiver = object : BroadcastReceiver() {
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
        handler.post(uiTick)
        styleModeButtons("real")
        styleLockButton()
        styleProtection()
        styleConnState()

        // Every button without its own styling function gets the same
        // flat black-bg/blue-border look explicitly.
        styleToggle(btnCmdH, false)
        styleToggle(btnCmdM, false)
        styleToggle(btnCmdD, false)
        styleToggle(btnCmdC, false)
        styleToggle(btnCmdS, false)
        styleToggle(downFreqBtn, false)
        styleToggle(upFreqBtn, false)
        styleToggle(speed1xBtn, true)
        styleToggle(speed2xBtn, false)
        styleToggle(minus1Btn, false)
        styleToggle(minus01Btn, false)
        styleToggle(plus01Btn, false)
        styleToggle(plus1Btn, false)
        styleToggle(manualFreqSetBtn, false)
        styleToggle(manualDutySetBtn, false)
        styleToggle(clearLogBtn, false)
        styleToggle(resetBtn, false)

        appendLog("OPscope ready. Output stays off until Connect.", "info")
    }

    fun setupUsbReceiver() {
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

    fun sendFrequency() {
        scopeView.targetFreqHz = outputFrequency
        val command = String.format(Locale.US, "F%.2f", outputFrequency)
        lastFreqTxMs = SystemClock.elapsedRealtime()
        lastSentFreq = outputFrequency
        sessionLogger.log("TX", command)
        serial?.writeLine(command)
    }

    // Enable + frequency + stream. Safe to repeat (the ESP32 just applies them again).
    fun sendStartupCommands() {
        if (!isConnected) return
        sessionLogger.log("TX", "E1")
        serial?.writeLine("E1")
        sendFrequency()
        if (!startupSent) {
            startupSent = true
            streamWanted = true
            styleToggle(btnCmdC, true)
            appendLog("Live stream ON.", "ok")
        }
        if (streamWanted) {
            sessionLogger.log("TX", "W1")
            serial?.writeLine("W1")
        }
    }

    fun requestDevice() {
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

    fun connectToDevice(device: UsbDevice) {
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
            startupSent = false
            streamWanted = false
            scopeView.clearCapture()
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

            handler.removeCallbacks(startupRunnable)
            handler.postDelayed(startupRunnable, 1500L)
            handler.postDelayed(startupRunnable, 3000L)
        } catch (e: Exception) {
            serial = null
            isConnected = false
            appendLog("Connection failed: ${e.message}", "err")
            sessionLogger.log("SYS", "exception: ${e.message}")
            styleConnState()
        }
    }

    fun deviceDesc(d: UsbDevice): String =
        String.format(Locale.US, "USB %04X:%04X %s", d.vendorId, d.productId, d.deviceName)

    fun startReading() {
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
                            if (line.isNotEmpty() && !handleStreamLine(line)) batch.add(line)
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

    val streamHeader = Regex("W(\\d{1,6}):")
    val streamBuf = IntArray(256)

    // Stream block: "W<rate>:" + 2 chars per sample. Runs on the read thread and
    // feeds the scope directly, so the UI thread never sees sample data.
    // Returns true if the line was a stream block.
    fun handleStreamLine(line: String): Boolean {
        if (line.length < 10) return false
        val m = streamHeader.find(line) ?: return false
        val rate = m.groupValues[1].toDoubleOrNull() ?: return false
        val start = m.range.last + 1
        val chars = line.length - start
        if (chars < 2 || chars % 2 != 0 || chars / 2 > streamBuf.size) return false
        val n = chars / 2
        for (i in 0 until n) {
            val a = line[start + 2 * i].code - 48
            val b = line[start + 2 * i + 1].code - 48
            if (a < 0 || a > 63 || b < 0 || b > 63) return false
            streamBuf[i] = (a shl 6) or b
        }
        scopeView.feed(streamBuf, n, rate)
        return true
    }

    fun processSerialLine(line: String) {
        var text = line.trim()
        if (text.isEmpty()) return

        // Boot garbage bytes can sit in front of a real message; cut them off.
        val aiIdx = text.indexOf("AI>")
        if (aiIdx > 0) text = text.substring(aiIdx)

        sessionLogger.log("RX", text)

        if (text.startsWith("AI>")) {
            val lower = text.lowercase(Locale.US)

            // ESP32 just (re)booted: send enable + frequency + stream start again.
            if (lower.contains("opscope esp32 ready")) {
                outputEnabled = true
                updateOutputBadge()
                sendStartupCommands()
            }

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

    fun disconnect(reason: String) {
        val wasConnected = isConnected
        keepReading = false
        repeatDirection = 0
        streamWanted = false
        startupSent = false
        handler.removeCallbacks(repeatRunnable)
        handler.removeCallbacks(startupRunnable)
        styleToggle(btnCmdC, false)
        styleToggle(btnCmdM, false)
        styleToggle(btnCmdD, false)
        manualFreqRow.visibility = View.GONE
        manualDutyRow.visibility = View.GONE
        try { readThread?.interrupt() } catch (_: Exception) {}
        readThread = null

        if (wasConnected) {
            try {
                sessionLogger.log("TX", "W0")
                serial?.writeLine("W0")
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

    fun appendLog(message: String, kind: String) {
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

    override fun onDestroy() {
        repeatDirection = 0
        handler.removeCallbacks(repeatRunnable)
        handler.removeCallbacks(startupRunnable)
        handler.removeCallbacks(uiTick)
        keepReading = false
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        disconnect("app closed")
        super.onDestroy()
    }
}
