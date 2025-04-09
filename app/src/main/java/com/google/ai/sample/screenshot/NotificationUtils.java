package com.google.ai.sample.screenshot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.util.Pair;

/**
 * Utility class for creating notifications for the screen capture service
 */
public class NotificationUtils {
    private static final String NOTIFICATION_CHANNEL_ID = "com.google.ai.sample.screenshot.NOTIFICATION_CHANNEL";
    private static final String NOTIFICATION_CHANNEL_NAME = "Screen Capture";
    private static final int NOTIFICATION_ID = 1337;

    /**
     * Creates a notification for the screen capture service
     * @param context The context to use
     * @return A pair containing the notification ID and the notification
     */
    public static Pair<Integer, Notification> getNotification(Context context) {
        createNotificationChannel(context);
        
        Notification notification = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Screen Capture")
                .setContentText("Screen capture service is running")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOngoing(true)
                .build();
        
        return new Pair<>(NOTIFICATION_ID, notification);
    }
    
    /**
     * Creates the notification channel for the screen capture service
     * @param context The context to use
     */
    private static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Channel for screen capture service");
            
            NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }
}
