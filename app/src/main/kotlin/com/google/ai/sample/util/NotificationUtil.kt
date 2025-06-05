package com.google.ai.sample.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.ai.sample.MainActivity
// Import R class if needed for custom drawables, for android.R.drawable it's not needed.
// import com.google.ai.sample.R

object NotificationUtil {

    const val CHANNEL_ID = "screen_operator_stop_channel"
    const val CHANNEL_NAME = "Screen Operator Controls"
    const val NOTIFICATION_ID = 1001
    const val ACTION_STOP_OPERATION = "com.google.ai.sample.ACTION_STOP_OPERATION"
    private const val TAG = "NotificationUtil"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Less intrusive
            ).apply {
                description = "Channel for Screen Operator stop controls"
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created: $CHANNEL_ID")
        }
    }

    fun showStopNotification(context: Context) {
        // Create an Intent for MainActivity
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_STOP_OPERATION
            // Flags to bring MainActivity to front if already running, or start it.
            // Clears other activities on top of MainActivity and makes it the new root.
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        // Create PendingIntent
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context,
            0, // requestCode
            intent,
            pendingIntentFlags
        )

        // Build the notification
        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // Using a standard system icon
            .setContentTitle("Screen Operator")
            .setContentText("Stop Screen Operator")
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Makes the notification persistent
            .setSilent(true)  // No sound for this notification
            // .setPriority(NotificationCompat.PRIORITY_LOW) // Alternative to setSilent for older versions

        val notificationManager = NotificationManagerCompat.from(context)

        // Check for notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
                    Log.d(TAG, "Stop notification shown.")
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException while showing notification, even though permission was granted.", e)
                }
            } else {
                Log.w(TAG, "Cannot show stop notification: POST_NOTIFICATIONS permission not granted.")
                // Optionally, inform the user via a Toast or other means if this is critical
                // Toast.makeText(context, "Notification permission needed to show stop control.", Toast.LENGTH_LONG).show()
            }
        } else {
            // For older versions, permission is not needed at runtime in this manner
            try {
                notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
                Log.d(TAG, "Stop notification shown (pre-Android 13).")
            } catch (e: Exception) {
                // Catch any other potential exceptions, though less likely for notifications on older versions
                Log.e(TAG, "Exception while showing notification (pre-Android 13).", e)
            }
        }
    }

    fun cancelStopNotification(context: Context) {
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.cancel(NOTIFICATION_ID)
        Log.d(TAG, "Stop notification cancelled.")
    }
}
