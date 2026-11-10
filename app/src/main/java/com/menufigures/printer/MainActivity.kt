package com.menufigures.printer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class MainActivity : Activity() {
    companion object {
        private const val DEFAULT_APP_URL = "https://preview--menufigures.apper.so"
        private const val BLUETOOTH_PERMISSION_REQUEST = 41
    }

    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var status: TextView
    private var trustedHost = Uri.parse(DEFAULT_APP_URL).host.orEmpty()
    private var printerSocket: BluetoothSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBluetoothPermissionIfNeeded()
        buildAppShell()
        openMenuFigures(DEFAULT_APP_URL)
    }

    private fun requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                BLUETOOTH_PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST) {
            status.text = if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                "Nearby devices permission granted. Pair your printer in Android Bluetooth settings."
            } else {
                "Nearby devices permission is required for Bluetooth printing."
            }
        }
    }

    private fun buildAppShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 8)
        }

        root.addView(TextView(this).apply {
            text = "MenuFigures Printer"
            textSize = 21f
        })

        status = TextView(this).apply {
            text = "Opening MenuFigures…"
            textSize = 12f
            setPadding(0, 6, 0, 8)
        }
        root.addView(status)

        val urlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        urlInput = EditText(this).apply {
            singleLine = true
            textSize = 12f
            setText(DEFAULT_APP_URL)
        }
        urlRow.addView(urlInput, LinearLayout.LayoutParams(0, 48, 1f))
        urlRow.addView(Button(this).apply {
            text = "Open"
            setOnClickListener { openMenuFigures(urlInput.text.toString().trim()) }
        })
        root.addView(urlRow)

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(PrinterJavascriptBridge(), "MenuFiguresPrinter")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "https" && uri.host == trustedHost) return false
                status.text = "Blocked navigation outside the selected MenuFigures website."
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                status.text = "Connecting to MenuFigures…"
            }

            override fun onPageFinished(view: WebView, url: String?) {
                status.text = "MenuFigures connected. Pair a Bluetooth printer in Android settings, then open Settings → Printers."
            }
        }

        root.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        setContentView(root)
    }

    private fun openMenuFigures(rawUrl: String) {
        val normalized = if (rawUrl.startsWith("https://")) rawUrl else "https://$rawUrl"
        val uri = runCatching { Uri.parse(normalized) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank()) {
            status.text = "Enter a valid HTTPS MenuFigures website address."
            return
        }
        trustedHost = uri.host!!
        urlInput.setText(uri.toString())
        webView.loadUrl(uri.toString())
    }

    private fun bridgeCallAllowed(): Boolean {
        val currentHost = runCatching { Uri.parse(webView.url).host }.getOrNull()
        return currentHost == trustedHost && trustedHost.isNotBlank()
    }

    private fun bluetoothAdapter(): BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private fun hasBluetoothPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun jsonError(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()

    inner class PrinterJavascriptBridge {
        @JavascriptInterface
        fun getPairedPrinters(): String {
            if (!bridgeCallAllowed()) return jsonError("Printer bridge is only available on the selected MenuFigures website.")
            if (!hasBluetoothPermission()) return jsonError("Allow Nearby devices permission in Android settings.")
            return try {
                val adapter = bluetoothAdapter() ?: return jsonError("Bluetooth is not available on this device.")
                if (!adapter.isEnabled) return jsonError("Turn on Bluetooth, then refresh paired printers.")
                val list = JSONArray()
                adapter.bondedDevices.orEmpty()
                    .sortedBy { it.name ?: it.address }
                    .forEach { device ->
                        list.put(JSONObject().put("name", device.name ?: "Bluetooth printer").put("address", device.address))
                    }
                JSONObject().put("ok", true).put("printers", list).toString()
            } catch (error: SecurityException) {
                jsonError("Nearby devices permission is required to list paired printers.")
            } catch (error: Exception) {
                jsonError(error.message ?: "Could not read paired printers.")
            }
        }

        @JavascriptInterface
        fun connect(address: String): String {
            if (!bridgeCallAllowed()) return jsonError("Printer bridge is only available on the selected MenuFigures website.")
            if (!hasBluetoothPermission()) return jsonError("Allow Nearby devices permission in Android settings.")
            return try {
                val adapter = bluetoothAdapter() ?: return jsonError("Bluetooth is not available.")
                val device: BluetoothDevice = adapter.bondedDevices.firstOrNull { it.address == address }
                    ?: return jsonError("Pair this printer in Android Bluetooth settings first.")
                synchronized(this@MainActivity) {
                    runCatching { printerSocket?.close() }
                    printerSocket = null
                    val socket = device.createRfcommSocketToServiceRecord(spp)
                    socket.connect()
                    printerSocket = socket
                }
                JSONObject().put("ok", true).put("name", device.name ?: "Bluetooth printer").toString()
            } catch (error: Exception) {
                runCatching { printerSocket?.close() }
                printerSocket = null
                jsonError(error.message ?: "Could not connect to the Bluetooth printer. Check that it supports Bluetooth Classic SPP.")
            }
        }

        @JavascriptInterface
        fun printEscPos(base64: String): String {
            if (!bridgeCallAllowed()) return jsonError("Printer bridge is only available on the selected MenuFigures website.")
            return try {
                val data = Base64.decode(base64, Base64.DEFAULT)
                if (data.isEmpty()) return jsonError("Receipt data is empty.")
                synchronized(this@MainActivity) {
                    val socket = printerSocket ?: return jsonError("Connect to a paired printer before printing.")
                    socket.outputStream.write(data)
                    socket.outputStream.flush()
                }
                JSONObject().put("ok", true).put("bytesSent", data.size).toString()
            } catch (error: Exception) {
                jsonError(error.message ?: "Could not send receipt data to the printer.")
            }
        }

        @JavascriptInterface
        fun disconnect(): String {
            synchronized(this@MainActivity) {
                runCatching { printerSocket?.close() }
                printerSocket = null
            }
            return JSONObject().put("ok", true).toString()
        }
    }

    override fun onDestroy() {
        synchronized(this) {
            runCatching { printerSocket?.close() }
            printerSocket = null
        }
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
