package com.google.ai.sample

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.util.Pair

/**
 * Utility class for creating notifications for the screenshot service
 */
object NotificationUtils {
    const val NOTIFICATION_ID = 1001
    private const val NOTIFICATION_CHANNEL_ID = "com.google.ai.sample.screenshot_channel"
    private const val NOTIFICATION_CHANNEL_NAME = "Screenshot Service"

    /**
     * Creates and returns a notification for the screenshot service
     * @param context The context to use for creating the notification
     * @return A pair containing the notification ID and the notification
     */
    fun getNotification(context: Context): Pair<Int, Notification> {
        createNotificationChannel(context)
        val notification = createNotification(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        return Pair(NOTIFICATION_ID, notification)
    }

    /**
     * Creates a notification channel for the screenshot service
     * @param context The context to use for creating the notification channel
     */
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Used for screenshot capture"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Creates a notification for the screenshot service
     * @param context The context to use for creating the notification
     * @return The created notification
     */
    private fun createNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Screenshot Service")
            .setContentText("Capturing screen content")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(true)
            .build()
    }
}
