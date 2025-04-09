package com.google.ai.sample;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import com.google.ai.sample.screenshot.ScreenCaptureService;

import java.io.File;

/**
 * Bridge class to connect Kotlin code with Java screenshot implementation
 */
public class ScreenshotBridge {
    private static final String TAG = "ScreenshotBridge";
    public static final int REQUEST_MEDIA_PROJECTION = 1001;
    
    private final Context context;
    private ScreenCaptureService screenshotService;
    private boolean isBound = false;
    
    // Singleton instance
    private static ScreenshotBridge INSTANCE;
    
    // Service connection
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ScreenCaptureService.LocalBinder binder = (ScreenCaptureService.LocalBinder) service;
            screenshotService = binder.getService();
            isBound = true;
            Log.d(TAG, "Service connected");
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            screenshotService = null;
            isBound = false;
            Log.d(TAG, "Service disconnected");
        }
    };
    
    /**
     * Get the singleton instance of ScreenshotBridge
     * @param context Application context
     * @return ScreenshotBridge instance
     */
    public static synchronized ScreenshotBridge getInstance(Context context) {
        if (INSTANCE == null) {
            INSTANCE = new ScreenshotBridge(context.getApplicationContext());
        }
        return INSTANCE;
    }
    
    /**
     * Private constructor
     * @param context Application context
     */
    private ScreenshotBridge(Context context) {
        this.context = context;
    }
    
    /**
     * Request permission to capture screen
     * @param activity The activity to request permission from
     */
    public void requestScreenshotPermission(Activity activity) {
        try {
            android.media.projection.MediaProjectionManager mediaProjectionManager = 
                    (android.media.projection.MediaProjectionManager) activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            activity.startActivityForResult(
                    mediaProjectionManager.createScreenCaptureIntent(),
                    REQUEST_MEDIA_PROJECTION
            );
        } catch (Exception e) {
            Log.e(TAG, "Error requesting screenshot permission: " + e.getMessage());
            Toast.makeText(context, "Failed to request screenshot permission", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Handle the result of the permission request
     * @param resultCode The result code from onActivityResult
     * @param data The intent data from onActivityResult
     * @return true if permission was granted, false otherwise
     */
    public boolean handlePermissionResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "User denied screen sharing permission");
            return false;
        }
        
        try {
            // Stop any existing service first
            stopScreenshotService();
            
            // Start the service with the new permission data
            Intent serviceIntent = ScreenCaptureService.getStartIntent(context, resultCode, data);
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            
            Log.d(TAG, "Started screenshot service");
            
            // Bind to the service
            bindService();
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error handling permission result: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Bind to the screenshot service
     */
    private void bindService() {
        if (!isBound) {
            try {
                Log.d(TAG, "Binding to service");
                Intent intent = new Intent(context, ScreenCaptureService.class);
                boolean result = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
                Log.d(TAG, "Bind service result: " + result);
                
                if (!result) {
                    Log.e(TAG, "Failed to bind to service");
                    Toast.makeText(context, "Failed to connect to screenshot service", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error binding to service: " + e.getMessage());
            }
        } else {
            Log.d(TAG, "Service already bound");
        }
    }
    
    /**
     * Unbind from the screenshot service
     */
    private void unbindService() {
        if (isBound) {
            try {
                context.unbindService(serviceConnection);
                isBound = false;
                Log.d(TAG, "Service unbound");
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding service: " + e.getMessage());
            }
        }
    }
    
    /**
     * Stop the screenshot service
     */
    private void stopScreenshotService() {
        try {
            // Unbind first
            unbindService();
            
            // Then stop the service
            Intent serviceIntent = ScreenCaptureService.getStopIntent(context);
            context.startService(serviceIntent);
            Log.d(TAG, "Stop service request sent");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping service: " + e.getMessage());
        }
    }
    
    /**
     * Take a screenshot
     * @param callback Callback function that will be called with the screenshot bitmap
     */
    public void takeScreenshot(final ScreenshotCallback callback) {
        Log.d(TAG, "takeScreenshot called, isBound=" + isBound);
        
        // For Android 14+, we need to check if we need to request new permission
        if (android.os.Build.VERSION.SDK_INT >= 34) { // Android 14 is API 34
            // If the service is not ready, we need to request new permission
            if (isBound && (screenshotService == null || !screenshotService.isMediaProjectionReady())) {
                Log.d(TAG, "Android 14+ requires new permission for each screenshot session");
                callback.onScreenshotTaken(null);
                return;
            }
        }
        
        if (isBound && screenshotService != null) {
            // Check if MediaProjection is ready
            if (screenshotService.isMediaProjectionReady()) {
                Log.d(TAG, "Taking screenshot via service");
                try {
                    screenshotService.takeScreenshot(new ScreenCaptureService.ScreenshotCallback() {
                        @Override
                        public void onScreenshotTaken(Bitmap bitmap) {
                            callback.onScreenshotTaken(bitmap);
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error taking screenshot: " + e.getMessage());
                    callback.onScreenshotTaken(null);
                }
            } else {
                Log.e(TAG, "MediaProjection not ready");
                callback.onScreenshotTaken(null);
            }
        } else {
            Log.e(TAG, "Service not bound or null");
            callback.onScreenshotTaken(null);
        }
    }
    
    /**
     * Save bitmap to file
     * @param bitmap The bitmap to save
     * @return The file where the bitmap was saved, or null if saving failed
     */
    public File saveBitmapToFile(Bitmap bitmap) {
        if (isBound && screenshotService != null) {
            try {
                return screenshotService.saveBitmapToFile(bitmap);
            } catch (Exception e) {
                Log.e(TAG, "Error saving bitmap: " + e.getMessage());
                return null;
            }
        } else {
            Log.e(TAG, "Service not bound, cannot save bitmap");
            return null;
        }
    }
    
    /**
     * Release all resources
     */
    public void release() {
        stopScreenshotService();
        INSTANCE = null;
    }
    
    /**
     * Interface for screenshot callback
     */
    public interface ScreenshotCallback {
        void onScreenshotTaken(Bitmap bitmap);
    }
}
