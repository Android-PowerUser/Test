package io.github.Android-PowerUser

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import android.app.Application
import android.util.Log

/**
 * Application class for maintaining application-wide state and resources
 */
class PhotoReasoningApplication : Application() {
    
    companion object {
        private const val TAG = "PhotoReasoningApp"
        
        // Application-wide CoroutineScope that is not tied to any lifecycle
        // This scope will continue to run even when the app is in the background
        val applicationScope = CoroutineScope(
            SupervisorJob() + 
            Dispatchers.Default + 
            CoroutineExceptionHandler { _, throwable ->
                Log.e(TAG, "Uncaught exception in application scope: ${throwable.message}", throwable)
            }
        )
        
        // Instance of the application for global access
        private lateinit var instance: PhotoReasoningApplication
        
        fun getInstance(): PhotoReasoningApplication {
            return instance
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Application created")
    }
}
