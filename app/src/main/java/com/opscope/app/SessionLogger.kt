package com.opscope.app

import android.content.Context
import android.os.Build
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class SessionLogger(context: Context) {

    var onLogLine: ((String) -> Unit)? = null

    private val appVersion: String = try {
        val ai = context.packageManager.getPackageInfo(context.packageName, 0)
        "${ai.versionName} (build ${ai.versionCode})"
    } catch (e: Exception) {
        "unknown"
    }

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val dir = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "Logbook"
    ).apply { mkdirs() }
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var writer: BufferedWriter? = null
    private var sessionStartMs = 0L
    private var currentFileRef: File? = null

    val currentFile: File? get() = currentFileRef

    private var txCount = 0
    private var rxCount = 0
    private var appCount = 0
    private var sysCount = 0
    private var capturesStarted = 0
    private var capturesDone = 0
    private var errHighLines = 0
    private var firstTarget: Double? = null
    private var lastTarget: Double? = null
    private var minTarget: Double? = null
    private var maxTarget: Double? = null
    private var minMeasured: Double? = null
    private var maxMeasured: Double? = null
    private var lastDuty: Double? = null
    private var lastCaptureLine: String? = null

    fun startSession(deviceDesc: String): File {
        endSession("restart")
        var f = File(dir, "OPscope_${fileFmt.format(Date())}.txt")
        var n = 2
        while (f.exists()) {
            f = File(dir, "OPscope_${fileFmt.format(Date())}_$n.txt")
            n++
        }
        currentFileRef = f
        sessionStartMs = System.currentTimeMillis()
        resetStats()

        val w = BufferedWriter(FileWriter(f))
        w.writeLine("================================================================")
        w.writeLine(" OPscope - SESSION LOGBOOK")
        w.writeLine("================================================================")
        w.writeLine("")
        w.writeLine("WHAT THIS FILE IS")
        w.writeLine("  A complete, timestamped record of ONE connection session between")
        w.writeLine("  OPscope (Android phone) and an ESP32 over USB serial")
        w.writeLine("  (USB OTG, 250000 baud, 8N1). The ESP32 is the signal source and")
        w.writeLine("  output driver and measures its own output (frequency, duty, error).")
        w.writeLine("  The app only sends commands and displays what the ESP32 reports.")
        w.writeLine("")
        w.writeLine("HOW TO READ IT")
        w.writeLine("  Every line:  [YYYY-MM-DD HH:MM:SS.mmm] [KIND] text")
        w.writeLine("  Kinds:")
        w.writeLine("    SYS = system events (connect/disconnect/USB/errors)")
        w.writeLine("    APP = user actions inside the app (dial, buttons, captures...)")
        w.writeLine("    TX  = a command the app SENT to the ESP32")
        w.writeLine("    RX  = a line the ESP32 SENT to the app")
        w.writeLine("")
        w.writeLine("PROTOCOL REFERENCE (what the values mean)")
        w.writeLine("  TX  F<freq>            set output frequency in Hz (e.g. F8.80 = 8.80 Hz)")
        w.writeLine("  TX  C                  request one waveform capture")
        w.writeLine("  RX  CAP,<n>,<rate>,REAL capture header: n samples, sampled at rate Hz")
        w.writeLine("  RX  <csv integers>     real ESP32 ADC samples")
        w.writeLine("  RX  ENDCAP             capture finished")
        w.writeLine("  RX  STATUS,<f>,<m>,<d>,<v>,<e>,<c>")
        w.writeLine("                         periodic status report from the ESP32:")
        w.writeLine("                         f = requested frequency, m = measured output")
        w.writeLine("                         frequency, d = output duty cycle in %,")
        w.writeLine("                         e = |f - m| / f in % (0 = output on target)")
        w.writeLine("  RX  NO SIGNAL          status line variant: no waveform detected")
        w.writeLine("  RX  AI> <text>         ESP32 feedback / acknowledgement messages")
        w.writeLine("")
        w.writeLine("SESSION INFO")
        w.writeLine("  Started:     ${timeFmt.format(Date())}")
        w.writeLine("  App:         OPscope $appVersion (com.opscope.app)")
        w.writeLine("  Phone:       Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        w.writeLine("  Device:      $deviceDesc")
        w.writeLine("  Freq range:  1 Hz - 20000 Hz (app dial)")
        w.writeLine("  Voltage:     displayed min/max/avg use the ESP32 3.3V ADC reference;")
        w.writeLine("                real inverter voltage depends on the external divider,")
        w.writeLine("                so treat displayed voltages as RELATIVE, not absolute.")
        w.writeLine("")
        w.writeLine("--- session start ---")
        w.writeLine("")
        writer = w
        return f
    }

    fun log(kind: String, text: String) {
        val w = writer ?: return
        when (kind) {
            "TX" -> {
                txCount++
                if (text.startsWith("F")) {
                    text.substring(1).toDoubleOrNull()?.let { noteTargetSent(it) }
                }
            }
            "RX" -> {
                rxCount++
                noteRxStats(text)
            }
            "APP" -> {
                appCount++
                if (text.startsWith("capture done:")) lastCaptureLine = text
            }
            "SYS" -> sysCount++
        }
        val line = "[${timeFmt.format(Date())}] [$kind] $text"
        io.execute {
            try {
                w.write(line)
                w.newLine()
                w.flush()
            } catch (_: Exception) {
            }
            onLogLine?.invoke(line)
        }
    }

    fun endSession(reason: String) {
        val w = writer ?: return
        writer = null
        val start = sessionStartMs
        io.execute {
            try {
                val dur = if (start > 0) System.currentTimeMillis() - start else 0
                w.writeLine("")
                w.writeLine("--- session end ---")
                w.writeLine("")
                w.writeLine("SESSION SUMMARY (quick reference for analysis)")
                w.writeLine(String.format(Locale.US, "  Duration:              %.1f s", dur / 1000.0))
                w.writeLine("  Lines:                 APP=$appCount  TX=$txCount  RX=$rxCount  SYS=$sysCount")
                if (firstTarget != null) {
                    w.writeLine(
                        String.format(
                            Locale.US,
                            "  Frequency set (TX F):  first %.2f Hz | last %.2f Hz | min %.2f | max %.2f",
                            firstTarget!!, lastTarget!!, minTarget!!, maxTarget!!
                        )
                    )
                }
                if (minMeasured != null) {
                    w.writeLine(
                        String.format(
                            Locale.US,
                            "  Measured by ESP32:     min %.2f Hz | max %.2f Hz",
                            minMeasured!!, maxMeasured!!
                        )
                    )
                }
                w.writeLine("  Captures:              started=$capturesStarted  completed=$capturesDone")
                lastCaptureLine?.let { w.writeLine("  Last capture:          $it") }
                w.writeLine("  Status lines Err>2%:   $errHighLines")
                lastDuty?.let { w.writeLine(String.format(Locale.US, "  Duty (last seen):      %.1f%%", it)) }
                w.writeLine("  Ended:                 ${timeFmt.format(Date())} ($reason)")
                w.writeLine("")
                w.writeLine("=== end of session ===")
            } catch (_: Exception) {
            } finally {
                try {
                    w.close()
                } catch (_: Exception) {
                }
                currentFileRef = null
            }
        }
    }

    fun listSessions(): List<File> =
        dir.listFiles { f -> f.name.startsWith("OPscope_") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    private fun resetStats() {
        txCount = 0
        rxCount = 0
        appCount = 0
        sysCount = 0
        capturesStarted = 0
        capturesDone = 0
        errHighLines = 0
        firstTarget = null
        lastTarget = null
        minTarget = null
        maxTarget = null
        minMeasured = null
        maxMeasured = null
        lastDuty = null
        lastCaptureLine = null
    }

    private fun noteTargetSent(f: Double) {
        if (firstTarget == null) firstTarget = f
        lastTarget = f
        minTarget = if (minTarget == null) f else minOf(minTarget!!, f)
        maxTarget = if (maxTarget == null) f else maxOf(maxTarget!!, f)
    }

    private fun noteRxStats(text: String) {
        when {
            text.startsWith("CAP,") -> capturesStarted++
            text == "ENDCAP" -> capturesDone++
            text.startsWith("Target:") -> {
                if (!text.contains("NO SIGNAL")) {
                    Regex("Measured:\\s*([\\d.]+)").find(text)
                        ?.groupValues?.get(1)?.toDoubleOrNull()?.let { hz ->
                            minMeasured = if (minMeasured == null) hz else minOf(minMeasured!!, hz)
                            maxMeasured = if (maxMeasured == null) hz else maxOf(maxMeasured!!, hz)
                        }
                }
                Regex("Duty:\\s*([\\d.]+)").find(text)
                    ?.groupValues?.get(1)?.toDoubleOrNull()?.let { lastDuty = it }
                Regex("Err:\\s*([\\d.]+)").find(text)
                    ?.groupValues?.get(1)?.toDoubleOrNull()?.let {
                        if (it > 2.0) errHighLines++
                    }
            }
        }
    }

    private fun BufferedWriter.writeLine(s: String) {
        write(s)
        newLine()
    }
}
