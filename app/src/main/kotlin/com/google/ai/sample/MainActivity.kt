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
            Log.d(TAG, "Received broadcast: ${intent?.action}")
            when (intent?.action) {
                TrialTimerService.ACTION_TRIAL_EXPIRED -> {
                    Log.d(TAG, "Trial expired broadcast received.")
                    updateTrialState(TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED)
                }
                TrialTimerService.ACTION_INTERNET_TIME_UNAVAILABLE -> {
                    Log.d(TAG, "Internet time unavailable broadcast received.")
                    // Only update to INTERNET_UNAVAILABLE_CANNOT_VERIFY if not already expired or purchased
                    if (currentTrialState != TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED && currentTrialState != TrialManager.TrialState.PURCHASED) {
                        updateTrialState(TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY)
                    }
                }
                TrialTimerService.ACTION_INTERNET_TIME_AVAILABLE -> {
                    val internetTime = intent.getLongExtra(TrialTimerService.EXTRA_CURRENT_UTC_TIME_MS, 0L)
                    Log.d(TAG, "Internet time available broadcast received: $internetTime")
                    if (internetTime > 0) {
                        // Call startTrialIfNecessaryWithInternetTime first, as it might change the "awaiting" flag
                        TrialManager.startTrialIfNecessaryWithInternetTime(this@MainActivity, internetTime)
                        // Then, get the potentially updated state
                        val newState = TrialManager.getTrialState(this@MainActivity, internetTime)
                        Log.d(TAG, "State from TrialManager after internet time: $newState")
                        updateTrialState(newState)
                    }
                }
            }
        }
    }

    private fun updateTrialState(newState: TrialManager.TrialState) {
        if (currentTrialState == newState && newState != TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET && newState != TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY) {
            Log.d(TAG, "Trial state is already $newState, no UI update needed for message.")
            // Still update currentTrialState in case it was a no-op for the message but important for logic
            currentTrialState = newState
            if (newState == TrialManager.TrialState.ACTIVE_INTERNET_TIME_CONFIRMED || newState == TrialManager.TrialState.PURCHASED) {
                 showTrialInfoDialog = false // Ensure dialog is hidden if active or purchased
            }
            return
        }
        currentTrialState = newState
        Log.d(TAG, "Trial state updated to: $currentTrialState")
        when (currentTrialState) {
            TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET -> {
                trialInfoMessage = "Warte auf Internetverbindung zur Verifizierung der Testzeit..."
                showTrialInfoDialog = true
            }
            TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY -> {
                trialInfoMessage = "Testzeit kann nicht verifiziert werden. Bitte Internetverbindung prüfen."
                showTrialInfoDialog = true
            }
            TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED -> {
                trialInfoMessage = "Ihr 30-minütiger Testzeitraum ist beendet. Bitte abonnieren Sie die App, um sie weiterhin nutzen zu können."
                showTrialInfoDialog = true // This will trigger the persistent dialog
            }
            TrialManager.TrialState.ACTIVE_INTERNET_TIME_CONFIRMED,
            TrialManager.TrialState.PURCHASED -> {
                trialInfoMessage = "" // Clear message
                showTrialInfoDialog = false
            }
        }
    }

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase)
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.d(TAG, "User cancelled the purchase flow.")
            Toast.makeText(this, "Spendevorgang abgebrochen.", Toast.LENGTH_SHORT).show()
        } else {
            Log.e(TAG, "Billing error: ${billingResult.debugMessage} (Code: ${billingResult.responseCode})")
            Toast.makeText(this, "Fehler beim Spendevorgang: ${billingResult.debugMessage}", Toast.LENGTH_LONG).show()
        }
    }

    fun getCurrentApiKey(): String? {
        return if (::apiKeyManager.isInitialized) {
            apiKeyManager.getCurrentApiKey()
        } else {
            null
        }
    }

    internal fun checkAccessibilityServiceEnabled(): Boolean {
        Log.d(TAG, "Checking accessibility service.")
        val service = packageName + "/" + ScreenOperatorAccessibilityService::class.java.canonicalName
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val isEnabled = enabledServices?.contains(service, ignoreCase = true) == true
        if (!isEnabled) {
            Log.d(TAG, "Accessibility Service not enabled.")
        }
        return isEnabled
    }

    internal fun requestManageExternalStoragePermission() {
        Log.d(TAG, "Requesting manage external storage permission (dummy).")
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
        return photoReasoningViewModel
    }

    fun setPhotoReasoningViewModel(viewModel: PhotoReasoningViewModel) {
        this.photoReasoningViewModel = viewModel
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        Log.d(TAG, "onCreate: Setting MainActivity instance")

        apiKeyManager = ApiKeyManager.getInstance(this)
        // Show API Key dialog if no key is set, irrespective of trial state initially, 
        // but not if trial is already known to be expired (handled by TrialExpiredDialog)
        if (apiKeyManager.getCurrentApiKey().isNullOrEmpty()) {
             showApiKeyDialog = true
        }

        checkAndRequestPermissions()
        // checkAccessibilityServiceEnabled() // Called in onResume
        setupBillingClient()

        TrialManager.initializeTrialStateFlagsIfNecessary(this)

        val intentFilter = IntentFilter().apply {
            addAction(TrialTimerService.ACTION_TRIAL_EXPIRED)
            addAction(TrialTimerService.ACTION_INTERNET_TIME_UNAVAILABLE)
            addAction(TrialTimerService.ACTION_INTERNET_TIME_AVAILABLE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trialStatusReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(trialStatusReceiver, intentFilter)
        }

        // Initial state check. Pass null for time, TrialManager will handle it.
        updateTrialState(TrialManager.getTrialState(this, null))
        startTrialServiceIfNeeded() // Start service based on this initial state

        setContent {
            navController = rememberNavController()
            GenerativeAISample {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(navController)
                    // Show API Key dialog if needed, but not if trial is expired (as that has its own dialog)
                    if (showApiKeyDialog && currentTrialState != TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED) {
                        ApiKeyDialog(
                            apiKeyManager = apiKeyManager,
                            isFirstLaunch = apiKeyManager.getApiKeys().isEmpty(),
                            onDismiss = {
                                showApiKeyDialog = false
                                // If a key was set, we might want to re-evaluate things or just let the UI update.
                                // For now, just dismissing is fine.
                            }
                        )
                    }
                    // Handle Trial State Dialogs
                    when (currentTrialState) {
                        TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED -> {
                            TrialExpiredDialog(
                                onPurchaseClick = { initiateDonationPurchase() },
                                onDismiss = { /* Persistent dialog, user must purchase or exit */ }
                            )
                        }
                        TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET,
                        TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY -> {
                            if (showTrialInfoDialog) { // This flag is controlled by updateTrialState
                                InfoDialog(message = trialInfoMessage, onDismiss = { showTrialInfoDialog = false })
                            }
                        }
                        TrialManager.TrialState.ACTIVE_INTERNET_TIME_CONFIRMED,
                        TrialManager.TrialState.PURCHASED -> {
                            // No specific dialog for these states, info dialog should be hidden by updateTrialState
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun AppNavigation(navController: NavHostController) {
        val isAppEffectivelyUsable = currentTrialState == TrialManager.TrialState.ACTIVE_INTERNET_TIME_CONFIRMED ||
                                   currentTrialState == TrialManager.TrialState.PURCHASED

        // These actions should always be available, regardless of trial state, as per user request.
        val alwaysAvailableRoutes = listOf("ApiKeyDialog", "ChangeModel") // Placeholder for actual route if ChangeModel has one

        NavHost(navController = navController, startDestination = "menu") {
            composable("menu") {
                MenuScreen(
                    onItemClicked = { routeId ->
                        // Allow navigation to always available routes or if app is usable
                        if (alwaysAvailableRoutes.contains(routeId) || isAppEffectivelyUsable) {
                            // Specific handling for API Key dialog directly if it's not a separate route
                            if (routeId == "SHOW_API_KEY_DIALOG_ACTION") { // Use a constant or enum for this
                                showApiKeyDialog = true
                            } else {
                                navController.navigate(routeId)
                            }
                        } else {
                            updateStatusMessage(trialInfoMessage, isError = true)
                        }
                    },
                    onApiKeyButtonClicked = {
                        // This button in MenuScreen is now always enabled.
                        // Its action is to show the ApiKeyDialog.
                        showApiKeyDialog = true
                    },
                    onDonationButtonClicked = { initiateDonationPurchase() },
                    // isTrialExpired is used by MenuScreen to potentially change UI elements (e.g., text on donate button)
                    // but not to disable Change API Key / Change Model buttons.
                    isTrialExpired = (currentTrialState == TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED) ||
                                     (currentTrialState == TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET) ||
                                     (currentTrialState == TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY)
                )
            }
            composable("photo_reasoning") { // Example of a feature route
                if (isAppEffectivelyUsable) {
                    PhotoReasoningRoute()
                } else {
                    LaunchedEffect(Unit) {
                        navController.popBackStack() // Go back to menu
                        updateStatusMessage(trialInfoMessage, isError = true)
                    }
                }
            }
            // Add other composable routes here, checking isAppEffectivelyUsable if they are trial-dependent
        }
    }

    private fun startTrialServiceIfNeeded() {
        // Start service unless purchased or already expired (and confirmed by internet time)
        if (currentTrialState != TrialManager.TrialState.PURCHASED && currentTrialState != TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED) {
            Log.d(TAG, "Starting TrialTimerService because current state is: $currentTrialState")
            val serviceIntent = Intent(this, TrialTimerService::class.java)
            serviceIntent.action = TrialTimerService.ACTION_START_TIMER
            startService(serviceIntent)
        } else {
            Log.d(TAG, "TrialTimerService not started. State: $currentTrialState")
        }
    }

    private fun setupBillingClient() {
        billingClient = BillingClient.newBuilder(this)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases()
            .build()

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "BillingClient setup successful.")
                    queryProductDetails()
                    queryActiveSubscriptions() // This will also update trial state if purchased
                } else {
                    Log.e(TAG, "BillingClient setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "BillingClient service disconnected.")
                // Potentially try to reconnect or handle gracefully
            }
        })
    }

    private fun queryProductDetails() {
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
                Log.d(TAG, "Product details loaded: ${monthlyDonationProductDetails?.name}")
            } else {
                Log.e(TAG, "Failed to query product details: ${billingResult.debugMessage}")
            }
        }
    }

    private fun initiateDonationPurchase() {
        if (!billingClient.isReady) {
            Log.e(TAG, "BillingClient not ready.")
            updateStatusMessage("Bezahldienst nicht bereit. Bitte später versuchen.", true)
            if (billingClient.connectionState == BillingClient.ConnectionState.CLOSED || billingClient.connectionState == BillingClient.ConnectionState.DISCONNECTED){
                // Attempt to reconnect if disconnected
                billingClient.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(setupResult: BillingResult) {
                        if (setupResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            initiateDonationPurchase() // Retry purchase after successful reconnection
                        } else {
                             Log.e(TAG, "BillingClient setup failed after disconnect: ${setupResult.debugMessage}")
                        }
                    }
                    override fun onBillingServiceDisconnected() { Log.w(TAG, "BillingClient still disconnected.") }
                })
            }
            return
        }
        if (monthlyDonationProductDetails == null) {
            Log.e(TAG, "Product details not loaded yet.")
            updateStatusMessage("Spendeninformationen werden geladen. Bitte kurz warten und erneut versuchen.", true)
            queryProductDetails() // Attempt to reload product details
            return
        }

        monthlyDonationProductDetails?.let { productDetails ->
            val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
            if (offerToken == null) {
                Log.e(TAG, "No offer token found for product: ${productDetails.productId}")
                updateStatusMessage("Spendenangebot nicht gefunden.", true)
                return@let
            }
            val productDetailsParamsList = listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .setOfferToken(offerToken)
                    .build()
            )
            val billingFlowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(productDetailsParamsList)
                .build()
            val billingResult = billingClient.launchBillingFlow(this, billingFlowParams)
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(TAG, "Failed to launch billing flow: ${billingResult.debugMessage}")
                updateStatusMessage("Fehler beim Starten des Spendevorgangs: ${billingResult.debugMessage}", true)
            }
        } ?: run {
            Log.e(TAG, "Donation product details are null.")
            updateStatusMessage("Spendenprodukt nicht verfügbar.", true)
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (purchase.products.contains(subscriptionProductId)) {
                if (!purchase.isAcknowledged) {
                    val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                    billingClient.acknowledgePurchase(acknowledgePurchaseParams) { ackBillingResult ->
                        if (ackBillingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            Log.d(TAG, "Subscription purchase acknowledged.")
                            updateStatusMessage("Vielen Dank für Ihr Abonnement!")
                            TrialManager.markAsPurchased(this)
                            updateTrialState(TrialManager.TrialState.PURCHASED)
                            // Stop the trial timer service as it's no longer needed
                            val stopIntent = Intent(this, TrialTimerService::class.java)
                            stopIntent.action = TrialTimerService.ACTION_STOP_TIMER
                            startService(stopIntent)
                        } else {
                            Log.e(TAG, "Failed to acknowledge purchase: ${ackBillingResult.debugMessage}")
                            updateStatusMessage("Fehler beim Bestätigen des Kaufs: ${ackBillingResult.debugMessage}", true)
                        }
                    }
                } else {
                    Log.d(TAG, "Subscription already acknowledged.")
                    updateStatusMessage("Abonnement bereits aktiv.")
                    TrialManager.markAsPurchased(this)
                    updateTrialState(TrialManager.TrialState.PURCHASED)
                }
            }
        } else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            updateStatusMessage("Ihre Zahlung ist in Bearbeitung.")
        }
    }

    private fun queryActiveSubscriptions() {
        if (!billingClient.isReady) {
            Log.w(TAG, "queryActiveSubscriptions: BillingClient not ready.")
            return
        }
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                var isSubscribed = false
                purchases.forEach { purchase ->
                    if (purchase.products.contains(subscriptionProductId) && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        isSubscribed = true
                        if (!purchase.isAcknowledged) handlePurchase(purchase) // Acknowledge if not already
                        // Break or return early if subscription found and handled
                        return@forEach
                    }
                }
                if (isSubscribed) {
                    Log.d(TAG, "User has an active subscription.")
                    TrialManager.markAsPurchased(this) // Ensure flag is set
                    updateTrialState(TrialManager.TrialState.PURCHASED)
                    val stopIntent = Intent(this, TrialTimerService::class.java)
                    stopIntent.action = TrialTimerService.ACTION_STOP_TIMER
                    startService(stopIntent) // Stop trial timer
                } else {
                    Log.d(TAG, "User has no active subscription. Trial logic will apply.")
                    // If no active subscription, ensure trial state is re-evaluated and service started if needed
                    updateTrialState(TrialManager.getTrialState(this, null)) // Re-check state without internet time first
                    startTrialServiceIfNeeded()
                }
            } else {
                Log.e(TAG, "Failed to query active subscriptions: ${billingResult.debugMessage}")
                // If query fails, still re-evaluate trial state and start service if needed
                updateTrialState(TrialManager.getTrialState(this, null))
                startTrialServiceIfNeeded()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        instance = this
        Log.d(TAG, "onResume: Setting MainActivity instance")
        checkAccessibilityServiceEnabled()
        if (::billingClient.isInitialized && billingClient.isReady) {
            queryActiveSubscriptions() // This will update state if purchased
        } else if (::billingClient.isInitialized && billingClient.connectionState == BillingClient.ConnectionState.DISCONNECTED) {
            Log.d(TAG, "onResume: Billing client disconnected, attempting to reconnect.")
            setupBillingClient() // Attempt to reconnect billing client
        } else {
            // If billing client not ready or not initialized, rely on current trial state logic
            Log.d(TAG, "onResume: Billing client not ready or not initialized. Default trial logic applies.")
            updateTrialState(TrialManager.getTrialState(this, null))
            startTrialServiceIfNeeded()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(trialStatusReceiver)
        if (::billingClient.isInitialized) {
            billingClient.endConnection()
        }
        if (this == instance) {
            instance = null
            Log.d(TAG, "onDestroy: MainActivity instance cleared")
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest)
        } else {
            // Permissions already granted, ensure trial service is started if needed
            // This was potentially missed if onCreate didn't have permissions yet
            startTrialServiceIfNeeded()
        }
    }

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
            // Manifest.permission.POST_NOTIFICATIONS // Removed as per user request
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
            Log.d(TAG, "All required permissions granted")
            updateStatusMessage("Alle erforderlichen Berechtigungen erteilt")
            startTrialServiceIfNeeded() // Start service after permissions granted
        } else {
            Log.d(TAG, "Some required permissions denied")
            updateStatusMessage("Einige erforderliche Berechtigungen wurden verweigert. Die App benötigt diese für volle Funktionalität.", true)
            // Consider how to handle denied permissions regarding trial service start
            // For now, the service won't start if not all *required* permissions are granted.
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private var instance: MainActivity? = null
        fun getInstance(): MainActivity? {
            return instance
        }
    }
}

@Composable
fun TrialExpiredDialog(
    onPurchaseClick: () -> Unit,
    onDismiss: () -> Unit // Kept for consistency, but dialog is persistent
) {
    Dialog(onDismissRequest = onDismiss) { // onDismiss will likely do nothing to make it persistent
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Testzeitraum abgelaufen",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Ihr 30-minütiger Testzeitraum ist beendet. Bitte abonnieren Sie die App, um sie weiterhin nutzen zu können.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onPurchaseClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Abonnieren")
                }
            }
        }
    }
}

@Composable
fun InfoDialog(
    message: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Information", // Or a more dynamic title
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(modifier = Modifier.height(24.dp))
                TextButton(onClick = onDismiss) {
                    Text("OK")
                }
            }
        }
    }
}

