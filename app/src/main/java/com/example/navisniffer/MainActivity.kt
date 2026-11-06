package com.example.navisniffer

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Step 1 of 2: find out what the FIMI Navi sends over USB.
 * Shows accessory info, then logs every raw packet as hex (screen + file).
 * Move the sticks and press buttons one at a time so the log is easy to decode.
 */
class MainActivity : Activity() {

    private lateinit var usb: UsbManager
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private var fd: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var logFile: FileOutputStream? = null

    private val ACTION_PERM = "com.example.navisniffer.PERMISSION"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val acc: UsbAccessory? = if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            when (i.action) {
                ACTION_PERM -> if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && acc != null)
                    open(acc) else log("Permission denied")
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> { log("Accessory detached"); close() }
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        logView = TextView(this).apply { textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE; setPadding(16, 16, 16, 16) }
        scroll = ScrollView(this).apply {
            addView(logView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)

        val f = IntentFilter(ACTION_PERM).apply { addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)

        log("Plug the Navi in (or re-plug it). Waiting for accessory...")
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        val acc = usb.accessoryList?.firstOrNull() ?: return
        log("Accessory: manufacturer=${acc.manufacturer} model=${acc.model} version=${acc.version} desc=${acc.description} uri=${acc.uri} serial=${acc.serial}")
        if (usb.hasPermission(acc)) open(acc) else {
            val flags = PendingIntent.FLAG_MUTABLE
            usb.requestPermission(acc, PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERM).setPackage(packageName), flags))
        }
    }

    private fun open(acc: UsbAccessory) {
        if (running) return
        fd = usb.openAccessory(acc)
        val pfd = fd ?: run { log("openAccessory failed"); return }
        val file = File(getExternalFilesDir(null), "navi_dump_${System.currentTimeMillis()}.txt")
        logFile = FileOutputStream(file)
        log("Opened. Saving raw log to ${file.absolutePath}")
        running = true
        Thread {
            val input = FileInputStream(pfd.fileDescriptor)
            val buf = ByteArray(1024)
            try {
                while (running) {
                    val n = input.read(buf)
                    if (n <= 0) continue
                    val line = "${System.currentTimeMillis()} [$n] " + buf.take(n).joinToString(" ") { "%02X".format(it) }
                    logFile?.write((line + "\n").toByteArray())
                    runOnUiThread { log(line) }
                }
            } catch (e: Exception) {
                runOnUiThread { log("Read stopped: ${e.message}") }
            }
        }.start()
    }

    private fun close() {
        running = false
        try { fd?.close() } catch (_: Exception) {}
        try { logFile?.close() } catch (_: Exception) {}
        fd = null
    }

    private fun log(s: String) {
        logView.append(s + "\n")
        if (logView.lineCount > 400) logView.text = logView.text.toString().lines().takeLast(300).joinToString("\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        close()
    }
}
