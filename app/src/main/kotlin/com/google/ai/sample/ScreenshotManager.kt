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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import java.io.File

/**
 * Manager class for handling screenshot functionality using MediaProjection via a foreground service
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
    
    private var screenshotService: ScreenshotService? = null
    private var isBound = false
    private var pendingScreenshotCallback: ((Bitmap?) -> Unit)? = null
    private var resultCode: Int = Activity.RESULT_CANCELED
    private var resultData: Intent? = null
    
    // Service connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScreenshotService.LocalBinder
            screenshotService = binder.getService()
            isBound = true
            
            // If there's a pending screenshot request, execute it now
            pendingScreenshotCallback?.let { callback ->
                takeScreenshot(callback)
                pendingScreenshotCallback = null
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            screenshotService = null
            isBound = false
        }
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
        
        this.resultCode = resultCode
        this.resultData = data
        
        // Start the foreground service
        val serviceIntent = Intent(context, ScreenshotService::class.java).apply {
            action = ScreenshotService.ACTION_START
            putExtra(ScreenshotService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenshotService.EXTRA_DATA, data)
        }
        context.startForegroundService(serviceIntent)
        
        // Bind to the service
        bindService()
        
        return true
    }
    
    /**
     * Bind to the screenshot service
     */
    private fun bindService() {
        if (!isBound) {
            val intent = Intent(context, ScreenshotService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }
    
    /**
     * Unbind from the screenshot service
     */
    private fun unbindService() {
        if (isBound) {
            context.unbindService(serviceConnection)
            isBound = false
        }
    }
    
    /**
     * Take a screenshot
     * @param callback Callback function that will be called with the screenshot bitmap
     */
    fun takeScreenshot(callback: (Bitmap?) -> Unit) {
        if (isBound && screenshotService != null) {
            screenshotService?.takeScreenshot(callback)
        } else {
            // Store the callback and execute it when the service is connected
            pendingScreenshotCallback = callback
            
            // Try to bind to the service if not already bound
            if (!isBound) {
                bindService()
            }
        }
    }
    
    /**
     * Save bitmap to file
     * @param bitmap The bitmap to save
     * @return The URI of the saved file, or null if saving failed
     */
    fun saveBitmapToFile(bitmap: Bitmap): File? {
        return if (isBound && screenshotService != null) {
            screenshotService?.saveBitmapToFile(bitmap)
        } else {
            Log.e(TAG, "Service not bound, cannot save bitmap")
            null
        }
    }
    
    /**
     * Release all resources
     */
    fun release() {
        // Stop the service
        val serviceIntent = Intent(context, ScreenshotService::class.java).apply {
            action = ScreenshotService.ACTION_STOP
        }
        context.startService(serviceIntent)
        
        // Unbind from the service
        unbindService()
        
        // Clear references
        screenshotService = null
        resultData = null
    }
}
