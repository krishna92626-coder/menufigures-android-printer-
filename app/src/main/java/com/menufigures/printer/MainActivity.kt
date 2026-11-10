package com.menufigures.printer

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var paired: List<BluetoothDevice> = emptyList()
    private lateinit var status: TextView
    private lateinit var devices: Spinner
    private lateinit var paper: Spinner
    private lateinit var copies: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 41)
        }
        makeUi()
        loadPaired()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun makeUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 32, 28, 24) }
        root.addView(TextView(this).apply { text = "MenuFigures Printer"; textSize = 24f })
        root.addView(TextView(this).apply { text = "Pair your EZO / ESC-POS printer in Android Bluetooth settings first."; textSize = 14f })
        status = TextView(this).apply { text = "Loading paired printers…"; textSize = 14f }
        root.addView(status)
        devices = Spinner(this); root.addView(devices)
        root.addView(TextView(this).apply { text = "Paper width" })
        paper = Spinner(this).apply { adapter = arrayAdapter(listOf("58mm", "80mm")) }; root.addView(paper)
        root.addView(TextView(this).apply { text = "Copies" })
        copies = Spinner(this).apply { adapter = arrayAdapter(listOf("1", "2", "3")) }; root.addView(copies)
        root.addView(Button(this).apply { text = "Refresh paired printers"; setOnClickListener { loadPaired() } })
        root.addView(Button(this).apply { text = "Print test receipt"; setOnClickListener { printToSelected(testReceipt()) } })
        setContentView(root)
    }

    private fun arrayAdapter(items: List<String>) = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    private fun loadPaired() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) { status.text = "Bluetooth is not available on this device"; return }
            paired = adapter.bondedDevices?.sortedBy { it.name ?: it.address }?.toList() ?: emptyList()
            val labels = if (paired.isEmpty()) listOf("No paired devices — pair printer in Android Settings") else paired.map { "${it.name ?: "Bluetooth printer"} (${it.address})" }
            devices.adapter = arrayAdapter(labels)
            status.text = "${paired.size} paired device(s) found"
        } catch (_: SecurityException) { status.text = "Allow Nearby devices permission, then refresh" }
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        if (uri.scheme != "menufigures" || uri.host != "print") return
        val receipt = uri.getQueryParameter("text") ?: return
        val is80 = uri.getQueryParameter("paper") == "80"
        val count = (uri.getQueryParameter("copies")?.toIntOrNull() ?: 1).coerceIn(1, 3)
        printToSelected(escPosText(receipt, is80), count)
    }

    private fun testReceipt(): ByteArray {
        val is80 = paper.selectedItemPosition == 1
        return escPosText("MENUFIGURES\nBluetooth test receipt\nPaper: ${if (is80) "80mm" else "58mm"}\n------------------------------\nTEST PRINT\n\n", is80)
    }

    private fun escPosText(text: String, is80: Boolean): ByteArray {
        val maxChars = if (is80) 48 else 32
        val wrapped = text.lines().joinToString("\n") { line -> if (line.length <= maxChars) line else line.chunked(maxChars).joinToString("\n") } + "\n\n"
        return byteArrayOf(0x1B, 0x40) + wrapped.toByteArray(Charsets.UTF_8) + byteArrayOf(0x0A, 0x0A)
    }

    private fun printToSelected(data: ByteArray, requestedCopies: Int = 1) {
        val device = paired.getOrNull(devices.selectedItemPosition)
        if (device == null) { status.text = "Select a paired printer first"; return }
        val count = if (requestedCopies > 1) requestedCopies else (copies.selectedItem?.toString()?.toIntOrNull() ?: 1)
        status.text = "Connecting to ${device.name ?: device.address}…"
        thread {
            var socket: BluetoothSocket? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(spp)
                socket.connect()
                repeat(count.coerceIn(1, 3)) { socket.outputStream.write(data); socket.outputStream.flush() }
                socket.outputStream.write(byteArrayOf(0x1D, 0x56, 0x00))
                socket.outputStream.flush()
                runOnUiThread { status.text = "Print data sent" }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Print failed: ${e.message ?: "check printer protocol"}" }
            } finally { try { socket?.close() } catch (_: Exception) {} }
        }
    }
}
