package com.google.ai.sample

import android.app.Activity // Make sure this import is present
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import android.net.Uri // Added for broadcasting URI
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
        private const val NOTIFICATION_ID = 2001
        const val ACTION_START_CAPTURE = "com.google.ai.sample.START_CAPTURE"
        const val ACTION_TAKE_SCREENSHOT = "com.google.ai.sample.TAKE_SCREENSHOT" // New action
        const val ACTION_STOP_CAPTURE = "com.google.ai.sample.STOP_CAPTURE"   // New action
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private var instance: ScreenCaptureService? = null

        fun isRunning(): Boolean = instance != null && instance?.isReady == true
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isReady = false // Flag to indicate if MediaProjection is set up and active

    // Callback for MediaProjection
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection session stopped externally (via callback). Cleaning up.")
            cleanup() // Perform full cleanup if projection stops unexpectedly
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "onCreate: Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}, isReady=$isReady, mediaProjectionIsNull=${mediaProjection==null}")

        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val notification = createNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                Log.d(TAG, "Service started in foreground for ACTION_START_CAPTURE.")

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                }

                Log.d(TAG, "onStartCommand (START_CAPTURE): resultCode=$resultCode, hasResultData=${resultData != null}")

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startCapture(resultCode, resultData)
                } else {
                    Log.e(TAG, "Invalid parameters for START_CAPTURE: resultCode=$resultCode (expected ${Activity.RESULT_OK}), resultDataIsNull=${resultData == null}")
                    cleanup() // Use cleanup to stop foreground and self
                }
            }
            ACTION_TAKE_SCREENSHOT -> {
                Log.d(TAG, "Received ACTION_TAKE_SCREENSHOT.")
                if (isReady && mediaProjection != null) {
                    takeScreenshot()
                } else {
                    Log.e(TAG, "Service not ready or MediaProjection not available for TAKE_SCREENSHOT. isReady=$isReady, mediaProjectionIsNull=${mediaProjection == null}")
                    Toast.makeText(this, "Screenshot service not ready. Please re-grant permission if necessary.", Toast.LENGTH_LONG).show()
                    // Optionally, broadcast a failure or request MainActivity to re-initiate.
                    // If not ready, and this action is called, it implies a logic error or race condition.
                    // MainActivity should ideally prevent calling this if service isn't running/ready.
                }
            }
            ACTION_STOP_CAPTURE -> {
                Log.d(TAG, "Received ACTION_STOP_CAPTURE. Cleaning up.")
                cleanup()
            }
            else -> {
                Log.w(TAG, "Unknown or null action received: ${intent?.action}.")
                // If service is started with unknown action and not ready, stop it.
                if (!isReady) {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Capture Active")
            .setContentText("Ready to take screenshots")
            .setSmallIcon(android.R.drawable.ic_menu_camera) // Replace with a proper app icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for screen capture service"
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

            mediaProjection?.unregisterCallback(mediaProjectionCallback) // Unregister old before stopping
            mediaProjection?.stop() // Stop any existing projection

            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection is null after getMediaProjection call")
                isReady = false
                cleanup() // Use cleanup to stop foreground and self
                return
            }
            mediaProjection?.registerCallback(mediaProjectionCallback, Handler(Looper.getMainLooper()))
            isReady = true
            Log.d(TAG, "MediaProjection ready.")

            Handler(Looper.getMainLooper()).postDelayed({
                if(isReady && mediaProjection != null) {
                    Log.d(TAG, "Taking initial screenshot after delay.")
                    takeScreenshot()
                } else {
                    Log.w(TAG, "Conditions to take initial screenshot not met after delay. isReady=$isReady, mediaProjectionIsNull=${mediaProjection==null}")
                }
            }, 500)

        } catch (e: Exception) {
            Log.e(TAG, "Error in startCapture", e)
            isReady = false
            cleanup() // Use cleanup to stop foreground and self
        }
    }

private fun takeScreenshot() {
    if (!isReady || mediaProjection == null) {
        Log.e(TAG, "Cannot take screenshot - service not ready or mediaProjection is null. isReady=$isReady, mediaProjectionIsNull=${mediaProjection == null}")
        return
    }
    Log.d(TAG, "takeScreenshot: Preparing to capture.")

    try {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val defaultDisplay = windowManager.defaultDisplay
            if (defaultDisplay != null) {
                defaultDisplay.getRealMetrics(displayMetrics)
            } else {
                val bounds = windowManager.currentWindowMetrics.bounds
                displayMetrics.widthPixels = bounds.width()
                displayMetrics.heightPixels = bounds.height()
                displayMetrics.densityDpi = resources.displayMetrics.densityDpi
            }
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
        }

        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        if (width <= 0 || height <= 0) {
            Log.e(TAG, "Invalid display dimensions: ${width}x${height}. Cannot create ImageReader.")
            return
        }
        Log.d(TAG, "Display dimensions: ${width}x${height}, density: $density")

        // Clean up previous ImageReader and VirtualDisplay if they exist (for subsequent screenshots)
        // This was in user's latest version for takeScreenshot, ensuring fresh resources per shot
        this.imageReader?.close()
        this.virtualDisplay?.release()

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
        val localImageReader = imageReader ?: run { // Check if new instance is null
            Log.e(TAG, "ImageReader is null after newInstance attempt.")
            return
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            localImageReader.surface, // Use the new localImageReader instance
            object : VirtualDisplay.Callback() {
                override fun onPaused() { Log.d(TAG, "VirtualDisplay paused") }
                override fun onResumed() { Log.d(TAG, "VirtualDisplay resumed") }
                override fun onStopped() { Log.d(TAG, "VirtualDisplay stopped") }
            },
            Handler(Looper.getMainLooper())
        )

        if (virtualDisplay == null) {
            Log.e(TAG, "Failed to create VirtualDisplay.")
            localImageReader.close() // Clean up the reader we just created
            this.imageReader = null
            return
        }

        localImageReader.setOnImageAvailableListener({ reader -> // 'reader' here is localImageReader
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
                    Log.d(TAG, "Bitmap created, proceeding to save.")
                    saveScreenshot(bitmap)
                } else {
                    Log.w(TAG, "acquireLatestImage returned null.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
            } finally {
                image?.close()
                // Selective cleanup: only VD and the listener's ImageReader instance.
                this.virtualDisplay?.release() // Release the class member VD
                this.virtualDisplay = null

                reader.close() // Close the 'reader' from the listener parameter
                // If the class member 'imageReader' was indeed this instance, nullify it.
                // This is important because a new ImageReader is created at the start of takeScreenshot.
                if (this.imageReader == reader) {
                     this.imageReader = null
                }
                Log.d(TAG, "Per-screenshot resources (VD, IR from listener) cleaned up. MediaProjection remains active.")
            }
        }, Handler(Looper.getMainLooper()))

    } catch (e: Exception) {
        Log.e(TAG, "Error in takeScreenshot setup", e)
        // If setup fails, cleanup per-shot resources if they were initialized
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close() // This refers to the class member
        imageReader = null
        // Do NOT call the main cleanup() here, as MediaProjection should stay alive if possible
        // and if an error occurs before even getting to the listener.
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

            val screenshotUri = Uri.fromFile(file)
            val intent = Intent(MainActivity.ACTION_MEDIAPROJECTION_SCREENSHOT_CAPTURED).apply {
                putExtra(MainActivity.EXTRA_SCREENSHOT_URI, screenshotUri.toString())
                `package` = applicationContext.packageName
            }
            applicationContext.sendBroadcast(intent)
            Log.d(TAG, "Sent broadcast ACTION_MEDIAPROJECTION_SCREENSHOT_CAPTURED with URI: $screenshotUri")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "Failed to save screenshot: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun cleanup() {
        Log.d(TAG, "cleanup() called. Cleaning up all MediaProjection resources.")
        try {
            isReady = false
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null

            mediaProjection?.unregisterCallback(mediaProjectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error during full cleanup", e)
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf() // This will trigger onDestroy eventually
            instance = null // Clear static instance
            Log.d(TAG, "Full cleanup finished, service fully stopped.")
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Service being destroyed")
        // Cleanup is called from ACTION_STOP_CAPTURE or if projection stops externally.
        // If service is killed by system, this ensures cleanup too.
        if (isReady || mediaProjection != null) { // Check if cleanup is actually needed
           cleanup()
        }
        instance = null // Ensure instance is cleared
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
