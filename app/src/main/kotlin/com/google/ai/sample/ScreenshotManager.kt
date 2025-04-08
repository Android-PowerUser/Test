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

import android.app.Activity
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
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Manager class for handling screenshot functionality using MediaProjection
 */
class ScreenshotManager(private val context: Context) {
    companion object {
        private const val TAG = "ScreenshotManager"
        const val REQUEST_MEDIA_PROJECTION = 1001
        
        // Singleton instance
        @Volatile
        private var INSTANCE: ScreenshotManager? = null
        
        fun getInstance(context: Context): ScreenshotManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ScreenshotManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenDensity: Int = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenshotCallback: ((Bitmap?) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    
    init {
        // Get screen metrics
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        screenDensity = metrics.densityDpi
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }
    
    /**
     * Request permission to capture screen
     * @param activity The activity to request permission from
     */
    fun requestScreenshotPermission(activity: Activity) {
        val mediaProjectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        activity.startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }
    
    /**
     * Handle the result of the permission request
     * @param resultCode The result code from onActivityResult
     * @param data The intent data from onActivityResult
     * @return true if permission was granted, false otherwise
     */
    fun handlePermissionResult(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "User denied screen sharing permission")
            return false
        }
        
        val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        return true
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
                tearDown()
                screenshotCallback?.invoke(bitmap)
            }
        }, handler)
        
        // Add a delay to ensure the virtual display is set up
        handler.postDelayed({
            if (imageReader?.surface == null) {
                Log.e(TAG, "Surface is null")
                tearDown()
                callback(null)
            }
        }, 100)
    }
    
    /**
     * Clean up resources
     */
    private fun tearDown() {
        virtualDisplay?.release()
        virtualDisplay = null
        
        imageReader?.close()
        imageReader = null
    }
    
    /**
     * Release all resources
     */
    fun release() {
        tearDown()
        mediaProjection?.stop()
        mediaProjection = null
    }
    
    /**
     * Save bitmap to file
     * @param bitmap The bitmap to save
     * @return The URI of the saved file, or null if saving failed
     */
    fun saveBitmapToFile(bitmap: Bitmap): File? {
        val fileName = "screenshot_${UUID.randomUUID()}.png"
        val file = File(context.cacheDir, fileName)
        
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
}
