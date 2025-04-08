package com.google.ai.sample

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScreenshotService : Service() {
    companion object {
        private const val TAG = "ScreenshotService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screenshot_channel"
        private const val CHANNEL_NAME = "Screenshot Service"
        
        const val ACTION_START = "com.google.ai.sample.action.START_SCREENSHOT"
        const val ACTION_STOP = "com.google.ai.sample.action.STOP_SCREENSHOT"
        
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
    }
    
    private val binder = LocalBinder()
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenDensity: Int = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private val handler = Handler(Looper.getMainLooper())
    
    // Synchronization for MediaProjection initialization
    private val mediaProjectionInitLatch = CountDownLatch(1)
    private var isMediaProjectionInitialized = false
    
    private var screenshotCallback: ((Bitmap?) -> Unit)? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): ScreenshotService = this@ScreenshotService
    }
    
    override fun onCreate() {
        super.onCreate()
        
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        screenDensity = metrics.densityDpi
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                
                if (resultCode != -1 && data != null) {
                    startForeground(NOTIFICATION_ID, createNotification())
                    
                    // Initialize MediaProjection in a background thread to avoid ANR
                    Thread {
                        try {
                            initializeMediaProjection(resultCode, data)
                            isMediaProjectionInitialized = true
                            mediaProjectionInitLatch.countDown()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to initialize MediaProjection: ${e.message}")
                            isMediaProjectionInitialized = false
                            mediaProjectionInitLatch.countDown()
                        }
                    }.start()
                }
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        tearDown()
        super.onDestroy()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Used for screenshot capture"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screenshot Service")
            .setContentText("Capturing screen content")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    private fun initializeMediaProjection(resultCode: Int, data: Intent) {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
    }
    
    fun takeScreenshot(callback: (Bitmap?) -> Unit) {
        screenshotCallback = callback
        
        // Wait for MediaProjection to be initialized with a timeout
        try {
            if (!isMediaProjectionInitialized && !mediaProjectionInitLatch.await(3, TimeUnit.SECONDS)) {
                Log.e(TAG, "Timeout waiting for MediaProjection initialization")
                handler.post { callback(null) }
                return
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while waiting for MediaProjection initialization")
            handler.post { callback(null) }
            return
        }
        
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection not initialized")
            handler.post { callback(null) }
            return
        }
        
        // Create ImageReader
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            1
        )
        
        // Create virtual display
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )
        
        // Capture image
        imageReader?.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            var bitmap: Bitmap? = null
            
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth
                    
                    // Create bitmap
                    bitmap = Bitmap.createBitmap(
                        screenWidth + rowPadding / pixelStride,
                        screenHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    
                    // Crop bitmap to exact screen size if needed
                    if (bitmap.width > screenWidth || bitmap.height > screenHeight) {
                        bitmap = Bitmap.createBitmap(
                            bitmap,
                            0,
                            0,
                            screenWidth,
                            screenHeight
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing screen: ${e.message}")
                bitmap = null
            } finally {
                image?.close()
                tearDownVirtualDisplay()
                screenshotCallback?.invoke(bitmap)
            }
        }, handler)
        
        // Add a delay to ensure the virtual display is set up
        handler.postDelayed({
            if (imageReader?.surface == null) {
                Log.e(TAG, "Surface is null")
                tearDownVirtualDisplay()
                callback(null)
            }
        }, 100)
    }
    
    fun saveBitmapToFile(bitmap: Bitmap): File? {
        val fileName = "screenshot_${UUID.randomUUID()}.png"
        val file = File(cacheDir, fileName)
        
        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file
        } catch (e: IOException) {
            Log.e(TAG, "Error saving bitmap: ${e.message}")
            null
        }
    }
    
    private fun tearDownVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        
        imageReader?.close()
        imageReader = null
    }
    
    private fun tearDown() {
        tearDownVirtualDisplay()
        mediaProjection?.stop()
        mediaProjection = null
    }
}
