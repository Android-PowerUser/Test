import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.MediaActionSound
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import java.io.File

class ScreenOperatorAccessibilityService : AccessibilityService() {
    private val TAG = "ScreenOperatorService"
    
    companion object {
        // Flag to indicate if a screenshot should be taken
        var shouldTakeScreenshot = false
        
        // Callback to be executed after screenshot is taken
        var onScreenshotTaken: (() -> Unit)? = null
        
        // Method to trigger screenshot from outside the service
        fun takeScreenshot(callback: () -> Unit) {
            shouldTakeScreenshot = true
            onScreenshotTaken = callback
        }
        
        // Get the most recent screenshot from the Screenshots directory
        fun getLatestScreenshot(): File? {
            val screenshotsDir = File("/sdcard/Pictures/Screenshots")
            if (!screenshotsDir.exists() || !screenshotsDir.isDirectory) {
                Log.e("ScreenOperatorService", "Screenshots directory does not exist")
                return null
            }
            
            return screenshotsDir.listFiles()?.maxByOrNull { it.lastModified() }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Handle accessibility events here
        Log.d(TAG, "Received accessibility event: ${event.eventType}")
    }

    override fun onInterrupt() {
        // Handle interruption of the accessibility service
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Service is connected, perform any initial setup here
        Log.d(TAG, "Accessibility service connected")
    }
    
    // Method to take a screenshot using the global action
    fun performScreenshot() {
        Log.d(TAG, "Taking screenshot...")
        
        // Play the camera shutter sound
        val sound = MediaActionSound()
        sound.play(MediaActionSound.SHUTTER_CLICK)
        
        // Perform the screenshot action
        performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        
        // Wait a moment for the screenshot to be saved
        Handler(Looper.getMainLooper()).postDelayed({
            // Reset the flag
            shouldTakeScreenshot = false
            
            // Execute the callback
            onScreenshotTaken?.invoke()
            onScreenshotTaken = null
            
            // Broadcast that a screenshot was taken to refresh media scanner
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            getLatestScreenshot()?.let { file ->
                Log.d(TAG, "Screenshot taken: ${file.absolutePath}")
            }
        }, 1000) // Wait 1 second for the screenshot to be saved
    }
}
