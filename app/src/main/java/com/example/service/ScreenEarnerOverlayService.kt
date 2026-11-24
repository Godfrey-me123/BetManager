package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

/**
 * Notification helper for BetManager status (no static HUD overlay service needed since
 * FloatingHandsOverlayManager uses TYPE_ACCESSIBILITY_OVERLAY directly).
 */
object ScreenEarnerOverlayService {
    private const val CHANNEL_ID = "betmanager_status"
    private const val NOTIFICATION_ID = 4101

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.overlay_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.overlay_channel_description)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    fun updateStatusNotification(context: Context, isRunning: Boolean, statusLine: String) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isRunning) {
            context.getString(R.string.overlay_notification_title)
        } else {
            context.getString(R.string.overlay_notification_paused)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(statusLine)
            .setOngoing(isRunning)
            .setContentIntent(pi)
            .build()

        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    fun clearStatusNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.cancel(NOTIFICATION_ID) }
    }
}
