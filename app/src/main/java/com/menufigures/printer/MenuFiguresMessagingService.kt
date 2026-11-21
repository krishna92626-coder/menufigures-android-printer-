package com.menufigures.printer

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MenuFiguresMessagingService : FirebaseMessagingService() {
    companion object {
        private const val CHANNEL_ID = "menufigures_orders"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "MenuFigures"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: "You have a new restaurant update."
        val path = safeInternalPath(message.data["url"])

        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("push_path", path)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(
                (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                notification
            )
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // The WebView bridge reads the current FCM token after the vendor signs in
        // and syncs it with the correct store. Never associate tokens with a user here.
    }

    private fun safeInternalPath(value: String?): String {
        if (value.isNullOrBlank() || !value.startsWith("/") || value.startsWith("//")) return "/notifications"
        return runCatching {
            val uri = android.net.Uri.parse(value)
            if (uri.scheme != null || uri.host != null) "/notifications" else value
        }.getOrDefault("/notifications")
    }
}
