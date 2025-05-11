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

// Define route constants at the top level or in a companion object if preferred
private object Routes {
    const val MENU = "menu"
    const val PHOTO_REASONING = "photo_reasoning"
    const val SHOW_API_KEY_DIALOG_ACTION = "SHOW_API_KEY_DIALOG_ACTION"
    // Add other routes here
}

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
            if (newState == TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED && trialInfoMessage.isBlank()) {
                 trialInfoMessage = "Ihr 30-minütiger Testzeitraum ist beendet. Bitte abonnieren Sie die App, um sie weiterhin nutzen zu können."
            }
            if ((newState == TrialManager.TrialState.ACTIVE_INTERNET_TIME_CONFIRMED || newState == TrialManager.TrialState.PURCHASED) && showTrialInfoDialog) {
                showTrialInfoDialog = false
            }
            return
        }

        val oldState = currentTrialState
        currentTrialState = newState
        Log.i(TAG, "updateTrialState: Trial state updated from $oldState to $currentTrialState")

        when (currentTrialState) {
            TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED -> {
                trialInfoMessage = "Ihr 30-minütiger Testzeitraum ist beendet. Bitte abonnieren Sie die App, um sie weiterhin nutzen zu können."
                Log.d(TAG, "updateTrialState: Set message to \'$trialInfoMessage\' for EXPIRED state.")
            }
            TrialManager.TrialState.ACTIVE_INTERNET_TIME_CONFIRMED,
            TrialManager.TrialState.PURCHASED -> {
                trialInfoMessage = ""
                showTrialInfoDialog = false
                Log.d(TAG, "updateTrialState: Cleared message, showTrialInfoDialog = false (ACTIVE, PURCHASED)")
            }
            TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET,
            TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY -> {
                trialInfoMessage = "Keine Internetverbindung zur Verifizierung des Testzeitraums."
                showTrialInfoDialog = true
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
        instance = this
        Log.d(TAG, "onCreate: MainActivity instance set.")

        apiKeyManager = ApiKeyManager.getInstance(this)
        Log.d(TAG, "onCreate: ApiKeyManager initialized.")
        if (apiKeyManager.getCurrentApiKey().isNullOrEmpty()) {
             showApiKeyDialog = true
             Log.d(TAG, "onCreate: No API key found, showApiKeyDialog set to true.")
        } else {
             Log.d(TAG, "onCreate: API key found.")
        }

        Log.d(TAG, "onCreate: Calling checkAndRequestPermissions.")
        checkAndRequestPermissions()
        
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
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(trialStatusReceiver, intentFilter)
        }
        Log.d(TAG, "onCreate: trialStatusReceiver registered.")

        Log.d(TAG, "onCreate: Performing initial trial state check.")
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            val initialTrialState = TrialManager.getTrialState(this@MainActivity, null)
            Log.i(TAG, "onCreate: Initial trial state from TrialManager: $initialTrialState. Updating local state.")
            updateTrialState(initialTrialState)
            Log.d(TAG, "onCreate: Calling startTrialServiceIfNeeded based on initial state: $currentTrialState")
            startTrialServiceIfNeeded()
        }
        
        refreshAccessibilityServiceStatus()

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
                    AppNavigation(navController)

                    if (showApiKeyDialog) {
                        Log.d(TAG, "setContent: Rendering ApiKeyDialog. showApiKeyDialog=$showApiKeyDialog, currentTrialState=$currentTrialState")
                        ApiKeyDialog(
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
                            }
                        )
                    } else if (showTrialInfoDialog && trialInfoMessage.isNotBlank()) {
                        Log.d(TAG, "setContent: Rendering InfoDialog. Message: $trialInfoMessage")
                        InfoDialog(message = trialInfoMessage, onDismiss = {
                            Log.d(TAG, "InfoDialog onDismiss called.")
                            showTrialInfoDialog = false
                        })
                    }
                }
            }
        }
        Log.d(TAG, "onCreate: setContent finished.")
    }

    @Composable
    fun AppNavigation(navController: NavHostController) {
        val isAppEffectivelyUsable = currentTrialState != TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED
        Log.d(TAG, "AppNavigation: isAppEffectivelyUsable = $isAppEffectivelyUsable (currentTrialState: $currentTrialState)")

        val alwaysAvailableRoutes = listOf(Routes.SHOW_API_KEY_DIALOG_ACTION)

        NavHost(navController = navController, startDestination = Routes.MENU) {
            composable(Routes.MENU) {
                Log.d(TAG, "AppNavigation: Composing '${Routes.MENU}' screen.")
                MenuScreen(
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
                    PhotoReasoningRoute()
                } else {
                    Log.w(TAG, "AppNavigation: '${Routes.PHOTO_REASONING}' blocked. Popping back stack.")
                    LaunchedEffect(currentTrialState) {
                        if (!isAppEffectivelyUsable) { 
                            navController.popBackStack(Routes.MENU, inclusive = false, saveState = false)
                            val messageToShow = if (trialInfoMessage.isNotBlank()) trialInfoMessage else "Zugriff auf Funktion verweigert. Testzeitraum abgelaufen."
                            updateStatusMessage(messageToShow, isError = true)
                        }
                    }
                }
            }
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
            } catch (e: Exception) { 
                Log.e(TAG, "startTrialServiceIfNeeded: Failed to start TrialTimerService", e)
            }
        } else {
            Log.i(TAG, "TrialTimerService not started. State: $currentTrialState (Purchased or Expired)")
        }
    }

    private fun setupBillingClient() {
        Log.d(TAG, "setupBillingClient called.")
        if (::billingClient.isInitialized && billingClient.isReady) {
            Log.d(TAG, "setupBillingClient: BillingClient already initialized and ready.")
            if(monthlyDonationProductDetails == null) queryProductDetails()
            queryActiveSubscriptions()
            return
        }
        if (::billingClient.isInitialized && billingClient.connectionState == BillingClient.ConnectionState.CONNECTING) {
            Log.d(TAG, "setupBillingClient: BillingClient already connecting.")
            return
        }

        billingClient = BillingClient.newBuilder(this)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases()
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
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "onBillingServiceDisconnected: BillingClient service disconnected. Will attempt to reconnect on next relevant action or onResume.")
            }
        })
    }

    private fun queryProductDetails() {
        Log.d(TAG, "queryProductDetails called.")
        if (!::billingClient.isInitialized || !billingClient.isReady) { 
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
            if (billingClient.connectionState == BillingClient.ConnectionState.CLOSED || 
                billingClient.connectionState == BillingClient.ConnectionState.DISCONNECTED){
                Log.d(TAG, "initiateDonationPurchase: BillingClient disconnected, attempting to reconnect by calling setupBillingClient.")
                setupBillingClient()
            }
            return
        }
        if (monthlyDonationProductDetails == null) {
            Log.e(TAG, "initiateDonationPurchase: Product details not loaded yet.")
            updateStatusMessage("Spendeninformationen werden geladen. Bitte kurz warten und erneut versuchen.", true)
            Log.d(TAG, "initiateDonationPurchase: Attempting to reload product details.")
            queryProductDetails()
            return
        }

        monthlyDonationProductDetails?.let { productDetails ->
            Log.d(TAG, "initiateDonationPurchase: Product details available: ${productDetails.name}")
            val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
            if (offerToken == null) {
                Log.e(TAG, "No offer token found for product: ${productDetails.productId}. SubscriptionOfferDetails size: ${productDetails.subscriptionOfferDetails?.size}")
                updateStatusMessage("Spendenangebot nicht gefunden.", true)
                queryProductDetails()
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
            queryProductDetails()
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
                    } else { 
                        Log.i(TAG, "handlePurchase: Subscription already acknowledged.")
                        if (currentTrialState != TrialManager.TrialState.PURCHASED) {
                             TrialManager.markAsPurchased(this@MainActivity) 
                             updateTrialState(TrialManager.TrialState.PURCHASED)
                             updateStatusMessage("Abonnement bereits aktiv.") 
                        }
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
            } else { 
                Log.w(TAG, "handlePurchase: Purchase state is UNSPECIFIED_STATE or other: ${purchase.purchaseState}")
            }
        }
    }

    private fun queryActiveSubscriptions() {
        Log.d(TAG, "queryActiveSubscriptions called.")
        if (!::billingClient.isInitialized || !billingClient.isReady) {
            Log.w(TAG, "queryActiveSubscriptions: BillingClient not initialized or not ready. Cannot query.")
            lifecycleScope.launch(Dispatchers.Main.immediate) {
                // Revert to original logic: update trial state based on local info if billing is not ready
                val localTrialState = TrialManager.getTrialState(this@MainActivity, null)
                updateTrialState(localTrialState)
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
                            handlePurchase(purchase) 
                            return@launch 
                        }
                    }
                } else {
                     Log.e(TAG, "Failed to query active subscriptions: ${billingResult.debugMessage}")
                }

                if (!isSubscribed) {
                    Log.i(TAG, "queryActiveSubscriptions: No active subscription found for $subscriptionProductId, or query failed. Applying local trial logic.")
                    // Original logic: if TrialManager says PURCHASED but billing says no, it's an inconsistency.
                    // We will update the state based on TrialManager and start service if needed.
                    val currentDeviceTrialState = TrialManager.getTrialState(this@MainActivity, null)
                    if (currentDeviceTrialState == TrialManager.TrialState.PURCHASED && !isSubscribed) {
                        Log.w(TAG, "queryActiveSubscriptions: TrialManager indicates PURCHASED, but no active subscription found via billing. This is an inconsistency.")
                        // Proceeding with TrialManager's state for now as per original logic flow.
                        // A more robust solution might involve clearing the local "purchased" flag if your TrialManager supports it.
                    }
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
        } else { 
            Log.d(TAG, "onResume: Billing client initializing or in an intermediate state (State: ${billingClient.connectionState}). Relying on local trial state.")
            lifecycleScope.launch(Dispatchers.Main.immediate) {
                val localTrialState = TrialManager.getTrialState(this@MainActivity, null)
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
        if (this == instance) { 
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
        }
    }

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        mutableListOf(
            Manifest.permission.READ_MEDIA_IMAGES,
        ).apply {
            // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            //    add(Manifest.permission.POST_NOTIFICATIONS) // Uncomment if TrialTimerService uses foreground notifications
            // }
        }.toTypedArray()
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
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
            lifecycleScope.launch(Dispatchers.Main.immediate) {
                startTrialServiceIfNeeded()
            }
        } else {
            val deniedPermissions = permissions.entries.filter { !it.value }.map { it.key }
            Log.w(TAG, "Some required permissions denied by user: $deniedPermissions")
            updateStatusMessage("Einige erforderliche Berechtigungen wurden verweigert. Die App benötigt diese für volle Funktionalität.", true)
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
    onDismiss: () -> Unit 
) {
    Log.d("TrialExpiredDialog", "Composing TrialExpiredDialog")
    Dialog(
        onDismissRequest = {
            Log.d("TrialExpiredDialog", "onDismissRequest triggered. Dialog is intended to be persistent.")
            onDismiss() 
        }
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
                    color = MaterialTheme.colorScheme.onSurface 
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Ihr 30-minütiger Testzeitraum ist beendet. Bitte abonnieren Sie die App, um sie weiterhin nutzen zu können.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = MaterialTheme.colorScheme.onSurface 
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
                    color = MaterialTheme.colorScheme.onSurface 
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = MaterialTheme.colorScheme.onSurface 
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

// Assume ApiKeyDialog, MenuScreen, PhotoReasoningRoute etc. are defined in your project.
// @Composable fun ApiKeyDialog(apiKeyManager: ApiKeyManager, isFirstLaunch: Boolean, onDismiss: () -> Unit) { /* ... */ }
// @Composable fun MenuScreen(onItemClicked: (String) -> Unit, onApiKeyButtonClicked: () -> Unit, onDonationButtonClicked: () -> Unit, isTrialExpired: Boolean) { /* ... */ }

