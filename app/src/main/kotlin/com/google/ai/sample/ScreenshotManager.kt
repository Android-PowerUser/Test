package com.google.ai.sample

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
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
    private val handler = Handler(Looper.getMainLooper())
    
    // Service connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ScreenshotService.LocalBinder
            if (binder != null) {
                screenshotService = binder.getService()
                isBound = true
                Log.d(TAG, "Service connected")
                
                // If there's a pending screenshot request, execute it now
                pendingScreenshotCallback?.let { callback ->
                    Log.d(TAG, "Executing pending screenshot request")
                    takeScreenshot(callback)
                    pendingScreenshotCallback = null
                }
            } else {
                Log.e(TAG, "Service binder is null")
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
        
        try {
            // Stop any existing service first
            stopScreenshotService()
            
            // Start the service with the new permission data
            val serviceIntent = ScreenshotService.getStartIntent(context, resultCode, data)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            Log.d(TAG, "Started screenshot service")
            
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
     * Stop the screenshot service
     */
    private fun stopScreenshotService() {
        try {
            // Unbind first
            unbindService()
            
            // Then stop the service
            val serviceIntent = ScreenshotService.getStopIntent(context)
            context.startService(serviceIntent)
            Log.d(TAG, "Stop service request sent")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service: ${e.message}")
        }
    }
    
    /**
     * Take a screenshot
     * @param callback Callback function that will be called with the screenshot bitmap
     */
    fun takeScreenshot(callback: (Bitmap?) -> Unit) {
        Log.d(TAG, "takeScreenshot called, isBound=$isBound")
        
        // For Android 14+, we need to check if we need to request new permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // If the service is not ready, we need to request new permission
            if (isBound && screenshotService?.isMediaProjectionReady() != true) {
                Log.d(TAG, "Android 14+ requires new permission for each screenshot session")
                callback(null)
                return
            }
        }
        
        if (isBound && screenshotService != null) {
            // Check if MediaProjection is ready
            if (screenshotService?.isMediaProjectionReady() == true) {
                Log.d(TAG, "Taking screenshot via service")
                try {
                    screenshotService?.takeScreenshot(callback)
                } catch (e: Exception) {
                    Log.e(TAG, "Error taking screenshot: ${e.message}")
                    handler.post { callback(null) }
                }
            } else {
                Log.e(TAG, "MediaProjection not ready")
                handler.post { callback(null) }
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
                handler.post { callback(null) }
            }
        }
    }
    
    /**
     * Save bitmap to file
     * @param bitmap The bitmap to save
     * @return The file where the bitmap was saved, or null if saving failed
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
        stopScreenshotService()
        
        // Clear references
        screenshotService = null
    }
}
