package com.google.ai.sample

import android.app.Notification
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
import android.util.Log
import android.view.Display
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.core.util.Pair
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class ScreenshotService : Service() {
    companion object {
        private const val TAG = "ScreenshotService"
        private const val SCREENCAP_NAME = "screencap"
        
        const val ACTION_START = "com.google.ai.sample.action.START_SCREENSHOT"
        const val ACTION_STOP = "com.google.ai.sample.action.STOP_SCREENSHOT"
        
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        
        private var IMAGES_PRODUCED = 0
        
        /**
         * Creates an intent to start the screenshot service
         * @param context The context to use for creating the intent
         * @param resultCode The result code from onActivityResult
         * @param data The intent data from onActivityResult
         * @return The intent to start the service
         */
        fun getStartIntent(context: Context, resultCode: Int, data: Intent): Intent {
            val intent = Intent(context, ScreenshotService::class.java)
            intent.putExtra(ACTION, ACTION_START)
            intent.putExtra(EXTRA_RESULT_CODE, resultCode)
            intent.putExtra(EXTRA_DATA, data)
            return intent
        }
        
        /**
         * Creates an intent to stop the screenshot service
         * @param context The context to use for creating the intent
         * @return The intent to stop the service
         */
        fun getStopIntent(context: Context): Intent {
            val intent = Intent(context, ScreenshotService::class.java)
            intent.putExtra(ACTION, ACTION_STOP)
            return intent
        }
        
        private const val ACTION = "action"
    }
    
    private val binder = LocalBinder()
    
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var handler: Handler? = null
    private var display: Display? = null
    private var screenDensity = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private var rotation = 0
    private var orientationChangeCallback: OrientationChangeCallback? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): ScreenshotService = this@ScreenshotService
    }
    
    private inner class ImageAvailableListener : ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader) {
            var image: Image? = null
            var bitmap: Bitmap? = null
            var fos: FileOutputStream? = null
            
            try {
                image = imageReader?.acquireLatestImage()
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
                    
                    // Save bitmap to file
                    val fileName = "screenshot_${UUID.randomUUID()}.png"
                    val file = File(cacheDir, fileName)
                    fos = FileOutputStream(file)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    
                    IMAGES_PRODUCED++
                    Log.d(TAG, "Screenshot captured: $IMAGES_PRODUCED")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing screenshot: ${e.message}")
            } finally {
                try {
                    fos?.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Error closing output stream: ${e.message}")
                }
                
                bitmap?.recycle()
                image?.close()
            }
        }
    }
    
    private inner class OrientationChangeCallback(context: Context) : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            val currentRotation = display?.rotation ?: return
            if (currentRotation != rotation) {
                rotation = currentRotation
                try {
                    // Clean up existing virtual display
                    virtualDisplay?.release()
                    imageReader?.setOnImageAvailableListener(null, null)
                    
                    // Recreate virtual display
                    createVirtualDisplay()
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling orientation change: ${e.message}")
                }
            }
        }
    }
    
    private inner class MediaProjectionStopCallback : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped")
            handler?.post {
                virtualDisplay?.release()
                imageReader?.setOnImageAvailableListener(null, null)
                orientationChangeCallback?.disable()
                mediaProjection?.unregisterCallback(this@MediaProjectionStopCallback)
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Start capture handling thread
        Thread {
            Looper.prepare()
            handler = Handler(Looper.myLooper()!!)
            Looper.loop()
        }.start()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL: Start foreground immediately to avoid ANR
        val notification = NotificationUtils.getNotification(this)
        startForeground(notification.first, notification.second)
        
        if (intent != null) {
            when {
                isStartCommand(intent) -> {
                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                    val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_DATA)
                    }
                    
                    if (resultCode != -1 && data != null) {
                        startProjection(resultCode, data)
                    } else {
                        Log.e(TAG, "Invalid result code or data")
                        stopSelf()
                    }
                }
                isStopCommand(intent) -> {
                    stopProjection()
                    stopSelf()
                }
                else -> {
                    Log.d(TAG, "Unknown action: ${intent.action}")
                    stopSelf()
                }
            }
        } else {
            Log.e(TAG, "Intent is null")
            stopSelf()
        }
        
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        stopProjection()
        super.onDestroy()
    }
    
    /**
     * Checks if the intent is a start command
     * @param intent The intent to check
     * @return True if the intent is a start command, false otherwise
     */
    private fun isStartCommand(intent: Intent): Boolean {
        return intent.hasExtra(EXTRA_RESULT_CODE) && 
               intent.hasExtra(EXTRA_DATA) && 
               intent.hasExtra(ACTION) && 
               intent.getStringExtra(ACTION) == ACTION_START
    }
    
    /**
     * Checks if the intent is a stop command
     * @param intent The intent to check
     * @return True if the intent is a stop command, false otherwise
     */
    private fun isStopCommand(intent: Intent): Boolean {
        return intent.hasExtra(ACTION) && intent.getStringExtra(ACTION) == ACTION_STOP
    }
    
    /**
     * Starts the media projection
     * @param resultCode The result code from onActivityResult
     * @param data The intent data from onActivityResult
     */
    private fun startProjection(resultCode: Int, data: Intent) {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        if (mediaProjection == null) {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            
            if (mediaProjection != null) {
                // Get display metrics
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                screenDensity = resources.displayMetrics.densityDpi
                display = windowManager.defaultDisplay
                
                // Get screen dimensions
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bounds = windowManager.currentWindowMetrics.bounds
                    screenWidth = bounds.width()
                    screenHeight = bounds.height()
                } else {
                    @Suppress("DEPRECATION")
                    screenWidth = resources.displayMetrics.widthPixels
                    @Suppress("DEPRECATION")
                    screenHeight = resources.displayMetrics.heightPixels
                }
                
                Log.d(TAG, "Screen metrics: $screenWidth x $screenHeight, density: $screenDensity")
                
                // Create virtual display
                createVirtualDisplay()
                
                // Register orientation change callback
                orientationChangeCallback = OrientationChangeCallback(this)
                if (orientationChangeCallback?.canDetectOrientation() == true) {
                    orientationChangeCallback?.enable()
                }
                
                // Register media projection stop callback
                mediaProjection?.registerCallback(MediaProjectionStopCallback(), handler)
                
                Log.d(TAG, "MediaProjection initialized successfully")
            } else {
                Log.e(TAG, "Failed to get MediaProjection")
                stopSelf()
            }
        }
    }
    
    /**
     * Stops the media projection
     */
    private fun stopProjection() {
        handler?.post {
            mediaProjection?.stop()
        }
    }
    
    /**
     * Creates a virtual display for capturing the screen
     */
    private fun createVirtualDisplay() {
        // Create image reader
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )
        
        // Create virtual display
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            SCREENCAP_NAME,
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )
        
        // Set image available listener
        imageReader?.setOnImageAvailableListener(ImageAvailableListener(), handler)
    }
    
    /**
     * Takes a screenshot and returns the bitmap via callback
     * @param callback The callback to receive the bitmap
     */
    fun takeScreenshot(callback: (Bitmap?) -> Unit) {
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection not initialized")
            callback(null)
            return
        }
        
        try {
            // Create a new image reader for this specific screenshot
            val screenshotReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                1
            )
            
            // Create a temporary virtual display for this screenshot
            val screenshotDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenshotCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                screenshotReader.surface,
                null,
                handler
            )
            
            // Set up a one-time listener for the image
            screenshotReader.setOnImageAvailableListener({ reader ->
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
                        
                        // Crop bitmap if needed
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
                    Log.e(TAG, "Error capturing screenshot: ${e.message}")
                    bitmap = null
                } finally {
                    // Clean up resources
                    image?.close()
                    screenshotDisplay?.release()
                    screenshotReader.close()
                    
                    // Return the bitmap via callback
                    handler?.post {
                        callback(bitmap)
                    }
                }
            }, handler)
            
            // Add a timeout to prevent hanging
            handler?.postDelayed({
                try {
                    screenshotDisplay?.release()
                    screenshotReader.close()
                    callback(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in timeout handler: ${e.message}")
                }
            }, 3000) // 3 second timeout
            
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot: ${e.message}")
            callback(null)
        }
    }
    
    /**
     * Saves a bitmap to a file
     * @param bitmap The bitmap to save
     * @return The file where the bitmap was saved, or null if saving failed
     */
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
    
    /**
     * Checks if the media projection is ready
     * @return True if the media projection is ready, false otherwise
     */
    fun isMediaProjectionReady(): Boolean {
        return mediaProjection != null
    }
}
