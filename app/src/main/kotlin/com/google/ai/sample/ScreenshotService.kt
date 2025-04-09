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
import java.util.concurrent.atomic.AtomicBoolean

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
    
    // Flag to track if a screenshot is in progress
    private val screenshotInProgress = AtomicBoolean(false)
    
    // Flag to track if MediaProjection is initialized
    private val isMediaProjectionInitialized = AtomicBoolean(false)
    
    inner class LocalBinder : Binder() {
        fun getService(): ScreenshotService = this@ScreenshotService
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Get screen metrics
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            screenDensity = metrics.densityDpi
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        
        Log.d(TAG, "Screen metrics: $screenWidth x $screenHeight, density: $screenDensity")
        
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                
                if (resultCode != -1 && data != null) {
                    // CRITICAL: Start foreground immediately to avoid ANR
                    startForeground(NOTIFICATION_ID, createNotification())
                    
                    // Clean up any existing MediaProjection
                    tearDown()
                    
                    // Initialize MediaProjection
                    try {
                        initializeMediaProjection(resultCode, data)
                        isMediaProjectionInitialized.set(true)
                        Log.d(TAG, "MediaProjection initialized successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to initialize MediaProjection: ${e.message}")
                        isMediaProjectionInitialized.set(false)
                        stopSelf()
                    }
                } else {
                    Log.e(TAG, "Invalid result code or data")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                Log.d(TAG, "Received stop action")
                tearDown()
                stopForeground(true)
                stopSelf()
            }
            else -> {
                Log.d(TAG, "Received unknown action: ${intent?.action}")
            }
        }
        
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
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
        
        // Create a new MediaProjection instance
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data).apply {
            // Register callback to handle MediaProjection stop events
            registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system or user")
                    isMediaProjectionInitialized.set(false)
                    
                    handler.post {
                        tearDownVirtualDisplay()
                        // Notify UI if needed
                    }
                }
            }, handler)
        }
        
        Log.d(TAG, "MediaProjection initialized with resultCode: $resultCode")
    }
    
    fun isMediaProjectionReady(): Boolean {
        val ready = isMediaProjectionInitialized.get() && mediaProjection != null
        Log.d(TAG, "isMediaProjectionReady: $ready")
        return ready
    }
    
    fun takeScreenshot(callback: (Bitmap?) -> Unit) {
        // Prevent multiple simultaneous screenshot attempts
        if (!screenshotInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "Screenshot already in progress, ignoring request")
            handler.post { callback(null) }
            return
        }
        
        // Check if MediaProjection is initialized
        if (!isMediaProjectionInitialized.get() || mediaProjection == null) {
            Log.e(TAG, "MediaProjection not initialized yet, cannot take screenshot")
            screenshotInProgress.set(false)
            handler.post { callback(null) }
            return
        }
        
        try {
            // Create ImageReader
            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2  // Buffer size
            )
            
            // Set up image listener before creating virtual display
            val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
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
                    screenshotInProgress.set(false)
                    callback(bitmap)
                }
            }
            
            imageReader?.setOnImageAvailableListener(imageAvailableListener, handler)
            
            // Create virtual display
            val display = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                object : VirtualDisplay.Callback() {
                    override fun onStopped() {
                        Log.d(TAG, "VirtualDisplay stopped")
                    }
                },
                handler
            )
            
            if (display == null) {
                Log.e(TAG, "Failed to create virtual display")
                tearDownVirtualDisplay()
                screenshotInProgress.set(false)
                handler.post { callback(null) }
                return
            }
            
            virtualDisplay = display
            
            // Add a timeout to prevent hanging
            handler.postDelayed({
                if (screenshotInProgress.get()) {
                    Log.e(TAG, "Screenshot timed out")
                    tearDownVirtualDisplay()
                    screenshotInProgress.set(false)
                    callback(null)
                }
            }, 3000) // 3 second timeout
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up screenshot: ${e.message}")
            tearDownVirtualDisplay()
            screenshotInProgress.set(false)
            handler.post { callback(null) }
        }
    }
    
    fun saveBitmapToFile(bitmap: Bitmap): File? {
        val fileName = "screenshot_${UUID.randomUUID()}.png"
        val file = File(cacheDir, fileName)
        
        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            Log.d(TAG, "Screenshot saved to ${file.absolutePath}")
            file
        } catch (e: IOException) {
            Log.e(TAG, "Error saving bitmap: ${e.message}")
            null
        }
    }
    
    private fun tearDownVirtualDisplay() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            
            imageReader?.close()
            imageReader = null
            
            Log.d(TAG, "Virtual display torn down")
        } catch (e: Exception) {
            Log.e(TAG, "Error tearing down virtual display: ${e.message}")
        }
    }
    
    private fun tearDown() {
        try {
            tearDownVirtualDisplay()
            
            mediaProjection?.let {
                try {
                    it.stop()
                    Log.d(TAG, "MediaProjection stopped")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping MediaProjection: ${e.message}")
                }
            }
            
            mediaProjection = null
            isMediaProjectionInitialized.set(false)
            
            Log.d(TAG, "All resources torn down")
        } catch (e: Exception) {
            Log.e(TAG, "Error tearing down resources: ${e.message}")
        }
    }
}
