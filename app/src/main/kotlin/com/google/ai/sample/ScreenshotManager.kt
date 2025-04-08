package com.google.ai.sample

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File

class ScreenshotManager(private val context: Context) {
    companion object {
        private const val TAG = "ScreenshotManager"
        const val REQUEST_MEDIA_PROJECTION = 1001
        private const val MAX_RETRY_COUNT = 10
        private const val RETRY_DELAY_MS = 500L
        
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
    private val handler = Handler(Looper.getMainLooper())
    
    // Service connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScreenshotService.LocalBinder
            screenshotService = binder.getService()
            isBound = true
            Log.d(TAG, "Service connected")
            
            // If there's a pending screenshot request, execute it now
            pendingScreenshotCallback?.let { callback ->
                Log.d(TAG, "Executing pending screenshot request")
                takeScreenshotWithRetry(callback, 0)
                pendingScreenshotCallback = null
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            screenshotService = null
            isBound = false
            Log.d(TAG, "Service disconnected")
        }
    }
    
    /**
     * Request permission to capture screen
     * @param activity The activity to request permission from
     */
    fun requestScreenshotPermission(activity: Activity) {
        try {
            val mediaProjectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            activity.startActivityForResult(
                mediaProjectionManager.createScreenCaptureIntent(),
                REQUEST_MEDIA_PROJECTION
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting screenshot permission: ${e.message}")
            Toast.makeText(context, "Failed to request screenshot permission", Toast.LENGTH_SHORT).show()
        }
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
        
        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "Error handling permission result: ${e.message}")
            return false
        }
    }
    
    /**
     * Bind to the screenshot service
     */
    private fun bindService() {
        if (!isBound) {
            try {
                Log.d(TAG, "Binding to service")
                val intent = Intent(context, ScreenshotService::class.java)
                val result = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                Log.d(TAG, "Bind service result: $result")
                
                if (!result) {
                    Log.e(TAG, "Failed to bind to service")
                    Toast.makeText(context, "Failed to connect to screenshot service", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error binding to service: ${e.message}")
            }
        } else {
            Log.d(TAG, "Service already bound")
        }
    }
    
    /**
     * Unbind from the screenshot service
     */
    private fun unbindService() {
        if (isBound) {
            try {
                context.unbindService(serviceConnection)
                isBound = false
                Log.d(TAG, "Service unbound")
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding service: ${e.message}")
            }
        }
    }
    
    /**
     * Take a screenshot with retry mechanism
     * @param callback Callback function that will be called with the screenshot bitmap
     * @param retryCount Current retry count
     */
    private fun takeScreenshotWithRetry(callback: (Bitmap?) -> Unit, retryCount: Int) {
        // Create a wrapper callback that always runs on the main thread
        val mainThreadCallback: (Bitmap?) -> Unit = { bitmap ->
            handler.post {
                callback(bitmap)
            }
        }
        
        if (isBound && screenshotService != null) {
            // Check if MediaProjection is ready
            if (screenshotService?.isMediaProjectionReady() == true) {
                Log.d(TAG, "Taking screenshot via service")
                try {
                    screenshotService?.takeScreenshot(mainThreadCallback)
                } catch (e: Exception) {
                    Log.e(TAG, "Error taking screenshot: ${e.message}")
                    mainThreadCallback(null)
                }
            } else if (retryCount < MAX_RETRY_COUNT) {
                // Retry after delay
                Log.d(TAG, "MediaProjection not ready, retrying in ${RETRY_DELAY_MS}ms (attempt ${retryCount + 1}/${MAX_RETRY_COUNT})")
                handler.postDelayed({
                    takeScreenshotWithRetry(callback, retryCount + 1)
                }, RETRY_DELAY_MS)
            } else {
                // Max retries reached
                Log.e(TAG, "Max retries reached, MediaProjection still not initialized")
                Toast.makeText(context, "Failed to initialize screenshot service", Toast.LENGTH_SHORT).show()
                mainThreadCallback(null)
            }
        } else {
            // Store the callback and execute it when the service is connected
            Log.d(TAG, "Service not bound, storing callback and binding")
            pendingScreenshotCallback = callback
            
            // Try to bind to the service if not already bound
            if (!isBound) {
                bindService()
            } else {
                // If binding failed or service is null, return null
                Log.e(TAG, "Service is bound but null, cannot take screenshot")
                mainThreadCallback(null)
            }
        }
    }
    
    /**
     * Take a screenshot
     * @param callback Callback function that will be called with the screenshot bitmap
     */
    fun takeScreenshot(callback: (Bitmap?) -> Unit) {
        Log.d(TAG, "takeScreenshot called, isBound=$isBound")
        takeScreenshotWithRetry(callback, 0)
    }
    
    /**
     * Save bitmap to file
     * @param bitmap The bitmap to save
     * @return The URI of the saved file, or null if saving failed
     */
    fun saveBitmapToFile(bitmap: Bitmap): File? {
        return if (isBound && screenshotService != null) {
            try {
                screenshotService?.saveBitmapToFile(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving bitmap: ${e.message}")
                null
            }
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
        try {
            val serviceIntent = Intent(context, ScreenshotService::class.java).apply {
                action = ScreenshotService.ACTION_STOP
            }
            context.startService(serviceIntent)
            Log.d(TAG, "Stop service request sent")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service: ${e.message}")
        }
        
        // Unbind from the service
        unbindService()
        
        // Clear references
        screenshotService = null
        resultData = null
    }
}
