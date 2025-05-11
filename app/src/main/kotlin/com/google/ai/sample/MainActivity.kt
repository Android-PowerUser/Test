package com.google.ai.sample

import android.Manifest
import android.app.Activity
// import android.app.AlertDialog // Not used directly, consider removing if not needed elsewhere
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
// import android.net.Uri // Not used directly
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
// import androidx.compose.foundation.layout.Box // Not used directly
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
// import androidx.compose.material3.CircularProgressIndicator // Not used directly
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
// import androidx.compose.runtime.remember // Not used directly
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
// import androidx.compose.ui.platform.LocalContext // Not used directly
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
// import androidx.compose.ui.window.DialogProperties // For non-dismissable dialog
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
import com.google.ai.sample.feature.multimodal.PhotoReasoningViewModel
import com.google.ai.sample.ui.theme.GenerativeAISample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var photoReasoningViewModel: PhotoReasoningViewModel? = null
    private lateinit var apiKeyManager: ApiKeyManager
    private var showApiKeyDialog by mutableStateOf(false)

    // Google Play Billing
    private lateinit var billingClient: BillingClient
    private var monthlyDonationProductDetails: ProductDetails? = null
    private val subscriptionProductId = "donation_monthly_2_90_eur" // Make sure this ID is correct

    private var currentTrialState by mutableStateOf(TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET)
    private var showTrialInfoDialog by mutableStateOf(false)
    private var trialInfoMessage by mutableStateOf("")

    private lateinit var navController: NavHostController

    private val _isAccessibilityServiceEnabled = MutableStateFlow(false)
    val isAccessibilityServiceEnabledFlow: StateFlow<Boolean> = _isAccessibilityServiceEnabled.asStateFlow()

    private val trialStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "trialStatusReceiver: Received broadcast: ${intent?.action}")
            // Ensure state updates affecting UI are on the main immediate dispatcher
            lifecycleScope.launch(Dispatchers.Main.immediate) {
                when (intent?.action) {
                    TrialTimerService.ACTION_TRIAL_EXPIRED -> {
                        Log.i(TAG, "trialStatusReceiver: ACTION_TRIAL_EXPIRED received. Updating trial state on Main.immediate.")
                        updateTrialState(TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED)
                    }
                    TrialTimerService.ACTION_INTERNET_TIME_UNAVAILABLE -> {
                        Log.i(TAG, "trialStatusReceiver: ACTION_INTERNET_TIME_UNAVAILABLE received. Current state: $currentTrialState")
                        if (currentTrialState != TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED && currentTrialState != TrialManager.TrialState.PURCHASED) {
                            Log.d(TAG, "trialStatusReceiver: Updating state to INTERNET_UNAVAILABLE_CANNOT_VERIFY.")
                            updateTrialState(TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY)
                        } else {
                            Log.d(TAG, "trialStatusReceiver: State is EXPIRED or PURCHASED, not updating to INTERNET_UNAVAILABLE_CANNOT_VERIFY.")
                        }
                    }
                    TrialTimerService.ACTION_INTERNET_TIME_AVAILABLE -> {
                        val internetTime = intent.getLongExtra(TrialTimerService.EXTRA_CURRENT_UTC_TIME_MS, 0L)
                        Log.i(TAG, "trialStatusReceiver: ACTION_INTERNET_TIME_AVAILABLE received. InternetTime: $internetTime")
                        if (internetTime > 0) {
                            Log.d(TAG, "trialStatusReceiver: Valid internet time received. Calling TrialManager.startTrialIfNecessaryWithInternetTime.")
                            TrialManager.startTrialIfNecessaryWithInternetTime(this@MainActivity, internetTime)
                            Log.d(TAG, "trialStatusReceiver: Calling TrialManager.getTrialState.")
                            val newState = TrialManager.getTrialState(this@MainActivity, internetTime)
                            Log.i(TAG, "trialStatusReceiver: State from TrialManager after internet time: $newState. Updating local state.")
                            updateTrialState(newState)
                        } else {
                            Log.w(TAG, "trialStatusReceiver: ACTION_INTERNET_TIME_AVAILABLE received, but internetTime is 0 or less. No action taken.")
                        }
                    }
                    else -> {
                         Log.w(TAG, "trialStatusReceiver: Received unknown action: ${intent?.action}")
                    }
                }
            }
        }
    }

    private fun updateTrialState(newState: TrialManager.TrialState) {
        Log.d(TAG, "updateTrialState called with newState: $newState. Current local state: $currentTrialState")
        if (currentTrialState == newState) {
            Log.d(TAG, "updateTrialState: Trial state is already $newState.")
            // If the state is already EXPIRED, ensure the message is set for the toast that might appear
            // and ensure the dialog visibility is correctly handled by recomposition.
            if (newState == TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED && trialInfoMessage.isBlank()) {
                 trialInfoMessage = "Ihr 30-minütiger Testzeitraum ist beendet. Bitte abonnieren Sie die App, um sie weiterhin nutzen zu können."
            }
             // If the state is already ACTIVE or PURCHASED, ensure the info dialog is hidden
            if ((newState == TrialManager.TrialState.ACTIVE_INTERNET_TIME_CONFIRMED || newState == TrialManager.TrialState.PURCHASED) && showTrialInfoDialog) {
                showTrialInfoDialog = false
            }
            // No need to re-assign currentTrialState = newState if it's already the same,
            // as that won't trigger recomposition unless the object identity changes (which it doesn't for enums).
            // However, if specific actions like setting trialInfoMessage or showTrialInfoDialog are needed
            // even if the state is the same, they should be handled here.
            // The primary purpose of this early return is to avoid redundant logging and processing if the state is truly unchanged.
            return
        }


        val oldState = currentTrialState
        currentTrialState = newState // This is the crucial Compose State update that triggers recomposition
        Log.i(TAG, "updateTrialState: Trial state updated from $oldState to $currentTrialState")

        when (currentTrialState) {
            TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED -> {
                trialInfoMessage = "Ihr 30-minütiger Testzeitraum ist beendet. Bitte abonnieren Sie die App, um sie weiterhin nutzen zu können."
                // The TrialExpiredDialog's visibility is directly tied to 'currentTrialState' in the setContent block.
                // showTrialInfoDialog = true; // This is for the InfoDialog, not the TrialExpiredDialog.
                Log.d(TAG, "updateTrialState: Set message to \'$trialInfoMessage\' for EXPIRED state.")
            }
            TrialManager.TrialState.ACTIVE_INTERNET_TIME_CONFIRMED,
            TrialManager.TrialState.PURCHASED -> {
                trialInfoMessage = ""
                showTrialInfoDialog = false // Hide InfoDialog if it was shown for other reasons
                Log.d(TAG, "updateTrialState: Cleared message, showTrialInfoDialog = false (ACTIVE, PURCHASED)")
            }
            TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET,
            TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY -> {
                // Potentially set a message and show InfoDialog if needed for these states
                // For example:
                // trialInfoMessage = "Internetverbindung wird benötigt, um den Teststatus zu überprüfen."
                // showTrialInfoDialog = true
                // Current logic from before:
                trialInfoMessage = "Keine Internetverbindung zur Verifizierung des Testzeitraums." // Example message
                showTrialInfoDialog = true // Show info dialog for these states
                Log.d(TAG, "updateTrialState: Set message for AWAITING/UNAVAILABLE state. showTrialInfoDialog = true")
            }
        }
    }

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        Log.i(TAG, "purchasesUpdatedListener: BillingResponseCode: ${billingResult.responseCode}, Message: ${billingResult.debugMessage}")
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            Log.d(TAG, "purchasesUpdatedListener: Purchases list size: ${purchases.size}")
            for (purchase in purchases) {
                Log.d(TAG, "purchasesUpdatedListener: Processing purchase: ${purchase.orderId}, Products: ${purchase.products}, State: ${purchase.purchaseState}")
                handlePurchase(purchase)
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.i(TAG, "purchasesUpdatedListener: User cancelled the purchase flow.")
            Toast.makeText(this, "Spendevorgang abgebrochen.", Toast.LENGTH_SHORT).show()
        } else {
            Log.e(TAG, "purchasesUpdatedListener: Billing error: ${billingResult.debugMessage} (Code: ${billingResult.responseCode})")
            Toast.makeText(this, "Fehler beim Spendevorgang: ${billingResult.debugMessage}", Toast.LENGTH_LONG).show()
        }
    }

    fun getCurrentApiKey(): String? {
        val key = if (::apiKeyManager.isInitialized) {
            apiKeyManager.getCurrentApiKey()
        } else {
            null
        }
        Log.d(TAG, "getCurrentApiKey returning: ${if (key.isNullOrEmpty()) "null or empty" else "valid key"}")
        return key
    }

    internal fun refreshAccessibilityServiceStatus() {
        Log.d(TAG, "refreshAccessibilityServiceStatus called.")
        // Ensure ScreenOperatorAccessibilityService::class.java.canonicalName is not null
        val serviceName = ScreenOperatorAccessibilityService::class.java.canonicalName
        if (serviceName == null) {
            Log.e(TAG, "refreshAccessibilityServiceStatus: Canonical name for ScreenOperatorAccessibilityService is null!")
            _isAccessibilityServiceEnabled.value = false
            return
        }
        val service = "$packageName/$serviceName"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val isEnabled = enabledServices?.contains(service, ignoreCase = true) == true
        _isAccessibilityServiceEnabled.value = isEnabled
        Log.d(TAG, "Accessibility Service $service isEnabled: $isEnabled. Flow updated.")
        if (!isEnabled) {
            Log.d(TAG, "Accessibility Service not enabled.")
        }
    }


    internal fun requestManageExternalStoragePermission() {
        // This seems to be a placeholder. Implement if needed.
        Log.d(TAG, "requestManageExternalStoragePermission (dummy) called.")
    }

    fun updateStatusMessage(message: String, isError: Boolean = false) {
        Toast.makeText(this, message, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        if (isError) {
            Log.e(TAG, "updateStatusMessage (Error): $message")
        } else {
            Log.d(TAG, "updateStatusMessage (Info): $message")
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

    fun getAccessibilitySettingsIntent(): Intent {
        Log.d(TAG, "getAccessibilitySettingsIntent called.")
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate: Activity creating.")
        super.onCreate(savedInstanceState)
        instance = this // Companion object instance
        Log.d(TAG, "onCreate: MainActivity instance set.")

        apiKeyManager = ApiKeyManager.getInstance(this)
        Log.d(TAG, "onCreate: ApiKeyManager initialized.")
        // Show API key dialog if no key is present AND trial is not expired (allow key entry if expired is too restrictive)
        if (apiKeyManager.getCurrentApiKey().isNullOrEmpty()) {
             showApiKeyDialog = true
             Log.d(TAG, "onCreate: No API key found, showApiKeyDialog set to true.")
        } else {
             Log.d(TAG, "onCreate: API key found.")
        }

        Log.d(TAG, "onCreate: Calling checkAndRequestPermissions.")
        checkAndRequestPermissions() // Permissions might be needed before trial service starts reliably
        
        Log.d(TAG, "onCreate: Calling setupBillingClient.")
        setupBillingClient()

        Log.d(TAG, "onCreate: Calling TrialManager.initializeTrialStateFlagsIfNecessary.")
        TrialManager.initializeTrialStateFlagsIfNecessary(this)

        Log.d(TAG, "onCreate: Setting up IntentFilter for trialStatusReceiver.")
        val intentFilter = IntentFilter().apply {
            addAction(TrialTimerService.ACTION_TRIAL_EXPIRED)
            addAction(TrialTimerService.ACTION_INTERNET_TIME_UNAVAILABLE)
            addAction(TrialTimerService.ACTION_INTERNET_TIME_AVAILABLE)
        }
        Log.d(TAG, "onCreate: Registering trialStatusReceiver.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trialStatusReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") // For older Android versions
            registerReceiver(trialStatusReceiver, intentFilter)
        }
        Log.d(TAG, "onCreate: trialStatusReceiver registered.")

        Log.d(TAG, "onCreate: Performing initial trial state check.")
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            val initialTrialState = TrialManager.getTrialState(this@MainActivity, null)
            Log.i(TAG, "onCreate: Initial trial state from TrialManager: $initialTrialState. Updating local state.")
            updateTrialState(initialTrialState)
            Log.d(TAG, "onCreate: Calling startTrialServiceIfNeeded based on initial state: $currentTrialState")
            startTrialServiceIfNeeded() // Start service after initial state is determined
        }
        
        refreshAccessibilityServiceStatus() // Initial check

        Log.d(TAG, "onCreate: Calling setContent.")
        setContent {
            Log.d(TAG, "setContent: Composable content rendering. Current trial state: $currentTrialState")
            navController = rememberNavController()
            GenerativeAISample {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Log.d(TAG, "setContent: Rendering AppNavigation.")
                    AppNavigation(navController) // Contains NavHost

                    // API Key Dialog - show if needed and not expired (or allow entry even if expired)
                    if (showApiKeyDialog /* && currentTrialState != TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED */) {
                        Log.d(TAG, "setContent: Rendering ApiKeyDialog. showApiKeyDialog=$showApiKeyDialog, currentTrialState=$currentTrialState")
                        ApiKeyDialog( // Ensure ApiKeyDialog is defined elsewhere or provide its composable
                            apiKeyManager = apiKeyManager,
                            isFirstLaunch = apiKeyManager.getApiKeys().isEmpty(),
                            onDismiss = {
                                Log.d(TAG, "ApiKeyDialog onDismiss called.")
                                showApiKeyDialog = false
                            }
                        )
                    }
                    
                    Log.d(TAG, "setContent: Handling Trial State Dialogs. Current state: $currentTrialState, showTrialInfoDialog: $showTrialInfoDialog")
                    
                    if (currentTrialState == TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED) {
                        Log.i(TAG, "setContent: Composing TrialExpiredDialog NOW. State is EXPIRED_INTERNET_TIME_CONFIRMED.")
                        TrialExpiredDialog(
                            onPurchaseClick = {
                                Log.d(TAG, "TrialExpiredDialog onPurchaseClick called.")
                                initiateDonationPurchase()
                            },
                            onDismiss = { 
                                Log.d(TAG, "TrialExpiredDialog onDismiss called (should be persistent).") 
                                // To make it non-dismissable by back press/outside click, use DialogProperties
                            }
                        )
                    } else if (showTrialInfoDialog && trialInfoMessage.isNotBlank()) {
                        // This covers NOT_YET_STARTED_AWAITING_INTERNET and INTERNET_UNAVAILABLE_CANNOT_VERIFY
                        // if updateTrialState sets showTrialInfoDialog = true and a message for them.
                        Log.d(TAG, "setContent: Rendering InfoDialog. Message: $trialInfoMessage")
                        InfoDialog(message = trialInfoMessage, onDismiss = {
                            Log.d(TAG, "InfoDialog onDismiss called.")
                            showTrialInfoDialog = false
                        })
                    }
                    // No specific dialog for ACTIVE_INTERNET_TIME_CONFIRMED or PURCHASED in this section
                }
            }
        }
        Log.d(TAG, "onCreate: setContent finished.")
    }

    @Composable
    fun AppNavigation(navController: NavHostController) {
        val isAppEffectivelyUsable = currentTrialState != TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED
        Log.d(TAG, "AppNavigation: isAppEffectivelyUsable = $isAppEffectivelyUsable (currentTrialState: $currentTrialState)")

        // Define route constants for better maintainability
        object Routes {
            const val MENU = "menu"
            const val PHOTO_REASONING = "photo_reasoning"
            const val SHOW_API_KEY_DIALOG_ACTION = "SHOW_API_KEY_DIALOG_ACTION"
            // Add other routes here
        }

        val alwaysAvailableRoutes = listOf(Routes.SHOW_API_KEY_DIALOG_ACTION) // Add other routes like "Settings" if they should always be available

        NavHost(navController = navController, startDestination = Routes.MENU) {
            composable(Routes.MENU) {
                Log.d(TAG, "AppNavigation: Composing '${Routes.MENU}' screen.")
                MenuScreen( // Ensure MenuScreen is defined elsewhere or provide its composable
                    onItemClicked = { routeId ->
                        Log.d(TAG, "MenuScreen onItemClicked: routeId='$routeId', isAppEffectivelyUsable=$isAppEffectivelyUsable")
                        if (alwaysAvailableRoutes.contains(routeId) || isAppEffectivelyUsable) {
                            if (routeId == Routes.SHOW_API_KEY_DIALOG_ACTION) {
                                Log.d(TAG, "MenuScreen: Showing ApiKeyDialog directly.")
                                showApiKeyDialog = true
                            } else {
                                Log.d(TAG, "MenuScreen: Navigating to route: $routeId")
                                navController.navigate(routeId)
                            }
                        } else {
                            Log.w(TAG, "MenuScreen: Navigation to '$routeId' blocked due to trial state.")
                            val messageToShow = if (trialInfoMessage.isNotBlank()) trialInfoMessage else "Aktion nicht verfügbar. Testzeitraum abgelaufen."
                            updateStatusMessage(messageToShow, isError = true)
                        }
                    },
                    onApiKeyButtonClicked = {
                        Log.d(TAG, "MenuScreen onApiKeyButtonClicked: Showing ApiKeyDialog.")
                        showApiKeyDialog = true
                    },
                    onDonationButtonClicked = {
                        Log.d(TAG, "MenuScreen onDonationButtonClicked: Initiating donation purchase.")
                        initiateDonationPurchase()
                    },
                     isTrialExpired = !isAppEffectivelyUsable
                )
            }
            composable(Routes.PHOTO_REASONING) {
                Log.d(TAG, "AppNavigation: Composing '${Routes.PHOTO_REASONING}' screen. isAppEffectivelyUsable=$isAppEffectivelyUsable")
                if (isAppEffectivelyUsable) {
                    PhotoReasoningRoute() // Ensure PhotoReasoningRoute is defined
                } else {
                    Log.w(TAG, "AppNavigation: '${Routes.PHOTO_REASONING}' blocked. Popping back stack.")
                    // Use LaunchedEffect keyed to currentTrialState to react if state changes while on this screen (e.g. purchase)
                    LaunchedEffect(currentTrialState) {
                        if (!isAppEffectivelyUsable) { // Re-check in case state changed during composition
                            navController.popBackStack(Routes.MENU, inclusive = false, saveState = false)
                            val messageToShow = if (trialInfoMessage.isNotBlank()) trialInfoMessage else "Zugriff auf Funktion verweigert. Testzeitraum abgelaufen."
                            updateStatusMessage(messageToShow, isError = true)
                        }
                    }
                }
            }
            // Add other composable routes here, applying the same isAppEffectivelyUsable check
            // Example:
            // composable("settings") { SettingsScreen() } // Assuming settings is always available
        }
    }

    private fun startTrialServiceIfNeeded() {
        Log.d(TAG, "startTrialServiceIfNeeded called. Current state: $currentTrialState")
        if (currentTrialState != TrialManager.TrialState.PURCHASED && currentTrialState != TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED) {
            Log.i(TAG, "Starting TrialTimerService because current state is: $currentTrialState")
            val serviceIntent = Intent(this, TrialTimerService::class.java)
            serviceIntent.action = TrialTimerService.ACTION_START_TIMER
            try {
                startService(serviceIntent)
                Log.d(TAG, "startTrialServiceIfNeeded: startService call succeeded.")
            } catch (e: Exception) { // Catch specific exceptions if possible, e.g., IllegalStateException for background restrictions
                Log.e(TAG, "startTrialServiceIfNeeded: Failed to start TrialTimerService", e)
                // updateStatusMessage("Fehler beim Starten des Test-Dienstes.", true) // Inform user
            }
        } else {
            Log.i(TAG, "TrialTimerService not started. State: $currentTrialState (Purchased or Expired)")
        }
    }

    private fun setupBillingClient() {
        Log.d(TAG, "setupBillingClient called.")
        if (::billingClient.isInitialized && billingClient.isReady) {
            Log.d(TAG, "setupBillingClient: BillingClient already initialized and ready.")
            // If already ready, ensure product details and subscriptions are queried if not already loaded
            if(monthlyDonationProductDetails == null) queryProductDetails()
            queryActiveSubscriptions() // Refresh subscription state
            return
        }
        if (::billingClient.isInitialized && billingClient.connectionState == BillingClient.ConnectionState.CONNECTING) {
            Log.d(TAG, "setupBillingClient: BillingClient already connecting.")
            return
        }

        billingClient = BillingClient.newBuilder(this)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases() // Required for pending purchases
            .build()
        Log.d(TAG, "setupBillingClient: BillingClient built. Starting connection.")

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                Log.i(TAG, "onBillingSetupFinished: ResponseCode: ${billingResult.responseCode}, Message: ${billingResult.debugMessage}")
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "BillingClient setup successful.")
                    Log.d(TAG, "onBillingSetupFinished: Querying product details and active subscriptions.")
                    queryProductDetails()
                    queryActiveSubscriptions() 
                } else {
                    Log.e(TAG, "BillingClient setup failed: ${billingResult.debugMessage}")
                    // Consider retrying connection with backoff or informing the user
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "onBillingServiceDisconnected: BillingClient service disconnected. Will attempt to reconnect on next relevant action or onResume.")
                // Optionally, implement a retry mechanism here with backoff
            }
        })
    }

    private fun queryProductDetails() {
        Log.d(TAG, "queryProductDetails called.")
        if (!::billingClient.isInitialized || !billingClient.isReady) { // Check initialization too
            Log.w(TAG, "queryProductDetails: BillingClient not ready or not initialized. Cannot query.")
            return
        }
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(subscriptionProductId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        Log.d(TAG, "queryProductDetails: Querying for product ID: $subscriptionProductId")

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            Log.i(TAG, "queryProductDetailsAsync result: ResponseCode: ${billingResult.responseCode}, Message: ${billingResult.debugMessage}")
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && productDetailsList.isNotEmpty()) {
                monthlyDonationProductDetails = productDetailsList.find { it.productId == subscriptionProductId }
                if (monthlyDonationProductDetails != null) {
                    Log.i(TAG, "Product details loaded: ${monthlyDonationProductDetails?.name}, ID: ${monthlyDonationProductDetails?.productId}")
                } else {
                    Log.w(TAG, "Product details for $subscriptionProductId not found in the list despite OK response.")
                }
            } else {
                Log.e(TAG, "Failed to query product details: ${billingResult.debugMessage}. List size: ${productDetailsList.size}")
            }
        }
    }

    private fun initiateDonationPurchase() {
        Log.d(TAG, "initiateDonationPurchase called.")
        if (!::billingClient.isInitialized) {
            Log.e(TAG, "initiateDonationPurchase: BillingClient not initialized.")
            updateStatusMessage("Bezahldienst nicht initialisiert. Bitte später versuchen.", true)
            return
        }
        if (!billingClient.isReady) {
            Log.e(TAG, "initiateDonationPurchase: BillingClient not ready. Connection state: ${billingClient.connectionState}")
            updateStatusMessage("Bezahldienst nicht bereit. Bitte später versuchen.", true)
            // If disconnected or closed, try to reconnect
            if (billingClient.connectionState == BillingClient.ConnectionState.CLOSED || 
                billingClient.connectionState == BillingClient.ConnectionState.DISCONNECTED){
                Log.d(TAG, "initiateDonationPurchase: BillingClient disconnected, attempting to reconnect by calling setupBillingClient.")
                setupBillingClient() // This will start a new connection attempt
            }
            return
        }
        if (monthlyDonationProductDetails == null) {
            Log.e(TAG, "initiateDonationPurchase: Product details not loaded yet.")
            updateStatusMessage("Spendeninformationen werden geladen. Bitte kurz warten und erneut versuchen.", true)
            Log.d(TAG, "initiateDonationPurchase: Attempting to reload product details.")
            queryProductDetails() // Attempt to reload details
            return
        }

        monthlyDonationProductDetails?.let { productDetails ->
            Log.d(TAG, "initiateDonationPurchase: Product details available: ${productDetails.name}")
            val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
            if (offerToken == null) {
                Log.e(TAG, "No offer token found for product: ${productDetails.productId}. SubscriptionOfferDetails size: ${productDetails.subscriptionOfferDetails?.size}")
                updateStatusMessage("Spendenangebot nicht gefunden.", true)
                queryProductDetails() // Re-query if offer token is missing, might be a temporary issue
                return@let
            }
            Log.d(TAG, "initiateDonationPurchase: Offer token found: $offerToken")
            val productDetailsParamsList = listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .setOfferToken(offerToken)
                    .build()
            )
            val billingFlowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(productDetailsParamsList)
                .build()
            Log.d(TAG, "initiateDonationPurchase: Launching billing flow.")
            val activity: Activity = this 
            val billingResult = billingClient.launchBillingFlow(activity, billingFlowParams)
            Log.i(TAG, "initiateDonationPurchase: Billing flow launch result: ${billingResult.responseCode} - ${billingResult.debugMessage}")
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(TAG, "Failed to launch billing flow: ${billingResult.debugMessage}")
                updateStatusMessage("Fehler beim Starten des Spendevorgangs: ${billingResult.debugMessage}", true)
            }
        } ?: run {
            Log.e(TAG, "initiateDonationPurchase: Donation product details are null even after check. This shouldn't happen.")
            updateStatusMessage("Spendenprodukt nicht verfügbar.", true)
            queryProductDetails() // Attempt to reload details if this unusual case occurs
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        Log.i(TAG, "handlePurchase called for purchase: OrderId: ${purchase.orderId}, Products: ${purchase.products}, State: ${purchase.purchaseState}, Token: ${purchase.purchaseToken}, Ack: ${purchase.isAcknowledged}")
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                Log.d(TAG, "handlePurchase: Purchase state is PURCHASED.")
                if (purchase.products.any { it == subscriptionProductId }) {
                    Log.d(TAG, "handlePurchase: Purchase contains target product ID: $subscriptionProductId")
                    if (!purchase.isAcknowledged) {
                        Log.i(TAG, "handlePurchase: Purchase not acknowledged. Acknowledging now.")
                        val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.purchaseToken)
                            .build()
                        billingClient.acknowledgePurchase(acknowledgePurchaseParams) { ackBillingResult ->
                             lifecycleScope.launch(Dispatchers.Main.immediate) {
                                Log.i(TAG, "handlePurchase (acknowledgePurchase): Result code: ${ackBillingResult.responseCode}, Message: ${ackBillingResult.debugMessage}")
                                if (ackBillingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                                    Log.i(TAG, "Subscription purchase acknowledged successfully.")
                                    updateStatusMessage("Vielen Dank für Ihr Abonnement!")
                                    TrialManager.markAsPurchased(this@MainActivity)
                                    updateTrialState(TrialManager.TrialState.PURCHASED)
                                    Log.d(TAG, "handlePurchase: Stopping TrialTimerService as app is purchased.")
                                    val stopIntent = Intent(this@MainActivity, TrialTimerService::class.java)
                                    stopIntent.action = TrialTimerService.ACTION_STOP_TIMER
                                    startService(stopIntent)
                                } else {
                                    Log.e(TAG, "Failed to acknowledge purchase: ${ackBillingResult.debugMessage}")
                                    updateStatusMessage("Fehler beim Bestätigen des Kaufs: ${ackBillingResult.debugMessage}", true)
                                }
                            }
                        }
                    } else { // Already acknowledged
                        Log.i(TAG, "handlePurchase: Subscription already acknowledged.")
                        // Ensure local state reflects purchase
                        if (currentTrialState != TrialManager.TrialState.PURCHASED) {
                             TrialManager.markAsPurchased(this@MainActivity) // Ensure marked
                             updateTrialState(TrialManager.TrialState.PURCHASED)
                             updateStatusMessage("Abonnement bereits aktiv.") // Inform user if state was updated
                        }
                         // Stop service if it was running
                        val stopIntent = Intent(this@MainActivity, TrialTimerService::class.java)
                        stopIntent.action = TrialTimerService.ACTION_STOP_TIMER
                        startService(stopIntent)
                        Log.d(TAG, "handlePurchase: Ensured TrialTimerService is stopped for acknowledged purchase.")
                    }
                } else {
                    Log.w(TAG, "handlePurchase: Purchase is PURCHASED but does not contain the target product ID ($subscriptionProductId). Products: ${purchase.products}")
                }
            } else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                Log.i(TAG, "handlePurchase: Purchase state is PENDING.")
                updateStatusMessage("Ihre Zahlung ist in Bearbeitung.")
            } else { // UNSPECIFIED_STATE or other
                Log.w(TAG, "handlePurchase: Purchase state is UNSPECIFIED_STATE or other: ${purchase.purchaseState}")
                // Potentially inform user or log more details
            }
        }
    }

    private fun queryActiveSubscriptions() {
        Log.d(TAG, "queryActiveSubscriptions called.")
        if (!::billingClient.isInitialized || !billingClient.isReady) {
            Log.w(TAG, "queryActiveSubscriptions: BillingClient not initialized or not ready. Cannot query. isInitialized: ${::billingClient.isInitialized}, isReady: ${if(::billingClient.isInitialized) billingClient.isReady else "N/A"}")
            lifecycleScope.launch(Dispatchers.Main.immediate) {
                // If billing isn't ready, we can't confirm purchase status via billing.
                // Revert to local trial state, assuming not purchased if billing can't be checked.
                if (TrialManager.isMarkedAsPurchased(this@MainActivity)) {
                     Log.w(TAG, "queryActiveSubscriptions: Billing not ready, but was marked purchased. Re-evaluating local trial state without purchase assumption.")
                     // Potentially clear the persisted purchased mark if it's only set after successful billing confirmation
                     // TrialManager.clearPurchasedMark(this@MainActivity) // If you have such a method
                }
                val nonPurchaseState = TrialManager.getTrialState(this@MainActivity, null, ignorePurchase = true)
                updateTrialState(nonPurchaseState)
                startTrialServiceIfNeeded()
            }
            return
        }
        Log.d(TAG, "queryActiveSubscriptions: Querying for SUBS type purchases.")
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { billingResult, purchases ->
            lifecycleScope.launch(Dispatchers.Main.immediate) {
                Log.i(TAG, "queryActiveSubscriptions result: ResponseCode: ${billingResult.responseCode}, Message: ${billingResult.debugMessage}, Purchases count: ${purchases.size}")
                var isSubscribed = false
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    purchases.forEach { purchase ->
                        Log.d(TAG, "queryActiveSubscriptions: Checking purchase - Products: ${purchase.products}, State: ${purchase.purchaseState}")
                        if (purchase.products.any { it == subscriptionProductId } && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                            Log.i(TAG, "queryActiveSubscriptions: Active subscription found for $subscriptionProductId.")
                            isSubscribed = true
                            handlePurchase(purchase) // This will update state to PURCHASED and stop service
                            return@launch // Exit launch scope as active subscription is found and handled
                        }
                    }
                } else {
                     Log.e(TAG, "Failed to query active subscriptions: ${billingResult.debugMessage}")
                     // Don't assume not subscribed on query failure, could be temporary. Rely on current state.
                }

                // If loop completes (or billingResult was not OK) and no active, purchased subscription was handled:
                if (!isSubscribed) {
                    Log.i(TAG, "queryActiveSubscriptions: No active subscription found for $subscriptionProductId, or query failed. Applying local trial logic.")
                    // If it was marked as purchased locally, but billing says no (or failed to confirm),
                    // then it's likely not purchased or the purchase status is uncertain. Revert to non-purchased trial state.
                    if (TrialManager.isMarkedAsPurchased(this@MainActivity)) {
                        TrialManager.clearPurchasedMark(this@MainActivity) // Assumes TrialManager has this
                        Log.w(TAG, "queryActiveSubscriptions: Cleared inconsistent purchased mark as no active sub found via billing.")
                    }
                    val currentDeviceTrialState = TrialManager.getTrialState(this@MainActivity, null, ignorePurchase = true) // Get state ignoring any previous purchased mark
                    updateTrialState(currentDeviceTrialState)
                    startTrialServiceIfNeeded()
                }
            }
        }
    }

    override fun onResume() {
        Log.d(TAG, "onResume: Activity resuming.")
        super.onResume()
        instance = this
        Log.d(TAG, "onResume: MainActivity instance set.")
        Log.d(TAG, "onResume: Calling refreshAccessibilityServiceStatus.")
        refreshAccessibilityServiceStatus() 

        Log.d(TAG, "onResume: Checking BillingClient status.")
        if (::billingClient.isInitialized && billingClient.isReady) {
            Log.d(TAG, "onResume: BillingClient is initialized and ready. Querying active subscriptions.")
            queryActiveSubscriptions()
        } else if (::billingClient.isInitialized && 
                   (billingClient.connectionState == BillingClient.ConnectionState.DISCONNECTED || 
                    billingClient.connectionState == BillingClient.ConnectionState.CLOSED) ) {
            Log.w(TAG, "onResume: Billing client initialized but disconnected/closed (State: ${billingClient.connectionState}). Attempting to reconnect.")
            setupBillingClient() 
        } else if (!::billingClient.isInitialized) {
            Log.w(TAG, "onResume: Billing client not initialized. Calling setupBillingClient.")
            setupBillingClient() 
        } else { // Billing client is CONNECTING or in some other state
            Log.d(TAG, "onResume: Billing client initializing or in an intermediate state (State: ${billingClient.connectionState}). Relying on local trial state until billing client is ready.")
            lifecycleScope.launch(Dispatchers.Main.immediate) {
                 // Update trial state based on non-billing info, assuming not purchased if billing is not ready.
                val localTrialState = TrialManager.getTrialState(this@MainActivity, null, ignorePurchase = true)
                updateTrialState(localTrialState)
                startTrialServiceIfNeeded()
            }
        }
        Log.d(TAG, "onResume: Finished.")
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Activity destroying.")
        super.onDestroy()
        Log.d(TAG, "onDestroy: Unregistering trialStatusReceiver.")
        try {
            unregisterReceiver(trialStatusReceiver)
            Log.d(TAG, "onDestroy: trialStatusReceiver unregistered successfully.")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "onDestroy: trialStatusReceiver was not registered or already unregistered.", e)
        }
        if (::billingClient.isInitialized && billingClient.isReady) {
            Log.d(TAG, "onDestroy: BillingClient is initialized and ready. Ending connection.")
            billingClient.endConnection()
            Log.d(TAG, "onDestroy: BillingClient connection ended.")
        }
        if (this == instance) { // Clear companion object instance
            instance = null
            Log.d(TAG, "onDestroy: MainActivity instance cleared.")
        }
        Log.d(TAG, "onDestroy: Finished.")
    }

    private fun checkAndRequestPermissions() {
        Log.d(TAG, "checkAndRequestPermissions called.")
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            Log.i(TAG, "Requesting permissions: ${permissionsToRequest.joinToString()}")
            requestPermissionLauncher.launch(permissionsToRequest)
        } else {
            Log.i(TAG, "All required permissions already granted.")
            // If permissions are already granted, ensure trial service starts if needed.
            // This might be called before onCreate's initial state check completes,
            // so ensure it's safe to call startTrialServiceIfNeeded or defer it.
            // It's generally safer to let the onCreate lifecycle manage the first start.
            // However, if this is called later, it's fine.
            // For simplicity, let initial onCreate logic handle the first start.
            // If called at other times, this can be useful:
            // lifecycleScope.launch(Dispatchers.Main.immediate) {
            //    startTrialServiceIfNeeded()
            // }
        }
    }

    // Define required permissions based on Android version
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        mutableListOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            // Manifest.permission.READ_MEDIA_VIDEO // Add if video processing is needed
            // Manifest.permission.POST_NOTIFICATIONS // Add if TrialTimerService uses foreground notifications and targets Android 13+
        ).apply {
            // Add POST_NOTIFICATIONS conditionally for Android 13+ if your service needs it
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                 // add(Manifest.permission.POST_NOTIFICATIONS) // Uncomment if needed
            }
        }.toTypedArray()
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            // Manifest.permission.WRITE_EXTERNAL_STORAGE // Only if writing is absolutely necessary
        )
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d(TAG, "requestPermissionLauncher callback received. Permissions: $permissions")
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.i(TAG, "All required permissions granted by user.")
            updateStatusMessage("Alle erforderlichen Berechtigungen erteilt")
            // After permissions are granted, the app might need to re-initialize or start services
            // This is a good place to ensure the trial service starts if it hasn't.
            lifecycleScope.launch(Dispatchers.Main.immediate) {
                startTrialServiceIfNeeded()
            }
        } else {
            val deniedPermissions = permissions.entries.filter { !it.value }.map { it.key }
            Log.w(TAG, "Some required permissions denied by user: $deniedPermissions")
            updateStatusMessage("Einige erforderliche Berechtigungen wurden verweigert. Die App benötigt diese für volle Funktionalität.", true)
            // Optionally, guide user to settings or explain consequences of denied permissions.
        }
    }

    companion object {
        private const val TAG = "MainActivity" // Consistent TAG
        private var instance: MainActivity? = null
        fun getInstance(): MainActivity? {
            // Log.d(TAG, "getInstance() called. Returning instance: ${if(instance == null) "null" else "not null"}") // Can be too verbose
            return instance
        }
    }
}

// --- Dialog Composable Functions ---
// These should ideally be in their own file if they become complex or are reused.

@Composable
fun TrialExpiredDialog(
    onPurchaseClick: () -> Unit,
    onDismiss: () -> Unit // This is Dialog's onDismissRequest
) {
    Log.d("TrialExpiredDialog", "Composing TrialExpiredDialog")
    Dialog(
        onDismissRequest = {
            Log.d("TrialExpiredDialog", "onDismissRequest triggered. Dialog is intended to be persistent.")
            onDismiss() // Call the passed lambda (MainActivity's onDismiss for this dialog only logs)
        }
        // To make it truly non-dismissable by user actions like back press or click outside:
        // properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
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
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface // Ensure text is visible
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Ihr 30-minütiger Testzeitraum ist beendet. Bitte abonnieren Sie die App, um sie weiterhin nutzen zu können.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = MaterialTheme.colorScheme.onSurface // Ensure text is visible
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        Log.d("TrialExpiredDialog", "Purchase button clicked")
                        onPurchaseClick()
                    },
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
    Log.d("InfoDialog", "Composing InfoDialog with message: $message")
    Dialog(onDismissRequest = {
        Log.d("InfoDialog", "onDismissRequest called")
        onDismiss()
    }) {
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
                    text = "Information", 
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface // Ensure text is visible
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = MaterialTheme.colorScheme.onSurface // Ensure text is visible
                )
                Spacer(modifier = Modifier.height(24.dp))
                TextButton(onClick = {
                    Log.d("InfoDialog", "OK button clicked")
                    onDismiss()
                }) {
                    Text("OK")
                }
            }
        }
    }
}

// Assume ApiKeyDialog, MenuScreen, PhotoReasoningRoute are defined elsewhere in your project.
// For example:
// @Composable fun ApiKeyDialog(apiKeyManager: ApiKeyManager, isFirstLaunch: Boolean, onDismiss: () -> Unit) { /* ... */ }
// @Composable fun MenuScreen(onItemClicked: (String) -> Unit, onApiKeyButtonClicked: () -> Unit, onDonationButtonClicked: () -> Unit, isTrialExpired: Boolean) { /* ... */ }


