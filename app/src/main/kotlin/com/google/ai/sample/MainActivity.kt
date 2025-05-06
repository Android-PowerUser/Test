package com.google.ai.sample

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.ai.sample.feature.multimodal.PhotoReasoningRoute
import com.google.ai.sample.ui.theme.GenerativeAISample

class MainActivity : ComponentActivity() {

    // PhotoReasoningViewModel instance
    private var photoReasoningViewModel: com.google.ai.sample.feature.multimodal.PhotoReasoningViewModel? = null
    
    // API Key Manager
    private lateinit var apiKeyManager: ApiKeyManager
    
    // Show API Key Dialog state
    private var showApiKeyDialog by mutableStateOf(false)
    
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

        // Initialize API Key Manager
        apiKeyManager = ApiKeyManager.getInstance(this)
        
        // Check if API key exists, if not, show dialog
        val apiKey = apiKeyManager.getCurrentApiKey()
        if (apiKey.isNullOrEmpty()) {
            showApiKeyDialog = true
            Log.d(TAG, "No API key found, showing dialog")
        } else {
            Log.d(TAG, "API key found: ${apiKey.take(5)}...")
        }

        // Check and request permissions
        checkAndRequestPermissions()
        
        // Check if accessibility service is enabled
        checkAccessibilityServiceEnabled()

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
                            MenuScreen(
                                onItemClicked = { routeId ->
                                    navController.navigate(routeId)
                                },
                                onApiKeyButtonClicked = {
                                    showApiKeyDialog = true
                                }
                            )
                        }
                        composable("photo_reasoning") {
                            PhotoReasoningRoute()
                        }
                    }
                    
                    // Show API Key Dialog if needed
                    if (showApiKeyDialog) {
                        ApiKeyDialog(
                            apiKeyManager = apiKeyManager,
                            isFirstLaunch = apiKeyManager.getApiKeys().isEmpty(),
                            onDismiss = {
                                showApiKeyDialog = false
                                // Refresh the activity if keys have changed
                                if (apiKeyManager.getApiKeys().isNotEmpty()) {
                                    recreate()
                                }
                            }
                        )
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
    
    // Check if accessibility service is enabled and prompt user if not
    fun checkAccessibilityServiceEnabled() {
        val isEnabled = ScreenOperatorAccessibilityService.isAccessibilityServiceEnabled(this)
        Log.d(TAG, "Accessibility service enabled: $isEnabled")
        
        if (!isEnabled) {
            // Show a toast message
            Toast.makeText(
                this,
                "Bitte aktivieren Sie den Accessibility Service für die Klick-Funktionalität",
                Toast.LENGTH_LONG
            ).show()
            
            // Open accessibility settings
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }
    
    // Function to update status message in UI
    fun updateStatusMessage(message: String, isError: Boolean) {
        runOnUiThread {
            val duration = if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            Toast.makeText(this, message, duration).show()
            Log.d(TAG, "Status message: $message, isError: $isError")
        }
    }
    
    // Function to check if all required permissions are granted
    fun areAllPermissionsGranted(): Boolean {
        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }
    
    // Function to show API key dialog
    fun showApiKeyDialog() {
        showApiKeyDialog = true
    }
    
    // Function to get current API key
    fun getCurrentApiKey(): String? {
        return apiKeyManager.getCurrentApiKey()
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
        
        // Check if accessibility service is enabled
        checkAccessibilityServiceEnabled()
    }
    
    override fun onPause() {
        super.onPause()
        // DO NOT clear the instance when activity is paused
        // This is critical for the accessibility service to work when app is in background
        Log.d(TAG, "onPause: Keeping MainActivity instance")
        // instance = null  // This line was causing the issue
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Only clear if this instance is the current one
        if (instance == this) {
            Log.d(TAG, "onDestroy: Clearing MainActivity instance")
            instance = null
        }
    }
}
