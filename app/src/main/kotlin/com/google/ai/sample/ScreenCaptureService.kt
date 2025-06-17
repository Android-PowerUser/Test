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
        const val EXTRA_TAKE_SCREENSHOT_ON_START = "take_screenshot_on_start"

        private var instance: ScreenCaptureService? = null

        fun isRunning(): Boolean = instance != null && instance?.isReady == true
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isReady = false // Flag to indicate if MediaProjection is set up and active
    private val isScreenshotRequestedRef = java.util.concurrent.atomic.AtomicBoolean(false)

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var latestActivityManagerResultCode: Int? = null
    private var latestActivityManagerResultData: android.content.Intent? = null

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
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
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
                    this.latestActivityManagerResultCode = resultCode
                    this.latestActivityManagerResultData = resultData
                    Log.d(TAG, "Stored latest permission grant data.")
                    val takeScreenshotFlag = intent.getBooleanExtra(EXTRA_TAKE_SCREENSHOT_ON_START, false)
                    startCapture(resultCode, resultData, takeScreenshotFlag)
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

    private fun startCapture(resultCode: Int, data: Intent, takeScreenshotOnStart: Boolean) {
        try {
            Log.d(TAG, "startCapture: Using member mediaProjectionManager, takeScreenshotOnStart: $takeScreenshotOnStart")
            // val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager // Now using member variable

            mediaProjection?.unregisterCallback(mediaProjectionCallback) // Unregister old before stopping
            mediaProjection?.stop() // Stop any existing projection

            mediaProjection = this.mediaProjectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection is null after getMediaProjection call")
                isReady = false
                cleanup() // Use cleanup to stop foreground and self
                return
            }
            mediaProjection?.registerCallback(mediaProjectionCallback, Handler(Looper.getMainLooper()))
            isReady = true
            Log.d(TAG, "MediaProjection ready.")

            if (takeScreenshotOnStart) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if(isReady && mediaProjection != null) {
                        Log.d(TAG, "startCapture: Taking initial screenshot after delay because takeScreenshotOnStart was true.")
                        takeScreenshot()
                    } else {
                        Log.w(TAG, "startCapture: Conditions to take initial screenshot not met after delay, even though takeScreenshotOnStart was true. isReady=$isReady, mediaProjectionIsNull=${mediaProjection==null}")
                    }
                }, 500)
            } else {
                Log.d(TAG, "startCapture: MediaProjection initialized, but skipping immediate screenshot as takeScreenshotOnStart is false.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in startCapture", e)
            isReady = false
            cleanup() // Use cleanup to stop foreground and self
        }
    }

private fun takeScreenshot() {
    isScreenshotRequestedRef.set(true) // Set flag for the current attempt first

    if (mediaProjection == null) { // If current projection is null
        if (latestActivityManagerResultCode != null && latestActivityManagerResultData != null) {
            Log.d(TAG, "takeScreenshot: MediaProjection is null, attempting to re-initialize from stored token.")
            try {
                // mediaProjectionManager is already initialized in onCreate
                mediaProjection = mediaProjectionManager.getMediaProjection(latestActivityManagerResultCode!!, latestActivityManagerResultData!!)
                if (mediaProjection != null) {
                    mediaProjection!!.registerCallback(mediaProjectionCallback, Handler(Looper.getMainLooper()))
                    // Reset virtualDisplay and imageReader so they are re-created with the new projection
                    virtualDisplay?.release()
                    virtualDisplay = null
                    imageReader?.close()
                    imageReader = null
                    isReady = true // Mark as ready to proceed with capture setup
                    Log.d(TAG, "takeScreenshot: MediaProjection re-initialized successfully.")
                    // The existing logic below for setting up ImageReader/VirtualDisplay will now run
                } else {
                    Log.e(TAG, "takeScreenshot: Failed to re-initialize MediaProjection from stored token.")
                    isReady = false
                    return // Cannot proceed
                }
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot: Exception during MediaProjection re-initialization", e)
                isReady = false
                mediaProjection = null // Ensure it's null on failure
                return // Cannot proceed
            }
        } else {
            Log.e(TAG, "takeScreenshot: MediaProjection is null and no valid stored grant data available. Cannot capture.")
            isReady = false // Ensure isReady reflects this state
            return // Cannot proceed
        }
    }

    // Original check after re-init attempt (or if projection was already valid)
    if (!isReady || mediaProjection == null) {
        Log.e(TAG, "Cannot take screenshot - service not ready or mediaProjection is null after re-init attempt. isReady=$isReady, mediaProjectionIsNull=${mediaProjection == null}")
        return
    }
    // isScreenshotRequestedRef.set(true); // Moved to the top
    Log.d(TAG, "takeScreenshot: Preparing to capture. isScreenshotRequestedRef is true.")

    try {
        // Check if we need to initialize VirtualDisplay and ImageReader
        if (virtualDisplay == null || imageReader == null) {
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

            imageReader?.close() // Close previous reader if any
            virtualDisplay?.release() // Release previous display if any

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
            val localImageReader = imageReader ?: run {
                Log.e(TAG, "ImageReader is null after creation attempt.")
                return
            }

            localImageReader.setOnImageAvailableListener({ reader ->
                if (isScreenshotRequestedRef.compareAndSet(true, false)) {
                    Log.d(TAG, "Screenshot request flag consumed, processing image.")
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
                            Log.w(TAG, "acquireLatestImage returned null despite requested flag.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing image in listener", e)
                    } finally {
                        image?.close()
                        // Do NOT release VirtualDisplay or ImageReader here
                        // They will be reused for the next screenshot
                        Log.d(TAG, "Screenshot processed (or attempted), keeping resources for reuse.")
                    }
                } else {
                    // Logic to discard the frame if no screenshot was formally requested
                    Log.w(TAG, "OnImageAvailableListener invoked but no screenshot was requested or flag already consumed. Discarding frame.")
                    var imageToDiscard: android.media.Image? = null
                    try {
                        imageToDiscard = reader.acquireLatestImage()
                    } catch (e: Exception) {
                        // This catch is important because acquireLatestImage can fail if buffers are truly messed up
                        Log.e(TAG, "Error acquiring image to discard in OnImageAvailableListener else block", e)
                    } finally {
                        imageToDiscard?.close()
                    }
                }
            }, Handler(Looper.getMainLooper()))

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                localImageReader.surface,
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
            Log.d(TAG, "VirtualDisplay and ImageReader initialized for reuse.")
        } else {
            // Resources already exist, just trigger a new capture
            Log.d(TAG, "Using existing VirtualDisplay and ImageReader.")
            // Force the ImageReader to capture a new frame
            // The listener is already set up and will handle the new image
        }

    } catch (e: Exception) {
        Log.e(TAG, "Error in takeScreenshot setup", e)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
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
            // latestActivityManagerResultCode and latestActivityManagerResultData are NOT cleared
        } catch (e: Exception) {
            Log.e(TAG, "Error during media resource cleanup", e)
        } finally {
            // Service is kept alive, but foreground state might need adjustment if no longer actively capturing.
            // For now, only remove notification if service is meant to be truly idle.
            // If it's expected to be ready for takeScreenshot() calls, it might need to remain foreground.
            // Task implies service stays alive, so let's remove stopForeground for now,
            // as it might be restarted by a takeScreenshot() call shortly.
            // stopForeground(STOP_FOREGROUND_REMOVE) // Re-evaluate if notification should persist
            Log.d(TAG, "Media resources cleaned up. Service remains active.")
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Service being destroyed")
        cleanup() // Ensure all media resources are released
        latestActivityManagerResultCode = null // Clear stored grant data when service is destroyed
        latestActivityManagerResultData = null
        instance = null // Clear static instance
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
