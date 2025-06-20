package com.google.ai.sample

import android.app.Activity // Make sure this import is present
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri // Added for broadcasting URI
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.sample.feature.multimodal.dtos.ContentDto
import com.google.ai.sample.feature.multimodal.dtos.toSdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenCaptureService : Service() {
    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "ScreenCaptureChannel"
        private const val NOTIFICATION_ID = 2001
        private const val NOTIFICATION_ID_AI = NOTIFICATION_ID + 1 // Or any distinct ID
        const val ACTION_START_CAPTURE = "com.google.ai.sample.START_CAPTURE"
        const val ACTION_TAKE_SCREENSHOT = "com.google.ai.sample.TAKE_SCREENSHOT" // New action
        const val ACTION_STOP_CAPTURE = "com.google.ai.sample.STOP_CAPTURE"   // New action
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TAKE_SCREENSHOT_ON_START = "take_screenshot_on_start"

        // For triggering AI call execution in the service
        const val ACTION_EXECUTE_AI_CALL = "com.google.ai.sample.EXECUTE_AI_CALL"
        const val EXTRA_AI_INPUT_CONTENT_JSON = "com.google.ai.sample.EXTRA_AI_INPUT_CONTENT_JSON"
        const val EXTRA_AI_CHAT_HISTORY_JSON = "com.google.ai.sample.EXTRA_AI_CHAT_HISTORY_JSON"
        const val EXTRA_AI_MODEL_NAME = "com.google.ai.sample.EXTRA_AI_MODEL_NAME" // For service to create model
        const val EXTRA_AI_API_KEY = "com.google.ai.sample.EXTRA_AI_API_KEY"     // For service to create model


        // For broadcasting AI call results from the service
        const val ACTION_AI_CALL_RESULT = "com.google.ai.sample.AI_CALL_RESULT"
        const val EXTRA_AI_RESPONSE_TEXT = "com.google.ai.sample.EXTRA_AI_RESPONSE_TEXT"
        const val EXTRA_AI_ERROR_MESSAGE = "com.google.ai.sample.EXTRA_AI_ERROR_MESSAGE"

        private var instance: ScreenCaptureService? = null

        fun isRunning(): Boolean = instance != null && instance?.isReady == true
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isReady = false // Flag to indicate if MediaProjection is set up and active
    private val isScreenshotRequestedRef = java.util.concurrent.atomic.AtomicBoolean(false)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Callback for MediaProjection
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection session stopped externally (via callback). Cleaning up.")
            cleanup() // Perform full cleanup if projection stops unexpectedly
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "onCreate: Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}, isReady=$isReady, mediaProjectionIsNull=${mediaProjection==null}")

        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val notification = createNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                Log.d(TAG, "Service started in foreground for ACTION_START_CAPTURE.")

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                }

                Log.d(TAG, "onStartCommand (START_CAPTURE): resultCode=$resultCode, hasResultData=${resultData != null}")

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    val takeScreenshotFlag = intent.getBooleanExtra(EXTRA_TAKE_SCREENSHOT_ON_START, false)
                    startCapture(resultCode, resultData, takeScreenshotFlag)
                } else {
                    Log.e(TAG, "Invalid parameters for START_CAPTURE: resultCode=$resultCode (expected ${Activity.RESULT_OK}), resultDataIsNull=${resultData == null}")
                    cleanup() // Use cleanup to stop foreground and self
                }
            }
            ACTION_TAKE_SCREENSHOT -> {
                Log.d(TAG, "Received ACTION_TAKE_SCREENSHOT.")
                if (isReady && mediaProjection != null) {
                    takeScreenshot()
                } else {
                    Log.e(TAG, "Service not ready or MediaProjection not available for TAKE_SCREENSHOT. isReady=$isReady, mediaProjectionIsNull=${mediaProjection == null}")
                    Toast.makeText(this, "Screenshot service not ready. Please re-grant permission if necessary.", Toast.LENGTH_LONG).show()
                    // Optionally, broadcast a failure or request MainActivity to re-initiate.
                    // If not ready, and this action is called, it implies a logic error or race condition.
                    // MainActivity should ideally prevent calling this if service isn't running/ready.
                }
            }
            ACTION_STOP_CAPTURE -> {
                Log.d(TAG, "Received ACTION_STOP_CAPTURE. Cleaning up.")
                cleanup()
            }
            ACTION_EXECUTE_AI_CALL -> {
                Log.d(TAG, "ACTION_EXECUTE_AI_CALL: Ensuring foreground state for AI processing.")
                val aiNotification = createAiOperationNotification()
                // Comment: Attempt to start foreground for the AI call.
                // If the service is already in foreground (e.g., for screen capture), this updates the notification
                // or is a no-op depending on exact state. The goal is to elevate priority for the network call.
                // We will not explicitly call stopForeground() after the AI call in this handler to keep service
                // lifecycle management simple and rely on existing cleanup/stop mechanisms.
                // This might mean the "AI processing" notification persists if no other action stops/changes foreground state.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Using a generic type like DATA_SYNC or SPECIAL_USE if not mediaProjection related.
                    // However, to avoid permission issues if service was started for mediaProjection,
                    // sticking to mediaProjection type might be safer if it's already in that mode.
                    // For simplicity and if this call path doesn't define its own service type, we rely on the OS.
                    // Let's use a generic type if possible, but be mindful of existing foreground state.
                    // Re-evaluating: The service is already declared with mediaProjection.
                    // It's safer to re-assert this type or one compatible.
                    // Given this service *can* do media projection, reusing that type is safest.
                    startForeground(NOTIFICATION_ID_AI, aiNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(NOTIFICATION_ID_AI, aiNotification)
                }

                Log.d(TAG, "Received ACTION_EXECUTE_AI_CALL")
                // This service, already a Foreground Service for MediaProjection,
                // is now also responsible for executing AI calls to leverage foreground network priority.
                val inputContentJson = intent.getStringExtra(EXTRA_AI_INPUT_CONTENT_JSON)
                val chatHistoryJson = intent.getStringExtra(EXTRA_AI_CHAT_HISTORY_JSON)
                val modelName = intent.getStringExtra(EXTRA_AI_MODEL_NAME)
                val apiKey = intent.getStringExtra(EXTRA_AI_API_KEY)

                if (inputContentJson == null || chatHistoryJson == null || modelName == null || apiKey == null) {
                    Log.e(TAG, "Missing necessary data for AI call. inputContentJson: ${inputContentJson != null}, chatHistoryJson: ${chatHistoryJson != null}, modelName: ${modelName != null}, apiKey: ${apiKey != null}")
                    // Optionally broadcast an error back immediately
                    broadcastAiCallError("Missing parameters for AI call in service.")
                    return START_STICKY // Or START_NOT_STICKY if this is a fatal error for this call
                }

                serviceScope.launch {
                    var responseText: String? = null
                    var errorMessage: String? = null
                    try {
                        // Deserialize JSON to DTOs.
                        val chatHistoryDtos = Json.decodeFromString<List<ContentDto>>(chatHistoryJson)
                        val inputContentDto = Json.decodeFromString<ContentDto>(inputContentJson)

                        // Convert DTOs back to SDK types.
                        val chatHistory = chatHistoryDtos.map { it.toSdk() } // Uses ContentDto.toSdk()
                        val inputContent = inputContentDto.toSdk()           // Uses ContentDto.toSdk()

                        // Create a GenerativeModel instance for this specific call.
                        // This ensures the call uses the API key and model name provided by the ViewModel.
                        // Consider a default GenerationConfig or make it configurable too if needed.
                        val generativeModel = GenerativeModel(
                            modelName = modelName,
                            apiKey = apiKey
                            // generationConfig = generationConfig { ... } // Optional: add default config
                        )

                        // Start a new chat session with the provided history for this call.
                        val tempChat = generativeModel.startChat(history = chatHistory) // Use the mapped SDK history
                        Log.d(TAG, "Executing AI sendMessage with history size: ${chatHistory.size}")
                        val aiResponse = tempChat.sendMessage(inputContent) // Use the mapped SDK inputContent
                        responseText = aiResponse.text
                        Log.d(TAG, "AI call successful. Response text available: ${responseText != null}")

                    } catch (e: Exception) {
                        // Catching general exceptions from model/chat operations or serialization
                        Log.e(TAG, "Error during AI call execution in service", e)
                        errorMessage = e.localizedMessage ?: "Unknown error during AI call in service"
                        // More specific error handling (like API key failure leading to trying another key via ApiKeyManager)
                        // could be added here if this service becomes responsible for ApiKeyManager interactions.
                        // For "minimal changes", we just report the error back.
                    }

                    // Broadcast the result (success or error) back to the ViewModel.
                    val resultIntent = Intent(ACTION_AI_CALL_RESULT).apply {
                        `package` = applicationContext.packageName // Ensure only our app receives it
                        if (responseText != null) {
                            putExtra(EXTRA_AI_RESPONSE_TEXT, responseText)
                        }
                        if (errorMessage != null) {
                            putExtra(EXTRA_AI_ERROR_MESSAGE, errorMessage)
                        }
                    }
                    applicationContext.sendBroadcast(resultIntent)
                    Log.d(TAG, "Broadcast sent for AI_CALL_RESULT. Error: $errorMessage, Response: ${responseText != null}")
                }
                // START_STICKY is appropriate if the service is also managing MediaProjection independently.
                // If it becomes purely command-driven, START_NOT_STICKY might be considered after all commands processed.
                // For now, keep START_STICKY consistent with existing behavior.
                return START_STICKY
            }
            else -> {
                Log.w(TAG, "Unknown or null action received: ${intent?.action}.")
                // If service is started with unknown action and not ready, stop it.
                if (!isReady) {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun broadcastAiCallError(message: String) {
        val errorIntent = Intent(ACTION_AI_CALL_RESULT).apply {
            `package` = applicationContext.packageName
            putExtra(EXTRA_AI_ERROR_MESSAGE, message)
        }
        applicationContext.sendBroadcast(errorIntent)
        Log.d(TAG, "Broadcast error sent for AI_CALL_RESULT: $message")
    }

    private fun createAiOperationNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID) // Reuse existing channel
            .setContentTitle("Screen Operator")
            .setContentText("Processing AI request...")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Replace with a proper app icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false) // AI operation is not typically as long as screen capture
            .build()
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Capture Active")
            .setContentText("Ready to take screenshots")
            .setSmallIcon(android.R.drawable.ic_menu_camera) // Replace with a proper app icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for screen capture service"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    private fun startCapture(resultCode: Int, data: Intent, takeScreenshotOnStart: Boolean) {
        try {
            Log.d(TAG, "startCapture: Getting MediaProjection, takeScreenshotOnStart: $takeScreenshotOnStart")
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            mediaProjection?.unregisterCallback(mediaProjectionCallback) // Unregister old before stopping
            mediaProjection?.stop() // Stop any existing projection

            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection is null after getMediaProjection call")
                isReady = false
                cleanup() // Use cleanup to stop foreground and self
                return
            }
            mediaProjection?.registerCallback(mediaProjectionCallback, Handler(Looper.getMainLooper()))
            isReady = true
            Log.d(TAG, "MediaProjection ready.")

            if (takeScreenshotOnStart) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if(isReady && mediaProjection != null) {
                        Log.d(TAG, "startCapture: Taking initial screenshot after delay because takeScreenshotOnStart was true.")
                        takeScreenshot()
                    } else {
                        Log.w(TAG, "startCapture: Conditions to take initial screenshot not met after delay, even though takeScreenshotOnStart was true. isReady=$isReady, mediaProjectionIsNull=${mediaProjection==null}")
                    }
                }, 500)
            } else {
                Log.d(TAG, "startCapture: MediaProjection initialized, but skipping immediate screenshot as takeScreenshotOnStart is false.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in startCapture", e)
            isReady = false
            cleanup() // Use cleanup to stop foreground and self
        }
    }

private fun takeScreenshot() {
    if (!isReady || mediaProjection == null) {
        Log.e(TAG, "Cannot take screenshot - service not ready or mediaProjection is null. isReady=$isReady, mediaProjectionIsNull=${mediaProjection == null}")
        return
    }
    isScreenshotRequestedRef.set(true)
    Log.d(TAG, "takeScreenshot: Preparing to capture. isScreenshotRequestedRef set to true.")

    try {
        // Check if we need to initialize VirtualDisplay and ImageReader
        if (virtualDisplay == null || imageReader == null) {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val defaultDisplay = windowManager.defaultDisplay
                if (defaultDisplay != null) {
                    defaultDisplay.getRealMetrics(displayMetrics)
                } else {
                    val bounds = windowManager.currentWindowMetrics.bounds
                    displayMetrics.widthPixels = bounds.width()
                    displayMetrics.heightPixels = bounds.height()
                    displayMetrics.densityDpi = resources.displayMetrics.densityDpi
                }
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getMetrics(displayMetrics)
            }

            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            val density = displayMetrics.densityDpi

            if (width <= 0 || height <= 0) {
                Log.e(TAG, "Invalid display dimensions: ${width}x${height}. Cannot create ImageReader.")
                return
            }
            Log.d(TAG, "Display dimensions: ${width}x${height}, density: $density")

            imageReader?.close() // Close previous reader if any
            virtualDisplay?.release() // Release previous display if any

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
            val localImageReader = imageReader ?: run {
                Log.e(TAG, "ImageReader is null after creation attempt.")
                return
            }

            localImageReader.setOnImageAvailableListener({ reader ->
                if (isScreenshotRequestedRef.compareAndSet(true, false)) {
                    Log.d(TAG, "Screenshot request flag consumed, processing image.")
                    var image: android.media.Image? = null
                    try {
                        image = reader.acquireLatestImage()
                        if (image != null) {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * width

                            val bitmap = Bitmap.createBitmap(
                                width + rowPadding / pixelStride,
                                height,
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.copyPixelsFromBuffer(buffer)
                            Log.d(TAG, "Bitmap created, proceeding to save.")
                            saveScreenshot(bitmap)
                        } else {
                            Log.w(TAG, "acquireLatestImage returned null despite requested flag.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing image in listener", e)
                    } finally {
                        image?.close()
                        // Do NOT release VirtualDisplay or ImageReader here
                        // They will be reused for the next screenshot
                        Log.d(TAG, "Screenshot processed (or attempted), keeping resources for reuse.")
                    }
                } else {
                    // Logic to discard the frame if no screenshot was formally requested
                    Log.w(TAG, "OnImageAvailableListener invoked but no screenshot was requested or flag already consumed. Discarding frame.")
                    var imageToDiscard: android.media.Image? = null
                    try {
                        imageToDiscard = reader.acquireLatestImage()
                    } catch (e: Exception) {
                        // This catch is important because acquireLatestImage can fail if buffers are truly messed up
                        Log.e(TAG, "Error acquiring image to discard in OnImageAvailableListener else block", e)
                    } finally {
                        imageToDiscard?.close()
                    }
                }
            }, Handler(Looper.getMainLooper()))

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                localImageReader.surface,
                object : VirtualDisplay.Callback() {
                    override fun onPaused() { Log.d(TAG, "VirtualDisplay paused") }
                    override fun onResumed() { Log.d(TAG, "VirtualDisplay resumed") }
                    override fun onStopped() { Log.d(TAG, "VirtualDisplay stopped") }
                },
                Handler(Looper.getMainLooper())
            )

            if (virtualDisplay == null) {
                Log.e(TAG, "Failed to create VirtualDisplay.")
                localImageReader.close() // Clean up the reader we just created
                this.imageReader = null
                return
            }
            Log.d(TAG, "VirtualDisplay and ImageReader initialized for reuse.")
        } else {
            // Resources already exist, just trigger a new capture
            Log.d(TAG, "Using existing VirtualDisplay and ImageReader.")
            // Force the ImageReader to capture a new frame
            // The listener is already set up and will handle the new image
        }

    } catch (e: Exception) {
        Log.e(TAG, "Error in takeScreenshot setup", e)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }
}

    private fun saveScreenshot(bitmap: Bitmap) {
        try {
            val picturesDir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Screenshots")
            if (!picturesDir.exists()) {
                picturesDir.mkdirs()
            }

            // List existing screenshot files
            val screenshotFiles = picturesDir.listFiles { _, name ->
                name.startsWith("screenshot_") && name.endsWith(".png")
            }?.toMutableList() ?: mutableListOf()

            // Sort files by name (timestamp) to find the oldest
            screenshotFiles.sortBy { it.name }

            // If count is 100 or more, delete oldest ones until count is 99
            // This makes space for the new screenshot, keeping the total at a max of 100
            val maxScreenshots = 100
            var screenshotsToDelete = screenshotFiles.size - (maxScreenshots -1) // Number of files to delete to make space for the new one

            if (screenshotsToDelete > 0) {
                Log.i(TAG, "Max screenshots reached. Current count: ${screenshotFiles.size}. Attempting to delete $screenshotsToDelete oldest screenshot(s).")
                for (i in 0 until screenshotsToDelete) {
                    if (i < screenshotFiles.size) {
                        val oldestFile = screenshotFiles[i]
                        if (oldestFile.delete()) {
                            Log.i(TAG, "Deleted oldest screenshot: ${oldestFile.absolutePath}")
                        } else {
                            Log.e(TAG, "Failed to delete oldest screenshot: ${oldestFile.absolutePath}")
                        }
                    }
                }
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(picturesDir, "screenshot_$timestamp.png")

            val outputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.flush()
            outputStream.close()

            Log.i(TAG, "Screenshot saved to: ${file.absolutePath}")

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    applicationContext,
                    "Screenshot saved to: Android/data/com.google.ai.sample/files/Pictures/Screenshots/",
                    Toast.LENGTH_LONG
                ).show()
            }

            val screenshotUri = Uri.fromFile(file)
            val intent = Intent(MainActivity.ACTION_MEDIAPROJECTION_SCREENSHOT_CAPTURED).apply {
                putExtra(MainActivity.EXTRA_SCREENSHOT_URI, screenshotUri.toString())
                `package` = applicationContext.packageName
            }
            applicationContext.sendBroadcast(intent)
            Log.d(TAG, "Sent broadcast ACTION_MEDIAPROJECTION_SCREENSHOT_CAPTURED with URI: $screenshotUri")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "Failed to save screenshot: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun cleanup() {
        Log.d(TAG, "cleanup() called. Cleaning up all MediaProjection resources.")
        try {
            isReady = false
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null

            mediaProjection?.unregisterCallback(mediaProjectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error during full cleanup", e)
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf() // This will trigger onDestroy eventually
            instance = null // Clear static instance
            Log.d(TAG, "Full cleanup finished, service fully stopped.")
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Service being destroyed")
        // Cleanup is called from ACTION_STOP_CAPTURE or if projection stops externally.
        // If service is killed by system, this ensures cleanup too.
        if (isReady || mediaProjection != null) { // Check if cleanup is actually needed
           cleanup()
        }
        serviceScope.cancel() // Cancel all coroutines in this scope
        instance = null // Ensure instance is cleared
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
