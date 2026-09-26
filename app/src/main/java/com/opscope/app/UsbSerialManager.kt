package com.opscope.app

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

class UsbSerialManager(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) {
    private var port: UsbSerialPort? = null

    fun open(baudRate: Int): Boolean {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver: UsbSerialDriver = availableDrivers.firstOrNull { it.device == device }
            ?: availableDrivers.firstOrNull() ?: return false

        val connection = usbManager.openDevice(driver.device) ?: return false
        val p = driver.ports[0]
        p.open(connection)
        p.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        port = p
        return true
    }

    fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val p = port ?: return -1
        return try { p.read(buffer, timeoutMs) } catch (e: Exception) { -1 }
    }

    fun writeLine(text: String) {
        val p = port ?: return
        try { p.write((text + "\n").toByteArray(Charsets.US_ASCII), 1000) } catch (e: Exception) {}
    }

    fun close() {
        try { port?.close() } catch (e: Exception) {}
    }

    fun debugInInfo(): String = "port=${port != null}"
    fun debugOutInfo(): String = "port=${port != null}"
}
