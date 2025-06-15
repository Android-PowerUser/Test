package com.google.ai.sample

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenCaptureService : Service() {
    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "ScreenCaptureChannel"
        private const val NOTIFICATION_ID = 2001 // Changed as per user
        const val ACTION_START_CAPTURE = "com.google.ai.sample.START_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null // Add this line

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service created")
        createNotificationChannel()
    }

override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.d(TAG, "onStartCommand: action=${intent?.action}")

    // Start foreground immediately
    val notification = createNotification()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    } else {
        startForeground(NOTIFICATION_ID, notification)
    }

    if (intent?.action == ACTION_START_CAPTURE) {
        // Use Activity.RESULT_CANCELED as default and check against Activity.RESULT_OK
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }

        Log.d(TAG, "onStartCommand: resultCode=$resultCode, hasResultData=${resultData != null}")

        // Correctly check if resultCode is Activity.RESULT_OK
        if (resultCode == Activity.RESULT_OK && resultData != null) {
            Handler(Looper.getMainLooper()).postDelayed({
                startCapture(resultCode, resultData)
            }, 200)
        } else {
            Log.e(TAG, "Invalid parameters or permission denied: resultCode=$resultCode (expected ${Activity.RESULT_OK}), resultDataIsPresent=${resultData != null}")
            stopSelf()
        }
    } else {
        Log.e(TAG, "Invalid action: ${intent?.action}")
        stopSelf()
    }

    return START_NOT_STICKY
}

    private fun createNotification(): Notification { // New method from user
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Capture")
            .setContentText("Taking screenshot...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Service", // Updated name
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for screen capture service" // Updated description
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            Log.d(TAG, "startCapture: Getting MediaProjection")
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) { // Added null check
                Log.e(TAG, "MediaProjection is null after getMediaProjection call")
                stopSelf()
                return
            }

            // Create and store the callback instance
            val callback = object : MediaProjection.Callback() { // Assign to local val
                override fun onStop() {
                    Log.w(TAG, "MediaProjection session stopped via callback.")
                    cleanup()
                }
            }
            this.mediaProjectionCallback = callback // Store it in the class member
            mediaProjection?.registerCallback(callback, Handler(Looper.getMainLooper())) // Use local val

            Log.d(TAG, "startCapture: MediaProjection obtained, taking screenshot")
            takeScreenshot() // Call takeScreenshot without delay here, delay is in onStartCommand
        } catch (e: Exception) {
            Log.e(TAG, "Error in startCapture", e)
            stopSelf() // Ensure service stops on error
        }
    }

private fun takeScreenshot() {
    try {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.getRealMetrics(displayMetrics)
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
        }

        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        Log.d(TAG, "Display dimensions: ${width}x${height}, density: $density")

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            object : VirtualDisplay.Callback() { // Non-null Callback
                override fun onPaused() {
                    Log.d(TAG, "VirtualDisplay paused")
                }
                override fun onResumed() {
                    Log.d(TAG, "VirtualDisplay resumed")
                }
                override fun onStopped() {
                    Log.d(TAG, "VirtualDisplay stopped")
                }
            },
            Handler(Looper.getMainLooper()) // Handler for the callback
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            var image: android.media.Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    saveScreenshot(bitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
            } finally {
                image?.close()
                cleanup() // This was in the user's provided code
            }
        }, Handler(Looper.getMainLooper()))

    } catch (e: Exception) {
        Log.e(TAG, "Error taking screenshot", e)
        cleanup()
    }
}

    private fun saveScreenshot(bitmap: Bitmap) {
        try {
            val picturesDir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Screenshots")
            if (!picturesDir.exists()) {
                picturesDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(picturesDir, "screenshot_$timestamp.png")

            val outputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.flush()
            outputStream.close()

            Log.i(TAG, "Screenshot saved to: ${file.absolutePath}")

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    applicationContext,
                    "Screenshot saved to: Android/data/com.google.ai.sample/files/Pictures/Screenshots/",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "Failed to save screenshot: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        // Note: The user's version of the service code calls cleanup() from the image listener's finally block.
        // So, after saving, cleanup() will be invoked by that path.
    }

    private fun cleanup() {
        Log.d(TAG, "Cleaning up resources")
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null

            // Assign to a local immutable variable before use for smart cast
            val callbackInstance = this.mediaProjectionCallback
            if (callbackInstance != null) {
                mediaProjection?.unregisterCallback(callbackInstance) // Use local val
                this.mediaProjectionCallback = null // Nullify the class member
            }

            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Log.d(TAG, "Cleanup finished, service stopped.")
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Service being destroyed")
        cleanup() // Ensure cleanup is called
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
