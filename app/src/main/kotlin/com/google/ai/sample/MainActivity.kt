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
import androidx.activity.viewModels // Import für by viewModels hinzugefügt
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
import com.google.ai.sample.feature.chat.ChatViewModel // Import für ChatViewModel hinzugefügt
import com.google.ai.sample.feature.multimodal.PhotoReasoningRoute
import com.google.ai.sample.feature.multimodal.PhotoReasoningViewModel // Import für PhotoReasoningViewModel hinzugefügt
import com.google.ai.sample.feature.text.SummarizeRoute
import com.google.ai.sample.ui.theme.GenerativeAISample

class MainActivity : ComponentActivity() {

    // --- Initialisiere deine ViewModels hier (Beispiel mit by viewModels) ---
    // Stelle sicher, dass die Factory korrekt verwendet wird
    private val chatViewModel: ChatViewModel by viewModels { GenerativeViewModelFactory }
    private val photoReasoningViewModel: PhotoReasoningViewModel by viewModels { GenerativeViewModelFactory }

    // --- Bestehende Methode zum Setzen/Holen von PhotoReasoningViewModel ---
    // Hinweis: Das explizite Setzen ist bei Verwendung von 'by viewModels' oft nicht nötig.
    // Der AccessibilityService kann jetzt 'getPhotoReasoningViewModel()' verwenden.
    // private var photoReasoningViewModelInstance: PhotoReasoningViewModel? = null // Wird durch by viewModels ersetzt

    // Function to get the PhotoReasoningViewModel
    fun getPhotoReasoningViewModel(): PhotoReasoningViewModel? {
        // Log.d(TAG, "getPhotoReasoningViewModel called, returning: ${photoReasoningViewModel != null}") // Log kann bleiben
        // Gibt die Instanz zurück, die über 'by viewModels' verwaltet wird
        return if (::photoReasoningViewModel.isInitialized) photoReasoningViewModel else {
            Log.w(TAG, "getPhotoReasoningViewModel called but ViewModel is not initialized yet.")
            null
        }
    }

    // Function to set the PhotoReasoningViewModel
    // Diese Methode ist wahrscheinlich nicht mehr nötig, wenn 'by viewModels' verwendet wird.
    // Der Service sollte 'getPhotoReasoningViewModel' aufrufen.
    // Wenn du sie dennoch brauchst, muss sie anders implementiert werden, da photoReasoningViewModel 'val' ist.
    /*
    fun setPhotoReasoningViewModel(viewModel: PhotoReasoningViewModel) {
        Log.d(TAG, "setPhotoReasoningViewModel called with viewModel: $viewModel")
        // photoReasoningViewModel = viewModel // Kann nicht zugewiesen werden, da 'val'
        // Stattdessen: Logge nur, dass die Route aktiv ist
        Log.d(TAG, "PhotoReasoningRoute is active, ViewModel instance should be available via getPhotoReasoningViewModel().")
    }
    */

    // --- NEUE METHODE für ChatViewModel ---
    /**
     * Gibt die Instanz des ChatViewModels zurück.
     * Kann null sein, wenn die Activity nicht bereit ist oder das ViewModel noch nicht initialisiert wurde.
     */
    fun getChatViewModel(): ChatViewModel? {
        return if (::chatViewModel.isInitialized) chatViewModel else {
            Log.w(TAG, "getChatViewModel called but ViewModel is not initialized yet.")
            null
        }
    }


    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO // Video wird hier nicht direkt verwendet, aber oft zusammen angefragt
        )
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE // WRITE ist oft nicht mehr nötig für Mediendateien
            // Manifest.permission.WRITE_EXTERNAL_STORAGE // Nur wenn wirklich nötig
        )
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.i(TAG, "All required media permissions granted.")
            Toast.makeText(this, "Medien-Berechtigungen erteilt", Toast.LENGTH_SHORT).show()
            // Prüfe nach Medienerlaubnis die "Alle Dateien"-Erlaubnis
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                checkAndRequestManageExternalStoragePermission()
            }
        } else {
            Log.w(TAG, "Some media permissions were denied.")
            Toast.makeText(this, "Medien-Berechtigungen teilweise verweigert. Screenshot-Verarbeitung benötigt Zugriff.", Toast.LENGTH_LONG).show()
            // Optional: Erkläre genauer oder leite zu den Einstellungen
        }
    }

    // Launcher für ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
    private val requestManageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Prüfe erneut nach dem Ergebnis aus den Einstellungen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) {
                Log.i(TAG, "MANAGE_EXTERNAL_STORAGE permission granted after returning from settings.")
                Toast.makeText(this, "Zugriff auf alle Dateien erteilt.", Toast.LENGTH_SHORT).show()
            } else {
                Log.w(TAG, "MANAGE_EXTERNAL_STORAGE permission still not granted after returning from settings.")
                Toast.makeText(this, "Zugriff auf alle Dateien weiterhin nicht erteilt. Screenshot-Suche könnte eingeschränkt sein.", Toast.LENGTH_LONG).show()
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set the instance immediately when created
        instance = this
        Log.d(TAG, "onCreate: Setting MainActivity instance")

        // Check and request permissions
        checkAndRequestMediaPermissions() // Umbenannt für Klarheit

        // Check if accessibility service is enabled (wird auch in onResume geprüft)
        // checkAccessibilityServiceEnabled() // Kann hier optional sein

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
                            // Hier wird das photoReasoningViewModel implizit von Compose verwendet
                            PhotoReasoningRoute(/* viewModel = photoReasoningViewModel */)
                        }
                        composable("chat") {
                            // Hier wird das chatViewModel implizit von Compose verwendet
                            ChatRoute(/* viewModel = chatViewModel */)
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestMediaPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.i(TAG, "Requesting media permissions: $permissionsToRequest")
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.i(TAG, "All media permissions already granted.")
            // Wenn Medien OK sind, prüfe "Alle Dateien"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                checkAndRequestManageExternalStoragePermission()
            }
        }
    }

    private fun checkAndRequestManageExternalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                Log.w(TAG, "MANAGE_EXTERNAL_STORAGE permission not granted. Requesting...")
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = Uri.parse("package:$packageName")
                    requestManageStorageLauncher.launch(intent) // Verwende den neuen Launcher
                    Toast.makeText(this, "Zusätzliche Berechtigung für Dateizugriff benötigt (Screenshot-Suche)", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Could not launch ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", e)
                    try {
                         // Fallback für einige Geräte
                         val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                         requestManageStorageLauncher.launch(intent)
                         Toast.makeText(this, "Zusätzliche Berechtigung für Dateizugriff benötigt (Screenshot-Suche)", Toast.LENGTH_LONG).show()
                    } catch (e2: Exception) {
                         Log.e(TAG, "Could not launch ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION either", e2)
                         Toast.makeText(this, "Fehler beim Öffnen der Dateizugriffs-Einstellungen.", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                 Log.i(TAG, "MANAGE_EXTERNAL_STORAGE permission already granted.")
            }
        }
    }

    // Check if accessibility service is enabled and prompt user if not
    fun checkAccessibilityServiceEnabled() {
        // Führe Prüfung nur aus, wenn die Activity im Vordergrund ist (resumed)
        if (!isFinishing && !isChangingConfigurations) {
            val isEnabled = ScreenOperatorAccessibilityService.isAccessibilityServiceEnabled(this)
            Log.d(TAG, "Checking Accessibility service status. Enabled: $isEnabled")

            if (!isEnabled) {
                // Zeige Toast nur einmal oder seltener, um nicht zu nerven
                Toast.makeText(
                    this,
                    "Barrierefreiheitsdienst für ScreenOperator ist nicht aktiviert. Klick-Funktionen sind nicht verfügbar.",
                    Toast.LENGTH_LONG
                ).show()

                // Open accessibility settings
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                } catch (e: Exception) {
                     Log.e(TAG, "Could not open Accessibility Settings", e)
                     Toast.makeText(this, "Fehler beim Öffnen der Barrierefreiheits-Einstellungen.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Function to update status message in UI (using Toast)
    fun updateStatusMessage(message: String, isError: Boolean) {
        // Stelle sicher, dass es auf dem UI-Thread läuft
        runOnUiThread {
            val duration = if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            Toast.makeText(this, message, duration).show()
            if (isError) {
                Log.e(TAG, "Status message (Error): $message")
            } else {
                Log.i(TAG, "Status message: $message")
            }
        }
    }

    // Function to check if all required permissions are granted (optional helper)
    fun areAllRequiredPermissionsGranted(): Boolean {
        val mediaGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val storageManagerGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            true // Nicht relevant für ältere Versionen
        }
        return mediaGranted && storageManagerGranted
    }

    companion object {
        private const val TAG = "MainActivity"

        // Static instance of MainActivity for accessibility service access
        @Volatile
        private var instance: MainActivity? = null

        // Method to get the MainActivity instance
        fun getInstance(): MainActivity? {
            // Log.d(TAG, "getInstance called, returning: ${instance != null}") // Kann sehr gesprächig sein
            return instance
        }
    }

    override fun onResume() {
        super.onResume()
        // Set the instance when activity is resumed
        instance = this
        Log.d(TAG, "onResume: Setting MainActivity instance")

        // Check permissions and accessibility service status when returning to the app
        checkAndRequestMediaPermissions()
        checkAccessibilityServiceEnabled()
    }

    override fun onPause() {
        super.onPause()
        // DO NOT clear the instance when activity is paused
        Log.d(TAG, "onPause: Keeping MainActivity instance")
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