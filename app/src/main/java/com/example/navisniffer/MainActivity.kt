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
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Step 1b: sniff + experiment.
 *  - Echo mode: sends every received packet back to the controller.
 *  - Send hex: send custom bytes to the controller.
 *  - Changed bytes only: shows only the byte positions that changed since the last
 *    packet of the same type (so stick movements stand out from counters).
 */
class MainActivity : Activity() {

    private lateinit var usb: UsbManager
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var hexInput: EditText
    private var fd: ParcelFileDescriptor? = null
    private var out: FileOutputStream? = null
    private var logFile: FileOutputStream? = null
    private val writer = Executors.newSingleThreadExecutor()
    private val last = HashMap<String, ByteArray>()

    @Volatile private var running = false
    @Volatile private var echo = false
    @Volatile private var diffOnly = false

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

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }

        val echoBox = CheckBox(this).apply {
            text = "Echo every packet back to controller"
            setOnCheckedChangeListener { _, v -> echo = v }
        }
        val diffBox = CheckBox(this).apply {
            text = "Show changed bytes only"
            setOnCheckedChangeListener { _, v -> diffOnly = v }
        }
        hexInput = EditText(this).apply { hint = "Hex to send, e.g. AE B1 01 00"; textSize = 12f }
        val sendBtn = Button(this).apply {
            text = "Send hex"
            setOnClickListener { sendHex(hexInput.text.toString()) }
        }
        logView = TextView(this).apply { textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE }
        scroll = ScrollView(this).apply { addView(logView) }

        root.addView(echoBox)
        root.addView(diffBox)
        root.addView(hexInput)
        root.addView(sendBtn)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val f = IntentFilter(ACTION_PERM).apply { addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)

        log("Plug the Navi in (or re-plug it). Waiting for accessory...")
        handle()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle()
    }

    private fun handle() {
        val acc = usb.accessoryList?.firstOrNull() ?: return
        log("Accessory: manufacturer=${acc.manufacturer} model=${acc.model} version=${acc.version}")
        if (usb.hasPermission(acc)) open(acc) else {
            usb.requestPermission(acc, PendingIntent.getBroadcast(this, 0,
                Intent(ACTION_PERM).setPackage(packageName), PendingIntent.FLAG_MUTABLE))
        }
    }

    private fun open(acc: UsbAccessory) {
        if (running) return
        fd = usb.openAccessory(acc)
        val pfd = fd ?: run { log("openAccessory failed"); return }
        out = FileOutputStream(pfd.fileDescriptor)
        val file = File(getExternalFilesDir(null), "navi_dump_${System.currentTimeMillis()}.txt")
        logFile = FileOutputStream(file)
        log("Opened. Raw log: ${file.absolutePath}")
        running = true
        Thread {
            val input = FileInputStream(pfd.fileDescriptor)
            val buf = ByteArray(1024)
            try {
                while (running) {
                    val n = input.read(buf)
                    if (n <= 0) continue
                    val pkt = buf.copyOf(n)
                    val hex = pkt.joinToString(" ") { "%02X".format(it) }
                    logFile?.write("${System.currentTimeMillis()} [$n] $hex\n".toByteArray())

                    val key = "$n-${pkt.getOrElse(9) { 0 }}"
                    val prev = last[key]
                    last[key] = pkt
                    val shown = if (diffOnly && prev != null) {
                        val changes = (0 until n).filter { pkt[it] != prev[it] }
                        if (changes.isEmpty()) null
                        else "[$n/%02X] ".format(pkt.getOrElse(9) { 0 }) +
                            changes.joinToString(" ") { "#$it:%02X>%02X".format(prev[it], pkt[it]) }
                    } else "[$n] $hex"
                    if (shown != null) runOnUiThread { log(shown) }

                    if (echo) write(pkt)
                }
            } catch (e: Exception) {
                runOnUiThread { log("Read stopped: ${e.message}") }
            }
        }.start()
    }

    private fun write(bytes: ByteArray) {
        writer.execute {
            try { out?.write(bytes); out?.flush() }
            catch (e: Exception) { runOnUiThread { log("Write failed: ${e.message}") } }
        }
    }

    private fun sendHex(text: String) {
        val clean = text.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        if (clean.length < 2 || clean.length % 2 != 0) { log("Bad hex"); return }
        val bytes = ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        write(bytes)
        log("Sent ${bytes.size} bytes")
    }

    private fun close() {
        running = false
        try { fd?.close() } catch (_: Exception) {}
        try { logFile?.close() } catch (_: Exception) {}
        fd = null; out = null
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
        writer.shutdown()
    }
}
