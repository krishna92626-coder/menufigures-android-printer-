package com.menufigures.printer

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.util.Log
import android.net.http.SslError
import android.webkit.ConsoleMessage
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    companion object {
        private const val APP_URL = "https://preview--menufigures.apper.so"
        private const val TRUSTED_HOST = "preview--menufigures.apper.so"
        private const val TRUSTED_ORIGIN = "https://preview--menufigures.apper.so"
        private const val BLUETOOTH_PERMISSION_REQUEST = 41
        private const val PUSH_PERMISSION_REQUEST = 42
        private const val PUSH_CHANNEL_ID = "menufigures_orders"
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

        createNotificationChannel()
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.e("MenuFiguresWeb", "JS error: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                }
                return true
            }
        }
        bridgeReady = installSecurePrinterBridge()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val allowed = isTrustedUrl(request.url)
                if (!allowed) {
                    Log.w("MenuFiguresWeb", "Blocked navigation to ${request.url}")
                    if (request.isForMainFrame) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "MenuFigures blocked an external redirect: ${request.url.host}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                return !allowed
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                val uri = runCatching { Uri.parse(url) }.getOrNull()
                if (uri == null || !isTrustedUrl(uri)) {
                    Log.w("MenuFiguresWeb", "Stopped untrusted page: $url")
                    view.stopLoading()
                } else {
                    Log.i("MenuFiguresWeb", "Loading page: $url")
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    val detail = "WebView load error ${error.errorCode}: ${error.description}"
                    Log.e("MenuFiguresWeb", "$detail; URL=${request.url}")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, detail, Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                super.onReceivedHttpError(view, request, response)
                if (request.isForMainFrame) {
                    val detail = "MenuFigures server response: HTTP ${response.statusCode}"
                    Log.e("MenuFiguresWeb", "$detail; URL=${request.url}")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, detail, Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                Log.e("MenuFiguresWeb", "SSL error: ${error.primaryError}; URL=${error.url}")
                handler.cancel()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Secure connection failed. Check phone date/time and network, then retry.", Toast.LENGTH_LONG).show()
                }
            }
        }

        setContentView(webView)
        requestBluetoothPermissionIfNeeded()
        webView.loadUrl(APP_URL + safeInternalPath(intent.getStringExtra("push_path") ?: intent.getStringExtra("url")))
    }

    private fun safeInternalPath(value: String?): String {
        if (value.isNullOrBlank() || !value.startsWith("/") || value.startsWith("//")) return ""
        return runCatching {
            val uri = Uri.parse(value)
            if (uri.scheme != null || uri.host != null) "" else value
        }.getOrDefault("")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PUSH_CHANNEL_ID,
                "MenuFigures order alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "New orders and restaurant service requests"
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_PERMISSION_REQUEST)
        } else {
            requestNotificationPermissionIfNeeded()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PUSH_PERMISSION_REQUEST)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val path = safeInternalPath(intent.getStringExtra("push_path") ?: intent.getStringExtra("url"))
        if (path.isNotEmpty() && ::webView.isInitialized) {
            webView.post { webView.loadUrl(APP_URL + path) }
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
            requestNotificationPermissionIfNeeded()
        } else if (requestCode == PUSH_PERMISSION_REQUEST) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                if (granted) "MenuFigures notifications enabled."
                else "Allow notifications in Android settings to receive order alerts.",
                Toast.LENGTH_LONG
            ).show()
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

    private fun getPushToken(): JSONObject {
        if (FirebaseApp.getApps(this).isEmpty()) {
            return resultError("Firebase is not configured in this APK. Add the MenuFigures google-services.json file and rebuild.")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return resultError("Allow notifications for MenuFigures in Android settings, then tap Enable alerts again.")
        }
        val token = Tasks.await(FirebaseMessaging.getInstance().token, 20, TimeUnit.SECONDS)
        if (token.isBlank()) return resultError("Firebase returned an empty device token.")
        return JSONObject().put("ok", true).put("token", token)
    }

    private fun deletePushToken(): JSONObject {
        if (FirebaseApp.getApps(this).isEmpty()) {
            return resultError("Firebase is not configured in this APK.")
        }
        Tasks.await(FirebaseMessaging.getInstance().deleteToken(), 20, TimeUnit.SECONDS)
        return JSONObject().put("ok", true)
    }

    private fun subscribeToTopic(topic: String): JSONObject {
        if (FirebaseApp.getApps(this).isEmpty()) {
            return resultError("Firebase is not configured in this APK.")
        }
        if (!Regex("^mf_[0-9a-f]{64}$").matches(topic)) {
            return resultError("The store notification channel is invalid.")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return resultError("Allow notifications for MenuFigures in Android settings, then try again.")
        }
        Tasks.await(FirebaseMessaging.getInstance().subscribeToTopic(topic), 20, TimeUnit.SECONDS)
        return JSONObject().put("ok", true)
    }

    private fun unsubscribeFromTopic(topic: String): JSONObject {
        if (FirebaseApp.getApps(this).isEmpty()) {
            return resultError("Firebase is not configured in this APK.")
        }
        if (!Regex("^mf_[0-9a-f]{64}$").matches(topic)) {
            return resultError("The store notification channel is invalid.")
        }
        Tasks.await(FirebaseMessaging.getInstance().unsubscribeFromTopic(topic), 20, TimeUnit.SECONDS)
        return JSONObject().put("ok", true)
    }

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
                "getPushToken" -> getPushToken()
                "deletePushToken" -> deletePushToken()
                "subscribeToTopic" -> subscribeToTopic(args.optString("topic"))
                "unsubscribeFromTopic" -> unsubscribeFromTopic(args.optString("topic"))
                else -> resultError("Unsupported printer operation.")
            }
        } catch (error: SecurityException) {
            resultError("Allow Nearby devices or notification permission in Android settings, then try again.")
        } catch (error: Exception) {
            resultError(error.message ?: "The Android device operation failed.")
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
