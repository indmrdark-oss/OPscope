package com.opscope.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Part of MainActivity: UI helpers, split out so each file stays short.

fun MainActivity.setupButtons() {
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
        streamWanted = !streamWanted
        styleToggle(btnCmdC, streamWanted)
        val cmd = if (streamWanted) "W1" else "W0"
        sessionLogger.log("TX", cmd)
        serial?.writeLine(cmd)
        appendLog(if (streamWanted) "Live stream ON." else "Live stream OFF.", if (streamWanted) "ok" else "info")
        sessionLogger.log("APP", if (streamWanted) "live stream ON" else "live stream OFF")
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

fun MainActivity.setupArrowButton(button: Button, direction: Int) {
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

fun MainActivity.changeFrequencyFromArrow() {
    val step = if (arrowSpeed == 1) 1.0 else 5.0
    applyFrequencyChange((dialPreview + repeatDirection * step).coerceIn(F_MIN, F_MAX))
}

fun MainActivity.nudgeFrequency(amount: Double) {
    applyFrequencyChange((dialPreview + amount).coerceIn(F_MIN, F_MAX))
    sessionLogger.log("APP", "nudge ${if (amount > 0) "+" else ""}$amount")
}

// Shared by dial, nudge buttons and arrow-hold: moves the preview, and
// only pushes to outputFrequency / the ESP32 when not locked.
fun MainActivity.applyFrequencyChange(newPreview: Double) {
    dialPreview = newPreview
    dialView.setFrequency(dialPreview)
    if (!isLocked) {
        outputFrequency = dialPreview
        throttledFrequencySend()
    }
    updateFreqDisplay()
}

fun MainActivity.requireConnected(): Boolean {
    if (!isConnected) {
        appendLog("Choose a transport and connect first.", "err")
        return false
    }
    return true
}

fun MainActivity.fireSimpleCommand(cmd: String, logMsg: String) {
    if (!requireConnected()) return
    sessionLogger.log("TX", cmd)
    serial?.writeLine(cmd)
    appendLog(logMsg, "info")
}

fun MainActivity.submitManualFrequency() {
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

fun MainActivity.submitManualDuty() {
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

fun MainActivity.setupDial() {
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

fun MainActivity.setupDrawer() {
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

fun MainActivity.drawerWidth(): Float =
    if (drawerView.width > 0) drawerView.width.toFloat()
    else (220f * resources.displayMetrics.density)

fun MainActivity.openDrawer() {
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

fun MainActivity.closeDrawer() {
    if (!drawerOpen || drawerAnimating) return
    drawerAnimating = true
    scrimView.visibility = View.GONE
    drawerView.animate().translationX(-drawerWidth()).setDuration(200).withEndAction {
        drawerView.visibility = View.GONE; drawerAnimating = false; drawerOpen = false
    }.start()
}

fun MainActivity.goMain() {
    if (currentScreen != 0) {
        currentScreen = 0
        logbookPage.visibility = View.GONE
        mainPage.visibility = View.VISIBLE
    }
    closeDrawer()
}

fun MainActivity.goLogbook() {
    if (currentScreen != 1) {
        currentScreen = 1
        mainPage.visibility = View.GONE
        logbookPage.visibility = View.VISIBLE
        refreshSessionList()
    }
    closeDrawer()
}

fun MainActivity.updateDrawerHighlight() {
    val activeBg = 0xFF122038.toInt(); val idleBg = 0x00000000
    val activeColor = 0xFF3D6FA8.toInt(); val idleColor = 0xFFBFD4EE.toInt()
    val mainTitle = drawerMainOpt.getChildAt(0) as TextView
    val logTitle = drawerLogOpt.getChildAt(0) as TextView
    drawerMainOpt.setBackgroundColor(if (currentScreen == 0) activeBg else idleBg)
    mainTitle.setTextColor(if (currentScreen == 0) activeColor else idleColor)
    drawerLogOpt.setBackgroundColor(if (currentScreen == 1) activeBg else idleBg)
    logTitle.setTextColor(if (currentScreen == 1) activeColor else idleColor)
}

fun MainActivity.scheduleLogbookFlush() {
    if (logbookFlushScheduled) return
    logbookFlushScheduled = true
    handler.postDelayed({ flushLogbookLines() }, 150L)
}

fun MainActivity.flushLogbookLines() {
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

fun MainActivity.refreshSessionList() {
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

fun MainActivity.makeRow(text: String): TextView {
    val t = TextView(this)
    t.text = text
    t.textSize = 10.5f
    t.typeface = Typeface.MONOSPACE
    t.setPadding(16, 12, 16, 12)
    return t
}

fun MainActivity.openSessionFile(f: File) {
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

fun MainActivity.updateFreqDisplay() {
    rTargetBig.text = String.format(Locale.US, "%.2f Hz", outputFrequency)
    rTarget.text = String.format(Locale.US, "%.2f Hz", outputFrequency)
    pendText.text = if (isLocked && Math.abs(dialPreview - outputFrequency) > 0.004)
        "DIAL → ${String.format(Locale.US, "%.2f Hz", dialPreview)} (applies on unlock)"
    else ""
    updateOutputBadge()
}

fun MainActivity.updateOutputBadge() {
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

fun MainActivity.throttledFrequencySend() {
    val now = SystemClock.elapsedRealtime()
    if (now - lastFreqTxMs >= 100L) sendFrequency()
}

fun MainActivity.showOnce(text: String, kind: String, gapMs: Long = 2000L) {
    val now = SystemClock.elapsedRealtime()
    if (text == lastShownText && now - lastShownMs < gapMs) return
    lastShownText = text
    lastShownMs = now
    appendLog(text.removePrefix("AI>").trim(), kind)
}

fun MainActivity.styleModeButtons(selected: String) {
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

// Shared black-bg/blue-border look used by every plain button in the app.
// on=true gives the bolder/lit "pressed-and-held" look.
fun MainActivity.styleToggle(button: Button, on: Boolean) {
    val shape = GradientDrawable().apply {
        setColor(if (on) Color.parseColor("#14284A") else Color.TRANSPARENT)
        setStroke(2, if (on) Color.parseColor("#6AAAFF") else Color.parseColor("#2C4A72"))
        cornerRadius = 8f
    }
    val rippleColor = ColorStateList.valueOf(Color.parseColor("#663D6FA8"))
    button.background = RippleDrawable(rippleColor, shape, shape)
    button.setTextColor(if (on) Color.parseColor("#8FBBFF") else Color.parseColor("#4A90FF"))
}

fun MainActivity.styleLockButton() {
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

fun MainActivity.styleConnState() {
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

fun MainActivity.styleProtection() {
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
