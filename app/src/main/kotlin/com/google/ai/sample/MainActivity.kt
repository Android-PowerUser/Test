package com.google.ai.sample

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.ai.sample.feature.chat.ChatRoute
import com.google.ai.sample.feature.multimodal.PhotoReasoningRoute
import com.google.ai.sample.feature.text.SummarizeRoute
import com.google.ai.sample.ui.theme.GenerativeAISample

class MainActivity : ComponentActivity() {

    // PhotoReasoningViewModel instance
    private var photoReasoningViewModel: com.google.ai.sample.feature.multimodal.PhotoReasoningViewModel? = null
    
    // Function to get the PhotoReasoningViewModel
    fun getPhotoReasoningViewModel(): com.google.ai.sample.feature.multimodal.PhotoReasoningViewModel? {
        Log.d(TAG, "getPhotoReasoningViewModel called, returning: ${photoReasoningViewModel != null}")
        return photoReasoningViewModel
    }
    
    // Function to set the PhotoReasoningViewModel
    fun setPhotoReasoningViewModel(viewModel: com.google.ai.sample.feature.multimodal.PhotoReasoningViewModel) {
        Log.d(TAG, "setPhotoReasoningViewModel called with viewModel: $viewModel")
        photoReasoningViewModel = viewModel
    }

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.d(TAG, "All permissions granted")
            Toast.makeText(this, "Alle Berechtigungen erteilt", Toast.LENGTH_SHORT).show()
        } else {
            Log.d(TAG, "Some permissions denied")
            Toast.makeText(this, "Einige Berechtigungen wurden verweigert. Die App benötigt Zugriff auf Medien, um Screenshots zu verarbeiten.", Toast.LENGTH_LONG).show()
            
            // If MANAGE_EXTERNAL_STORAGE is needed (for Android 11+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requestManageExternalStoragePermission()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set the instance immediately when created
        instance = this
        Log.d(TAG, "onCreate: Setting MainActivity instance")

        // Check and request permissions
        checkAndRequestPermissions()
        
        // Check if accessibility service is enabled
        checkAccessibilityServiceEnabled()
        
        // Check accessibility service status periodically
        checkServiceAvailability()

        setContent {
            GenerativeAISample {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(navController = navController, startDestination = "menu") {
                        composable("menu") {
                            MenuScreen(onItemClicked = { routeId ->
                                navController.navigate(routeId)
                            })
                        }
                        composable("summarize") {
                            SummarizeRoute()
                        }
                        composable("photo_reasoning") {
                            PhotoReasoningRoute()
                        }
                        composable("chat") {
                            ChatRoute()
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Check which permissions we need to request
        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }

        // Request permissions if needed
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d(TAG, "All permissions already granted")
            
            // If MANAGE_EXTERNAL_STORAGE is needed (for Android 11+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requestManageExternalStoragePermission()
            }
        }
    }

    private fun requestManageExternalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                    Toast.makeText(this, "Bitte erteilen Sie Zugriff auf alle Dateien", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                    Toast.makeText(this, "Bitte erteilen Sie Zugriff auf alle Dateien", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    // Check if accessibility service is enabled and prompt user to enable it if not
    private fun checkAccessibilityServiceEnabled() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        if (!accessibilityEnabled) {
            // Show dialog to prompt user to enable the service
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Accessibility Service Required")
                .setMessage("Screen Operator requires accessibility service to be enabled to perform click operations. Would you like to enable it now?")
                .setPositiveButton("Enable") { _, _ ->
                    // Open accessibility settings
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                    Toast.makeText(this, "Please enable Screen Operator in the Accessibility Services list", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                    Toast.makeText(this, "Click functionality will not work without accessibility service enabled", Toast.LENGTH_LONG).show()
                }
                .setCancelable(false)
                .show()
        }
    }

    // Helper function to check if the accessibility service is enabled
    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val serviceName = packageName + "/" + ScreenOperatorAccessibilityService::class.java.canonicalName
        return enabledServices?.contains(serviceName) == true
    }
    
    // Check if all required permissions are granted
    fun areAllPermissionsGranted(): Boolean {
        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        
        // For Android 11+, check MANAGE_EXTERNAL_STORAGE separately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        
        return true
    }
    
    // Periodically check if the accessibility service is available
    private fun checkServiceAvailability() {
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                val isAvailable = ScreenOperatorAccessibilityService.isServiceAvailable()
                if (isAvailable) {
                    Log.d(TAG, "Accessibility service is available")
                    // Update UI to indicate service is available
                    updateServiceStatusUI(true)
                } else {
                    Log.d(TAG, "Accessibility service is not available")
                    // Update UI to indicate service is not available
                    updateServiceStatusUI(false)
                    // Schedule next check
                    handler.postDelayed(this, 2000)
                }
            }
        }
        
        // Start checking
        handler.post(runnable)
    }

    // Update UI to show service status
    fun updateServiceStatusUI(isAvailable: Boolean) {
        // Update UI to show service status
        runOnUiThread {
            val statusMessage = if (isAvailable) 
                "Screen Operator service is active" 
            else 
                "Screen Operator service is not active"
            
            Toast.makeText(this, statusMessage, Toast.LENGTH_SHORT).show()
        }
    }
    
    // Update UI with status message
    fun updateStatusMessage(message: String, isError: Boolean) {
        // Update UI with status message
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        
        // Static instance of MainActivity for accessibility service access
        @Volatile
        private var instance: MainActivity? = null
        
        // Method to get the MainActivity instance
        fun getInstance(): MainActivity? {
            Log.d(TAG, "getInstance called, returning: ${instance != null}")
            return instance
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Set the instance when activity is resumed
        instance = this
        Log.d(TAG, "onResume: Setting MainActivity instance")
    }
    
    // Do not clear instance in onPause
    override fun onPause() {
        super.onPause()
        // Do not clear the instance when activity is paused
        Log.d(TAG, "onPause: Keeping MainActivity instance")
    }
    
    // Only clear instance in onDestroy if isFinishing is true
    override fun onDestroy() {
        super.onDestroy()
        // Only clear the instance when activity is truly being destroyed (not during configuration changes)
        if (isFinishing && instance == this) {
            Log.d(TAG, "onDestroy: Clearing MainActivity instance (app is finishing)")
            instance = null
        } else {
            Log.d(TAG, "onDestroy: Keeping MainActivity instance (configuration change)")
        }
    }
}
