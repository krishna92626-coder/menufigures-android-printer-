package com.menufigures.printer

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val APP_URL = "https://preview--menufigures.apper.so"
        private const val TRUSTED_HOST = "preview--menufigures.apper.so"
        private const val TRUSTED_ORIGIN = "https://preview--menufigures.apper.so"
        private const val BLUETOOTH_PERMISSION_REQUEST = 41
        private const val MAX_RECEIPT_BYTES = 2 * 1024 * 1024
    }

    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val bridgeExecutor = Executors.newSingleThreadExecutor()
    private lateinit var webView: WebView
    private var printerSocket: BluetoothSocket? = null
    private var bridgeReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webView.webChromeClient = WebChromeClient()
        bridgeReady = installSecurePrinterBridge()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return !isTrustedUrl(request.url)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                val uri = runCatching { Uri.parse(url) }.getOrNull()
                if (uri == null || !isTrustedUrl(uri)) view.stopLoading()
            }
        }

        setContentView(webView)
        requestBluetoothPermissionIfNeeded()
        webView.loadUrl(APP_URL)
    }

    private fun requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                if (granted) "Bluetooth access enabled. Open Settings → Printers."
                else "Allow Nearby devices permission to use a Bluetooth printer.",
                Toast.LENGTH_LONG
            ).show()
            if (granted && ::webView.isInitialized) {
                webView.post { webView.reload() }
            }
        }
    }

    private fun installSecurePrinterBridge(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false

        WebViewCompat.addWebMessageListener(
            webView,
            "MenuFiguresPrinter",
            setOf(TRUSTED_ORIGIN)
        ) { _, message, sourceOrigin, isMainFrame, replyProxy ->
            if (!isMainFrame || !isTrustedOrigin(sourceOrigin)) return@addWebMessageListener

            val payload = message.data
            bridgeExecutor.execute {
                val response = handleBridgeMessage(payload)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        runCatching { replyProxy.postMessage(response) }
                    }
                }
            }
        }
        return true
    }

    private fun isTrustedOrigin(uri: Uri): Boolean =
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(TRUSTED_HOST, ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) &&
            uri.userInfo == null

    private fun isTrustedUrl(uri: Uri): Boolean = isTrustedOrigin(uri)

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun bluetoothAdapter(): BluetoothAdapter? =
        getSystemService(BluetoothManager::class.java)?.adapter

    private fun resultError(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)

    private fun handleBridgeMessage(payload: String): String {
        val request = runCatching { JSONObject(payload) }.getOrNull()
            ?: return JSONObject().put("id", "").put(
                "result",
                resultError("The printer request was not valid JSON.")
            ).toString()

        val id = request.optString("id")
        val args = request.optJSONObject("args") ?: JSONObject()
        val result = try {
            when (request.optString("method")) {
                "getPairedPrinters" -> getPairedPrinters()
                "connect" -> connectPrinter(args.optString("address"))
                "printEscPos" -> printEscPos(args.optString("base64"))
                "disconnect" -> disconnectPrinter()
                else -> resultError("Unsupported printer operation.")
            }
        } catch (error: SecurityException) {
            resultError("Allow Nearby devices permission in Android settings, then try again.")
        } catch (error: Exception) {
            resultError(error.message ?: "The Bluetooth printer operation failed.")
        }
        return JSONObject().put("id", id).put("result", result).toString()
    }

    private fun getPairedPrinters(): JSONObject {
        if (!hasBluetoothPermission()) return resultError("Allow Nearby devices permission in Android settings.")
        val adapter = bluetoothAdapter() ?: return resultError("Bluetooth is not available on this device.")
        if (!adapter.isEnabled) return resultError("Turn on Bluetooth, then refresh paired printers.")

        val printers = JSONArray()
        val audioDeviceNames = Regex("headphone|headset|earbud|airpod|speaker|soundbar", RegexOption.IGNORE_CASE)
        adapter.bondedDevices.orEmpty()
            .filter { device ->
                val majorClass = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
                val isAudioDevice = majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                    audioDeviceNames.containsMatchIn(device.name.orEmpty())
                !isAudioDevice
            }
            .sortedBy { it.name ?: it.address }
            .forEach { device ->
                printers.put(
                    JSONObject()
                        .put("name", device.name ?: "Bluetooth device")
                        .put("address", device.address)
                )
            }
        return JSONObject().put("ok", true).put("printers", printers)
    }

    private fun connectPrinter(address: String): JSONObject {
        if (!hasBluetoothPermission()) return resultError("Allow Nearby devices permission in Android settings.")
        if (address.isBlank()) return resultError("Choose a paired Bluetooth printer first.")

        val adapter = bluetoothAdapter() ?: return resultError("Bluetooth is not available.")
        if (!adapter.isEnabled) return resultError("Turn on Bluetooth before connecting.")
        val device: BluetoothDevice = adapter.bondedDevices.firstOrNull { it.address == address }
            ?: return resultError("This printer is not paired. Pair it in Android Bluetooth settings first.")

        synchronized(this) {
            runCatching { printerSocket?.close() }
            printerSocket = null
        }
        adapter.cancelDiscovery()

        val socket = device.createRfcommSocketToServiceRecord(spp)
        try {
            socket.connect()
            synchronized(this) { printerSocket = socket }
            return JSONObject().put("ok", true).put("name", device.name ?: "Bluetooth printer")
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun printEscPos(base64: String): JSONObject {
        if (!hasBluetoothPermission()) return resultError("Allow Nearby devices permission in Android settings.")
        if (base64.isBlank()) return resultError("Receipt data is empty.")
        if (base64.length > 3_000_000) return resultError("The receipt is too large to send.")

        val data = Base64.decode(base64, Base64.DEFAULT)
        if (data.isEmpty()) return resultError("Receipt data is empty.")
        if (data.size > MAX_RECEIPT_BYTES) return resultError("The receipt exceeds the safe print size.")

        synchronized(this) {
            val socket = printerSocket ?: return resultError("Connect to a paired printer before printing.")
            if (!socket.isConnected) return resultError("The printer connection was lost. Reconnect and try again.")
            socket.outputStream.write(data)
            socket.outputStream.flush()
        }
        return JSONObject().put("ok", true).put("bytesSent", data.size)
    }

    private fun disconnectPrinter(): JSONObject {
        synchronized(this) {
            runCatching { printerSocket?.close() }
            printerSocket = null
        }
        return JSONObject().put("ok", true)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        disconnectPrinter()
        bridgeExecutor.shutdownNow()
        super.onDestroy()
    }
}
