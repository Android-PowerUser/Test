package com.google.ai.sample

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.ai.sample.feature.multimodal.PhotoReasoningRoute
import com.google.ai.sample.feature.multimodal.PhotoReasoningViewModel // Added import
import com.google.ai.sample.ui.theme.GenerativeAISample
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var photoReasoningViewModel: PhotoReasoningViewModel? = null // Corrected type
    private lateinit var apiKeyManager: ApiKeyManager
    private var showApiKeyDialog by mutableStateOf(false)

    // Google Play Billing
    private lateinit var billingClient: BillingClient
    private var monthlyDonationProductDetails: ProductDetails? = null
    private val subscriptionProductId = "donation_monthly_2_90_eur" // IMPORTANT: Replace with your actual Product ID

    private var currentTrialState by mutableStateOf(TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET)
    private var showTrialInfoDialog by mutableStateOf(false)
    private var trialInfoMessage by mutableStateOf("")

    private lateinit var navController: NavHostController

    private val trialStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "trialStatusReceiver: Received broadcast. Action: ${intent?.action}")
            if (intent == null) {
                Log.w(TAG, "trialStatusReceiver: Intent is null, cannot process broadcast.")
                return
            }
            Log.d(TAG, "trialStatusReceiver: Intent extras: ${intent.extras}")

            when (intent.action) {
                TrialTimerService.ACTION_TRIAL_EXPIRED -> {
                    Log.i(TAG, "trialStatusReceiver: ACTION_TRIAL_EXPIRED received.")
                    updateTrialState(TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED)
                }
                TrialTimerService.ACTION_INTERNET_TIME_UNAVAILABLE -> {
                    Log.i(TAG, "trialStatusReceiver: ACTION_INTERNET_TIME_UNAVAILABLE received.")
                    if (currentTrialState != TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED && currentTrialState != TrialManager.TrialState.PURCHASED) {
                        Log.d(TAG, "trialStatusReceiver: Updating state to INTERNET_UNAVAILABLE_CANNOT_VERIFY.")
                        updateTrialState(TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY)
                    } else {
                        Log.d(TAG, "trialStatusReceiver: State is EXPIRED or PURCHASED, not updating to INTERNET_UNAVAILABLE.")
                    }
                }
                TrialTimerService.ACTION_INTERNET_TIME_AVAILABLE -> {
                    val internetTime = intent.getLongExtra(TrialTimerService.EXTRA_CURRENT_UTC_TIME_MS, 0L)
                    Log.i(TAG, "trialStatusReceiver: ACTION_INTERNET_TIME_AVAILABLE received. InternetTime: $internetTime")
                    if (internetTime > 0) {
                        Log.d(TAG, "trialStatusReceiver: Internet time is valid ($internetTime). Calling TrialManager.startTrialIfNecessaryWithInternetTime.")
                        TrialManager.startTrialIfNecessaryWithInternetTime(this@MainActivity, internetTime)
                        Log.d(TAG, "trialStatusReceiver: Calling TrialManager.getTrialState with time: $internetTime")
                        val newState = TrialManager.getTrialState(this@MainActivity, internetTime)
                        Log.i(TAG, "trialStatusReceiver: New state from TrialManager after internet time: $newState")
                        updateTrialState(newState)
                    } else {
                        Log.w(TAG, "trialStatusReceiver: Received ACTION_INTERNET_TIME_AVAILABLE but internetTime is invalid ($internetTime). Not processing.")
                    }
                }
                else -> {
                     Log.w(TAG, "trialStatusReceiver: Received unknown broadcast action: ${intent.action}")
                }
            }
        }
    }

    private fun updateTrialState(newState: TrialManager.TrialState) {
        Log.i(TAG, "updateTrialState: Attempting to update state from $currentTrialState to $newState")
        if (currentTrialState == newState && newState != TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET && newState != TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY) {
            Log.d(TAG, "updateTrialState: Trial state is already $newState, no UI update needed for message.")
            currentTrialState = newState // Still update to ensure consistency if internal logic expects it
            if (newState == TrialManager.TrialState.ACTIVE_INTERNET_TIME_CONFIRMED || newState == TrialManager.TrialState.PURCHASED) {
                 showTrialInfoDialog = false
            }
            return
        }
        currentTrialState = newState
        Log.i(TAG, "updateTrialState: Trial state successfully updated to: $currentTrialState")
        when (currentTrialState) {
            TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET -> {
                trialInfoMessage = "Warte auf Internetverbindung zur Verifizierung der Testzeit..."
                Log.d(TAG, "updateTrialState: Set message for NOT_YET_STARTED_AWAITING_INTERNET. showTrialInfoDialog = true")
                showTrialInfoDialog = true
            }
            TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY -> {
                trialInfoMessage = "Testzeit kann nicht verifiziert werden. Bitte Internetverbindung prüfen."
                Log.d(TAG, "updateTrialState: Set message for INTERNET_UNAVAILABLE_CANNOT_VERIFY. showTrialInfoDialog = true")
                showTrialInfoDialog = true
            }
            TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED -> {
                trialInfoMessage = "Ihr 30-minütiger Testzeitraum ist beendet. Bitte abonnieren Sie die App, um sie weiterhin nutzen zu können."
                Log.d(TAG, "updateTrialState: Set message for EXPIRED_INTERNET_TIME_CONFIRMED. showTrialInfoDialog = true")
                showTrialInfoDialog = true
            }
            TrialManager.TrialState.ACTIVE_INTERNET_TIME_CONFIRMED,
            TrialManager.TrialState.PURCHASED -> {
                trialInfoMessage = ""
                Log.d(TAG, "updateTrialState: Cleared message for ACTIVE_INTERNET_TIME_CONFIRMED or PURCHASED. showTrialInfoDialog = false")
                showTrialInfoDialog = false
            }
        }
    }

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        Log.d(TAG, "purchasesUpdatedListener: BillingResult: ${billingResult.responseCode}, Purchases: ${purchases?.size ?: 0}")
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                Log.d(TAG, "purchasesUpdatedListener: Handling purchase: ${purchase.orderId}")
                handlePurchase(purchase)
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.d(TAG, "purchasesUpdatedListener: User cancelled the purchase flow.")
            Toast.makeText(this, "Spendevorgang abgebrochen.", Toast.LENGTH_SHORT).show()
        } else {
            Log.e(TAG, "purchasesUpdatedListener: Billing error: ${billingResult.debugMessage} (Code: ${billingResult.responseCode})")
            Toast.makeText(this, "Fehler beim Spendevorgang: ${billingResult.debugMessage}", Toast.LENGTH_LONG).show()
        }
    }

    fun getCurrentApiKey(): String? {
        val key = if (::apiKeyManager.isInitialized) apiKeyManager.getCurrentApiKey() else null
        Log.d(TAG, "getCurrentApiKey called, returning: ${key != null}")
        return key
    }

    internal fun checkAccessibilityServiceEnabled(): Boolean {
        Log.d(TAG, "checkAccessibilityServiceEnabled: Checking accessibility service.")
        val service = packageName + "/" + ScreenOperatorAccessibilityService::class.java.canonicalName
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val isEnabled = enabledServices?.contains(service, ignoreCase = true) == true
        Log.d(TAG, "checkAccessibilityServiceEnabled: Service $service isEnabled: $isEnabled")
        return isEnabled
    }

    internal fun requestManageExternalStoragePermission() {
        Log.d(TAG, "requestManageExternalStoragePermission: (Dummy call for now)")
    }

    fun updateStatusMessage(message: String, isError: Boolean = false) {
        Toast.makeText(this, message, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        if (isError) {
            Log.e(TAG, "Status Message (Error): $message")
        } else {
            Log.d(TAG, "Status Message: $message")
        }
    }

    fun getPhotoReasoningViewModel(): PhotoReasoningViewModel? {
        Log.d(TAG, "getPhotoReasoningViewModel called.")
        return photoReasoningViewModel
    }

    fun setPhotoReasoningViewModel(viewModel: PhotoReasoningViewModel) {
        Log.d(TAG, "setPhotoReasoningViewModel called.")
        this.photoReasoningViewModel = viewModel
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        Log.i(TAG, "onCreate: Activity creating. Instance set.")

        apiKeyManager = ApiKeyManager.getInstance(this)
        if (apiKeyManager.getCurrentApiKey().isNullOrEmpty()) {
             Log.d(TAG, "onCreate: No API key found, setting showApiKeyDialog to true.")
             showApiKeyDialog = true
        }

        Log.d(TAG, "onCreate: Calling checkAndRequestPermissions.")
        checkAndRequestPermissions()
        Log.d(TAG, "onCreate: Calling setupBillingClient.")
        setupBillingClient()

        Log.d(TAG, "onCreate: Calling TrialManager.initializeTrialStateFlagsIfNecessary.")
        TrialManager.initializeTrialStateFlagsIfNecessary(this)

        val intentFilter = IntentFilter().apply {
            addAction(TrialTimerService.ACTION_TRIAL_EXPIRED)
            addAction(TrialTimerService.ACTION_INTERNET_TIME_UNAVAILABLE)
            addAction(TrialTimerService.ACTION_INTERNET_TIME_AVAILABLE)
        }
        Log.d(TAG, "onCreate: Registering trialStatusReceiver.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trialStatusReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(trialStatusReceiver, intentFilter)
        }

        Log.d(TAG, "onCreate: Performing initial state check. Calling TrialManager.getTrialState with null time.")
        val initialTrialState = TrialManager.getTrialState(this, null)
        Log.i(TAG, "onCreate: Initial trial state from TrialManager: $initialTrialState")
        updateTrialState(initialTrialState)
        Log.d(TAG, "onCreate: Calling startTrialServiceIfNeeded based on initial state: $currentTrialState")
        startTrialServiceIfNeeded()

        setContent {
            Log.d(TAG, "onCreate: Setting content.")
            navController = rememberNavController()
            GenerativeAISample {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(navController)
                    if (showApiKeyDialog && currentTrialState != TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED) {
                        Log.d(TAG, "onCreate: Displaying ApiKeyDialog. showApiKeyDialog: $showApiKeyDialog, currentTrialState: $currentTrialState")
                        ApiKeyDialog(
                            apiKeyManager = apiKeyManager,
                            isFirstLaunch = apiKeyManager.getApiKeys().isEmpty(),
                            onDismiss = {
                                Log.d(TAG, "ApiKeyDialog dismissed.")
                                showApiKeyDialog = false
                            }
                        )
                    }
                    Log.d(TAG, "onCreate: Evaluating trial state for dialogs. Current state: $currentTrialState")
                    when (currentTrialState) {
                        TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED -> {
                            Log.d(TAG, "onCreate: Displaying TrialExpiredDialog.")
                            TrialExpiredDialog(
                                onPurchaseClick = { 
                                    Log.d(TAG, "TrialExpiredDialog: Purchase clicked.")
                                    initiateDonationPurchase() 
                                },
                                onDismiss = { Log.d(TAG, "TrialExpiredDialog: Dismiss attempted (persistent dialog).") }
                            )
                        }
                        TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET,
                        TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY -> {
                            if (showTrialInfoDialog) {
                                Log.d(TAG, "onCreate: Displaying InfoDialog with message: $trialInfoMessage")
                                InfoDialog(message = trialInfoMessage, onDismiss = { 
                                    Log.d(TAG, "InfoDialog dismissed.")
                                    showTrialInfoDialog = false 
                                })
                            }
                        }
                        TrialManager.TrialState.ACTIVE_INTERNET_TIME_CONFIRMED,
                        TrialManager.TrialState.PURCHASED -> {
                            Log.d(TAG, "onCreate: Trial state is ACTIVE or PURCHASED, no specific dialog.")
                        }
                    }
                }
            }
        }
        Log.i(TAG, "onCreate: Activity creation completed.")
    }

    @Composable
    fun AppNavigation(navController: NavHostController) {
        val isAppEffectivelyUsable = currentTrialState == TrialManager.TrialState.ACTIVE_INTERNET_TIME_CONFIRMED ||
                                   currentTrialState == TrialManager.TrialState.PURCHASED
        Log.d(TAG, "AppNavigation: isAppEffectivelyUsable: $isAppEffectivelyUsable (currentTrialState: $currentTrialState)")

        val alwaysAvailableRoutes = listOf("ApiKeyDialog", "ChangeModel")

        NavHost(navController = navController, startDestination = "menu") {
            composable("menu") {
                MenuScreen(
                    onItemClicked = { routeId ->
                        Log.d(TAG, "MenuScreen: Item clicked: $routeId. isAppEffectivelyUsable: $isAppEffectivelyUsable")
                        if (alwaysAvailableRoutes.contains(routeId) || isAppEffectivelyUsable) {
                            if (routeId == "SHOW_API_KEY_DIALOG_ACTION") {
                                Log.d(TAG, "MenuScreen: Showing API Key Dialog via action.")
                                showApiKeyDialog = true
                            } else {
                                Log.d(TAG, "MenuScreen: Navigating to $routeId")
                                navController.navigate(routeId)
                            }
                        } else {
                            Log.w(TAG, "MenuScreen: Navigation to $routeId blocked. App not usable. Message: $trialInfoMessage")
                            updateStatusMessage(trialInfoMessage, isError = true)
                        }
                    },
                    onApiKeyButtonClicked = {
                        Log.d(TAG, "MenuScreen: API Key button clicked, showing dialog.")
                        showApiKeyDialog = true
                    },
                    onDonationButtonClicked = { 
                        Log.d(TAG, "MenuScreen: Donation button clicked.")
                        initiateDonationPurchase() 
                    },
                    isTrialExpired = (currentTrialState == TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED) ||
                                     (currentTrialState == TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET) ||
                                     (currentTrialState == TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY)
                )
            }
            composable(PhotoReasoningRoute) { // Ensure this route constant is defined
                 if (isAppEffectivelyUsable || apiKeyManager.getCurrentApiKey().isNullOrEmpty()) { // Allow access if API key needs to be set
                    Log.d(TAG, "Navigating to PhotoReasoningRoute.")
                    PhotoReasoningRoute(
                        photoReasoningViewModel = photoReasoningViewModel ?: PhotoReasoningViewModel(apiKeyManager),
                        onViewModelCreated = { viewModel ->
                            if (photoReasoningViewModel == null) {
                                photoReasoningViewModel = viewModel
                            }
                        }
                    )
                } else {
                    Log.w(TAG, "Navigation to PhotoReasoningRoute blocked. App not usable. Current state: $currentTrialState")
                    // Optionally, navigate back or show a message
                    LaunchedEffect(Unit) {
                        navController.popBackStack() // Go back to menu
                        updateStatusMessage(trialInfoMessage, isError = true)
                    }
                }
            }
            // TODO: Add other composable destinations here
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume: Activity resumed. Current trial state: $currentTrialState")
        // Re-check state on resume, especially if coming back from settings or purchase flow
        // Pass null for time, TrialManager will handle it, potentially using persisted end time.
        Log.d(TAG, "onResume: Calling TrialManager.getTrialState with null time.")
        val updatedStateOnResume = TrialManager.getTrialState(this, null)
        Log.i(TAG, "onResume: State from TrialManager on resume: $updatedStateOnResume")
        updateTrialState(updatedStateOnResume)
        startTrialServiceIfNeeded() // Ensure service is running if needed

        // Check if a purchase was made and needs acknowledgment
        billingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases.isNotEmpty()) {
                purchases.forEach { purchase ->
                    if (!purchase.isAcknowledged && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        Log.d(TAG, "onResume: Found unacknowledged purchase: ${purchase.orderId}. Acknowledging.")
                        acknowledgePurchase(purchase.purchaseToken)
                    }
                }
            }
        }
        if (!checkAccessibilityServiceEnabled()) {
            showAccessibilityServiceDialog()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause: Activity paused.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: Activity being destroyed. Unregistering trialStatusReceiver.")
        unregisterReceiver(trialStatusReceiver)
        if (::billingClient.isInitialized) {
            Log.d(TAG, "onDestroy: Ending BillingClient connection.")
            billingClient.endConnection()
        }
        instance = null
        Log.d(TAG, "onDestroy: MainActivity instance set to null.")
    }

    private fun checkAndRequestPermissions() {
        Log.d(TAG, "checkAndRequestPermissions: Checking permissions.")
        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            requiredPermissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            // POST_NOTIFICATIONS removed as per user request
        } else {
            requiredPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) { // Q (29) restricted, <=P (28) is fine
                requiredPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            Log.i(TAG, "checkAndRequestPermissions: Requesting permissions: ${permissionsToRequest.joinToString()}")
            permissionsLauncher.launch(permissionsToRequest)
        } else {
            Log.i(TAG, "checkAndRequestPermissions: All required permissions already granted.")
            // If all permissions are granted, we can proceed with starting the service if needed.
            // This is important if permissions were granted *after* initial onCreate check.
            startTrialServiceIfNeeded()
        }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d(TAG, "permissionsLauncher: Received permission results: $permissions")
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.i(TAG, "permissionsLauncher: All permissions granted by user.")
            // Permissions granted, now we can safely start the service if needed
            startTrialServiceIfNeeded()
        } else {
            val deniedPermissions = permissions.entries.filter { !it.value }.map { it.key }
            Log.w(TAG, "permissionsLauncher: Some permissions denied: $deniedPermissions")
            updateStatusMessage("Einige Berechtigungen wurden verweigert. Die App benötigt diese für volle Funktionalität.", isError = true)
            // Optionally, show a dialog explaining why permissions are needed and guide user to settings
            showPermissionRationaleDialog(deniedPermissions)
        }
    }

    private fun showPermissionRationaleDialog(deniedPermissions: List<String>) {
        Log.d(TAG, "showPermissionRationaleDialog: Showing rationale for denied permissions: $deniedPermissions")
        AlertDialog.Builder(this)
            .setTitle("Berechtigungen erforderlich")
            .setMessage("Die App benötigt die folgenden Berechtigungen, um korrekt zu funktionieren: ${deniedPermissions.joinToString()}. Bitte erteilen Sie diese in den App-Einstellungen.")
            .setPositiveButton("Einstellungen") { _, _ ->
                Log.d(TAG, "showPermissionRationaleDialog: Opening app settings.")
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("Abbrechen") { dialog, _ ->
                Log.d(TAG, "showPermissionRationaleDialog: Cancelled by user.")
                dialog.dismiss()
                // User chose not to grant permissions, app functionality might be limited.
                updateStatusMessage("App-Funktionalität ist ohne die erforderlichen Berechtigungen eingeschränkt.", isError = true)
            }
            .show()
    }

    private fun startTrialServiceIfNeeded() {
        Log.i(TAG, "startTrialServiceIfNeeded: Checking if trial service needs to be started. Current state: $currentTrialState")
        // Only start the service if the trial is in a state that requires internet time verification
        // AND all necessary runtime permissions have been granted.
        val permissionsGranted = areAllRequiredPermissionsGranted()
        Log.d(TAG, "startTrialServiceIfNeeded: Permissions granted status: $permissionsGranted")

        if (!permissionsGranted) {
            Log.w(TAG, "startTrialServiceIfNeeded: Not starting service - permissions not granted.")
            // If permissions are not granted, updateTrialState might be relevant if not already showing an error.
            // However, checkAndRequestPermissions should handle the permission error message.
            return
        }

        if (currentTrialState == TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET ||
            currentTrialState == TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY) {
            Log.i(TAG, "startTrialServiceIfNeeded: Conditions met. Starting TrialTimerService with ACTION_START_TIMER.")
            val serviceIntent = Intent(this, TrialTimerService::class.java).apply {
                action = TrialTimerService.ACTION_START_TIMER
            }
            try {
                ContextCompat.startForegroundService(this, serviceIntent)
                Log.d(TAG, "startTrialServiceIfNeeded: TrialTimerService started successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "startTrialServiceIfNeeded: Error starting TrialTimerService", e)
                updateStatusMessage("Fehler beim Starten des Testzeit-Dienstes.", isError = true)
            }
        } else {
            Log.d(TAG, "startTrialServiceIfNeeded: Conditions not met to start service. State: $currentTrialState")
        }
    }

    private fun areAllRequiredPermissionsGranted(): Boolean {
        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            requiredPermissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            requiredPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                requiredPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        return requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun setupBillingClient() {
        Log.d(TAG, "setupBillingClient: Setting up BillingClient.")
        billingClient = BillingClient.newBuilder(this)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases()
            .build()

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                Log.d(TAG, "onBillingSetupFinished: Result code: ${billingResult.responseCode}, Message: ${billingResult.debugMessage}")
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "onBillingSetupFinished: BillingClient setup successful.")
                    queryProductDetails()
                    queryPastPurchases() // Check for existing subscriptions
                } else {
                    Log.e(TAG, "onBillingSetupFinished: BillingClient setup failed. Code: ${billingResult.responseCode}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "onBillingServiceDisconnected: BillingClient disconnected. Retrying connection...")
                // Try to restart the connection on the next request to Google Play by calling queryProductDetails() again.
                // Optionally, implement a retry policy.
            }
        })
    }

    private fun queryProductDetails() {
        Log.d(TAG, "queryProductDetails: Querying for product ID: $subscriptionProductId")
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(subscriptionProductId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && productDetailsList.isNotEmpty()) {
                monthlyDonationProductDetails = productDetailsList.find { it.productId == subscriptionProductId }
                Log.i(TAG, "queryProductDetails: Found product details: ${monthlyDonationProductDetails?.name}")
            } else {
                Log.e(TAG, "queryProductDetails: Failed to query product details. Code: ${billingResult.responseCode}, List size: ${productDetailsList.size}")
                monthlyDonationProductDetails = null
            }
        }
    }

    private fun initiateDonationPurchase() {
        Log.d(TAG, "initiateDonationPurchase: Initiating purchase flow.")
        if (monthlyDonationProductDetails == null) {
            Log.e(TAG, "initiateDonationPurchase: Product details not available. Cannot start purchase.")
            Toast.makeText(this, "Spendenoption derzeit nicht verfügbar. Bitte später erneut versuchen.", Toast.LENGTH_LONG).show()
            queryProductDetails() // Try to re-fetch product details
            return
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(monthlyDonationProductDetails!!)
                .setOfferToken(monthlyDonationProductDetails!!.subscriptionOfferDetails!!.first().offerToken) // Assuming there is at least one offer
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        val billingResult = billingClient.launchBillingFlow(this, billingFlowParams)
        Log.d(TAG, "initiateDonationPurchase: Billing flow launch result: ${billingResult.responseCode}")
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "initiateDonationPurchase: Failed to launch billing flow. Code: ${billingResult.responseCode}, Message: ${billingResult.debugMessage}")
            Toast.makeText(this, "Fehler beim Starten des Spendevorgangs: ${billingResult.debugMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        Log.d(TAG, "handlePurchase: Processing purchase: ${purchase.orderId}, State: ${purchase.purchaseState}")
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                Log.d(TAG, "handlePurchase: Purchase is new and unacknowledged. Acknowledging: ${purchase.purchaseToken}")
                acknowledgePurchase(purchase.purchaseToken)
            } else {
                Log.d(TAG, "handlePurchase: Purchase already acknowledged.")
                // Grant entitlement for acknowledged purchase if not already done
                TrialManager.markAsPurchased(this)
                updateTrialState(TrialManager.TrialState.PURCHASED)
            }
        } else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            Log.d(TAG, "handlePurchase: Purchase is pending.")
            // Here you can say to the user that purchase is pending
            Toast.makeText(this, "Spende wird bearbeitet...", Toast.LENGTH_LONG).show()
        } else if (purchase.purchaseState == Purchase.PurchaseState.UNSPECIFIED_STATE) {
            Log.w(TAG, "handlePurchase: Purchase in unspecified state.")
        }
    }

    private fun acknowledgePurchase(purchaseToken: String) {
        Log.d(TAG, "acknowledgePurchase: Acknowledging purchase token: $purchaseToken")
        val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()
        billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.i(TAG, "acknowledgePurchase: Purchase acknowledged successfully.")
                TrialManager.markAsPurchased(this)
                updateTrialState(TrialManager.TrialState.PURCHASED)
                Toast.makeText(this, "Vielen Dank für Ihre Spende!", Toast.LENGTH_LONG).show()
            } else {
                Log.e(TAG, "acknowledgePurchase: Failed to acknowledge purchase. Code: ${billingResult.responseCode}, Message: ${billingResult.debugMessage}")
            }
        }
    }

    private fun queryPastPurchases() {
        Log.d(TAG, "queryPastPurchases: Checking for existing subscriptions.")
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                if (purchases.isNotEmpty()) {
                    Log.i(TAG, "queryPastPurchases: Found ${purchases.size} existing purchases.")
                    var activeSubscriptionFound = false
                    purchases.forEach { purchase ->
                        if (purchase.products.contains(subscriptionProductId) && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                            Log.d(TAG, "queryPastPurchases: Active subscription found: ${purchase.orderId}")
                            activeSubscriptionFound = true
                            if (!purchase.isAcknowledged) {
                                acknowledgePurchase(purchase.purchaseToken)
                            } else {
                                // Already acknowledged, ensure app state reflects purchase
                                TrialManager.markAsPurchased(this)
                                updateTrialState(TrialManager.TrialState.PURCHASED)
                            }
                        }
                    }
                    if (!activeSubscriptionFound) {
                        Log.d(TAG, "queryPastPurchases: No active subscription for $subscriptionProductId found among existing purchases.")
                    }
                } else {
                    Log.d(TAG, "queryPastPurchases: No existing purchases found.")
                }
            } else {
                Log.e(TAG, "queryPastPurchases: Error querying past purchases. Code: ${billingResult.responseCode}")
            }
        }
    }

    private fun showAccessibilityServiceDialog() {
        Log.d(TAG, "showAccessibilityServiceDialog: Showing dialog.")
        AlertDialog.Builder(this)
            .setTitle("Bedienungshilfen-Dienst erforderlich")
            .setMessage("Diese App benötigt den Bedienungshilfen-Dienst, um die Bildschirminhalte zu analysieren und zu steuern. Bitte aktivieren Sie den Dienst in den Einstellungen.")
            .setPositiveButton("Einstellungen") { _, _ ->
                Log.d(TAG, "showAccessibilityServiceDialog: Opening accessibility settings.")
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton("Abbrechen") { dialog, _ ->
                Log.d(TAG, "showAccessibilityServiceDialog: Cancelled by user.")
                dialog.dismiss()
                Toast.makeText(this, "Ohne den Bedienungshilfen-Dienst ist die App nicht funktionsfähig.", Toast.LENGTH_LONG).show()
                // finish() // Optionally close the app if the service is absolutely critical
            }
            .setCancelable(false)
            .show()
    }

    companion object {
        private const val TAG = "MainActivity_DEBUG"
        private var instance: MainActivity? = null

        fun getInstance(): MainActivity? {
            Log.d(TAG, "getInstance called, instance is ${if (instance == null) "null" else "not null"}")
            return instance
        }
    }
}

