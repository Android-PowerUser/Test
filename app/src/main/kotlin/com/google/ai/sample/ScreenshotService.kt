/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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

/**
 * Foreground service for handling screenshot capture using MediaProjection
 */
class ScreenshotService : Service() {
    companion object {
        private const val TAG = "ScreenshotService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screenshot_channel"
        private const val CHANNEL_NAME = "Screenshot Service"
        
        // Intent actions
        const val ACTION_START = "com.google.ai.sample.action.START_SCREENSHOT"
        const val ACTION_STOP = "com.google.ai.sample.action.STOP_SCREENSHOT"
        
        // Intent extras
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
    }
    
    // Binder for local service binding
    private val binder = LocalBinder()
    
    // MediaProjection related variables
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenDensity: Int = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private val handler = Handler(Looper.getMainLooper())
    
    // Callback for screenshot capture
    private var screenshotCallback: ((Bitmap?) -> Unit)? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): ScreenshotService = this@ScreenshotService
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Get screen metrics
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        screenDensity = metrics.densityDpi
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        
        // Create notification channel for foreground service
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                
                if (resultCode != -1 && data != null) {
                    startForeground(NOTIFICATION_ID, createNotification())
                    initializeMediaProjection(resultCode, data)
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
    
    /**
     * Create notification channel for foreground service
     */
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
    
    /**
     * Create notification for foreground service
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screenshot Service")
            .setContentText("Capturing screen content")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    /**
     * Initialize MediaProjection with permission result
     */
    private fun initializeMediaProjection(resultCode: Int, data: Intent) {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
    }
    
    /**
     * Take a screenshot
     * @param callback Callback function that will be called with the screenshot bitmap
     */
    fun takeScreenshot(callback: (Bitmap?) -> Unit) {
        screenshotCallback = callback
        
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection not initialized")
            callback(null)
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
    
    /**
     * Save bitmap to file
     * @param bitmap The bitmap to save
     * @return The file containing the saved bitmap, or null if saving failed
     */
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
    
    /**
     * Clean up virtual display resources
     */
    private fun tearDownVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        
        imageReader?.close()
        imageReader = null
    }
    
    /**
     * Clean up all resources
     */
    private fun tearDown() {
        tearDownVirtualDisplay()
        mediaProjection?.stop()
        mediaProjection = null
    }
}
