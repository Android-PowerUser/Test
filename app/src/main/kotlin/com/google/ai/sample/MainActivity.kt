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
import com.google.ai.sample.feature.multimodal.PhotoReasoningViewModel
import com.google.ai.sample.ui.theme.GenerativeAISample
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
    private val subscriptionProductId = "donation_monthly_2_90_eur"

    // Core state variable for trial status. Compose UI reacts to changes in this.
    private var currentTrialState by mutableStateOf(TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET)
    // State for a generic InfoDialog (e.g., for "awaiting first internet")
    private var showTrialInfoDialog by mutableStateOf(false)
    private var trialInfoMessage by mutableStateOf("")

    private lateinit var navController: NavHostController

    private val _isAccessibilityServiceEnabled = MutableStateFlow(false)
    val isAccessibilityServiceEnabledFlow: StateFlow<Boolean> = _isAccessibilityServiceEnabled.asStateFlow()

    private val trialStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "trialStatusReceiver: Received broadcast: ${intent?.action}")
            val action = intent?.action

            when (action) {
                TrialTimerService.ACTION_TRIAL_EXPIRED -> {
                    Log.i(TAG, "trialStatusReceiver: ACTION_TRIAL_EXPIRED received. Updating trial state to EXPIRED.")
                    updateTrialState(TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED)
                }
                TrialTimerService.ACTION_INTERNET_TIME_UNAVAILABLE -> {
                    Log.i(TAG, "trialStatusReceiver: ACTION_INTERNET_TIME_UNAVAILABLE received. Current local state: $currentTrialState")
                    // Only transition to UNAVAILABLE if not already permanently expired or purchased
                    if (currentTrialState != TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED &&
                        currentTrialState != TrialManager.TrialState.PURCHASED) {
                        Log.d(TAG, "trialStatusReceiver: Updating state to INTERNET_UNAVAILABLE_CANNOT_VERIFY.")
                        updateTrialState(TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY)
                        // --- MODIFICATION START: Dialog for internet unavailable removed ---
                        // trialInfoMessage = "Keine Internetverbindung zur Überprüfung der Testversion."
                        // showTrialInfoDialog = true
                        // Log.d(TAG, "trialStatusReceiver: Set message and flag to show InfoDialog for internet unavailable.")
                        Log.d(TAG, "trialStatusReceiver: Internet unavailable. No dialog will be shown. State updated to INTERNET_UNAVAILABLE_CANNOT_VERIFY.")
                        // --- MODIFICATION END ---
                    } else {
                        Log.d(TAG, "trialStatusReceiver: State is already EXPIRED or PURCHASED, not changing to INTERNET_UNAVAILABLE_CANNOT_VERIFY.")
                    }
                }
                TrialTimerService.ACTION_INTERNET_TIME_AVAILABLE -> {
                    val internetTime = intent.getLongExtra(TrialTimerService.EXTRA_CURRENT_UTC_TIME_MS, 0L)
                    Log.i(TAG, "trialStatusReceiver: ACTION_INTERNET_TIME_AVAILABLE received. InternetTime: $internetTime")
                    if (internetTime > 0) {
                        TrialManager.startTrialIfNecessaryWithInternetTime(this@MainActivity, internetTime)
                        val newState = TrialManager.getTrialState(this@MainActivity, internetTime)
                        Log.i(TAG, "trialStatusReceiver: State from TrialManager after internet time: $newState. Updating local state.")
                        updateTrialState(newState)

                        // If a generic InfoDialog was shown (e.g. for "awaiting internet"), hide it now.
                        if (showTrialInfoDialog) {
                            showTrialInfoDialog = false
                            trialInfoMessage = ""
                            Log.d(TAG, "trialStatusReceiver: Hid generic InfoDialog as internet is now available and state updated.")
                        }
                    } else {
                        Log.w(TAG, "trialStatusReceiver: ACTION_INTERNET_TIME_AVAILABLE received, but internetTime is 0 or less. No action taken on state.")
                    }
                }
                else -> {
                     Log.w(TAG, "trialStatusReceiver: Received unknown action: ${intent?.action}")
                }
            }
        }
    }

    private fun updateTrialState(newState: TrialManager.TrialState) {
        val oldState = currentTrialState
        Log.d(TAG, "updateTrialState called. Attempting to transition from $oldState to $newState")

        if (oldState == newState) {
            Log.d(TAG, "updateTrialState: New state ($newState) is the same as old state. No change to currentTrialState needed.")
            return
        }

        currentTrialState = newState
        Log.i(TAG, "updateTrialState: Trial state CHANGED from $oldState to $currentTrialState")

        // If the new state is definitive (ACTIVE, PURCHASED, EXPIRED),
        // and an InfoDialog was shown for a transient state (e.g., AWAITING), hide it.
        if (newState == TrialManager.TrialState.ACTIVE_INTERNET_TIME_CONFIRMED ||
            newState == TrialManager.TrialState.PURCHASED ||
            newState == TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED) {
            if (showTrialInfoDialog && (oldState == TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET)) { // Removed INTERNET_UNAVAILABLE from here
                Log.d(TAG, "updateTrialState: Transitioned to $newState from AWAITING. Hiding generic InfoDialog.")
                showTrialInfoDialog = false
                trialInfoMessage = ""
            }
        }
        // Logic for showing InfoDialog for NOT_YET_STARTED_AWAITING_INTERNET can be added here if needed,
        // or handled directly in setContent based on currentTrialState.
        // For now, we are not showing any InfoDialog for NOT_YET_STARTED_AWAITING_INTERNET either.
        // If you want one, you could set showTrialInfoDialog = true and a message here.
    }


    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        Log.i(TAG, "purchasesUpdatedListener: BillingResponseCode: ${billingResult.responseCode}, Message: ${billingResult.debugMessage}")
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase)
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            Toast.makeText(this, "Spendevorgang abgebrochen.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Fehler beim Spendevorgang: ${billingResult.debugMessage}", Toast.LENGTH_LONG).show()
        }
    }

    fun getCurrentApiKey(): String? {
        val key = if (::apiKeyManager.isInitialized) {
            apiKeyManager.getCurrentApiKey()
        } else {
            null
        }
        return key
    }

    internal fun refreshAccessibilityServiceStatus() {
        val service = packageName + "/" + ScreenOperatorAccessibilityService::class.java.canonicalName
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val isEnabled = enabledServices?.contains(service, ignoreCase = true) == true
        _isAccessibilityServiceEnabled.value = isEnabled
    }


    internal fun requestManageExternalStoragePermission() {
        // Dummy function
    }

    fun updateStatusMessage(message: String, isError: Boolean = false) {
        Toast.makeText(this, message, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun getPhotoReasoningViewModel(): PhotoReasoningViewModel? {
        return photoReasoningViewModel
    }

    fun setPhotoReasoningViewModel(viewModel: PhotoReasoningViewModel) {
        this.photoReasoningViewModel = viewModel
    }

    fun getAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        apiKeyManager = ApiKeyManager.getInstance(this)
        if (apiKeyManager.getCurrentApiKey().isNullOrEmpty()) {
             showApiKeyDialog = true
        }

        checkAndRequestPermissions()
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

        val initialTrialState = TrialManager.getTrialState(this, null)
        updateTrialState(initialTrialState)

        startTrialServiceIfNeeded()

        refreshAccessibilityServiceStatus()

        setContent {
            navController = rememberNavController()
            GenerativeAISample {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(navController)
                    if (showApiKeyDialog && currentTrialState != TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED) {
                        ApiKeyDialog(
                            apiKeyManager = apiKeyManager,
                            isFirstLaunch = apiKeyManager.getApiKeys().isEmpty(),
                            onDismiss = {
                                showApiKeyDialog = false
                            }
                        )
                    }
                    // The TrialExpiredDialog is directly controlled by currentTrialState
                    if (currentTrialState == TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED) {
                        TrialExpiredDialog(
                            onPurchaseClick = {
                                initiateDonationPurchase()
                            },
                            onDismiss = {
                                // Dialog is persistent
                            }
                        )
                    }
                    // --- MODIFICATION START: InfoDialog for INTERNET_UNAVAILABLE_CANNOT_VERIFY removed from here ---
                    // else if (showTrialInfoDialog &&
                    //          (currentTrialState == TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET ||
                    //           currentTrialState == TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY)) {
                    // --- MODIFICATION END ---
                    // If you still want an InfoDialog for NOT_YET_STARTED_AWAITING_INTERNET, that logic would go here.
                    // For example:
                    // else if (currentTrialState == TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET) {
                    //    InfoDialog(message = "Warte auf Internetverbindung zur Aktivierung der Testversion...", onDismiss = { /* usually not dismissible or handled by state change */ })
                    // }
                }
            }
        }
    }

    @Composable
    fun AppNavigation(navController: NavHostController) {
        val isAppEffectivelyUsable = currentTrialState != TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED
        val alwaysAvailableRoutes = listOf("ApiKeyDialog", "ChangeModel")

        NavHost(navController = navController, startDestination = "menu") {
            composable("menu") {
                MenuScreen(
                    onItemClicked = { routeId ->
                        if (alwaysAvailableRoutes.contains(routeId) || isAppEffectivelyUsable) {
                            if (routeId == "SHOW_API_KEY_DIALOG_ACTION") {
                                showApiKeyDialog = true
                            } else {
                                navController.navigate(routeId)
                            }
                        } else {
                            val message = if (currentTrialState == TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED) {
                                "Testzeitraum abgelaufen. Bitte abonnieren."
                            } else {
                                "Funktion nicht verfügbar im aktuellen Teststatus."
                            }
                            updateStatusMessage(message, isError = true)
                        }
                    },
                    onApiKeyButtonClicked = {
                        showApiKeyDialog = true
                    },
                    onDonationButtonClicked = {
                        initiateDonationPurchase()
                    },
                     isTrialExpired = currentTrialState == TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED
                )
            }
            composable("photo_reasoning") {
                if (isAppEffectivelyUsable) {
                    PhotoReasoningRoute()
                } else {
                    LaunchedEffect(Unit) {
                        navController.popBackStack()
                        updateStatusMessage("Testzeitraum abgelaufen. Zugriff auf Funktion gesperrt.", isError = true)
                    }
                }
            }
        }
    }

    private fun startTrialServiceIfNeeded() {
        Log.d(TAG, "startTrialServiceIfNeeded called. Current local trial state: $currentTrialState")
        if (currentTrialState != TrialManager.TrialState.PURCHASED &&
            currentTrialState != TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED) {
            Log.i(TAG, "Starting TrialTimerService because current local state is: $currentTrialState.")
            val serviceIntent = Intent(this, TrialTimerService::class.java)
            serviceIntent.action = TrialTimerService.ACTION_START_TIMER
            try {
                startService(serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "startTrialServiceIfNeeded: Failed to start TrialTimerService", e)
            }
        } else {
            Log.i(TAG, "TrialTimerService NOT started. Current local state: $currentTrialState.")
        }
    }

    private fun setupBillingClient() {
        Log.d(TAG, "setupBillingClient called.")
        if (::billingClient.isInitialized && billingClient.isReady) return
        if (::billingClient.isInitialized && billingClient.connectionState == BillingClient.ConnectionState.CONNECTING) return

        billingClient = BillingClient.newBuilder(this)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases()
            .build()

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProductDetails()
                    queryActiveSubscriptions()
                } else {
                    Log.e(TAG, "BillingClient setup failed: ${billingResult.debugMessage}")
                }
            }
            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "onBillingServiceDisconnected.")
            }
        })
    }

    private fun queryProductDetails() {
        if (!billingClient.isReady) return
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
            } else {
                Log.e(TAG, "Failed to query product details: ${billingResult.debugMessage}")
            }
        }
    }

    private fun initiateDonationPurchase() {
        if (!::billingClient.isInitialized || !billingClient.isReady) {
            updateStatusMessage("Bezahldienst nicht bereit.", true)
            if (::billingClient.isInitialized && (billingClient.connectionState == BillingClient.ConnectionState.CLOSED || billingClient.connectionState == BillingClient.ConnectionState.DISCONNECTED)){
                setupBillingClient()
            }
            return
        }
        if (monthlyDonationProductDetails == null) {
            updateStatusMessage("Spendeninformationen werden geladen.", true)
            queryProductDetails()
            return
        }

        monthlyDonationProductDetails?.let { productDetails ->
            val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
            if (offerToken == null) {
                updateStatusMessage("Spendenangebot nicht gefunden.", true); return@let
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
                updateStatusMessage("Fehler beim Starten des Spendevorgangs: ${billingResult.debugMessage}", true)
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (purchase.products.any { it == subscriptionProductId }) {
                if (!purchase.isAcknowledged) {
                    val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                    billingClient.acknowledgePurchase(acknowledgePurchaseParams) { ackResult ->
                        if (ackResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            updateStatusMessage("Vielen Dank für Ihr Abonnement!")
                            TrialManager.markAsPurchased(this)
                            updateTrialState(TrialManager.TrialState.PURCHASED)
                            val stopIntent = Intent(this, TrialTimerService::class.java).apply { action = TrialTimerService.ACTION_STOP_TIMER }
                            startService(stopIntent)
                        } else {
                            updateStatusMessage("Fehler beim Bestätigen des Kaufs: ${ackResult.debugMessage}", true)
                        }
                    }
                } else {
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
        if (!::billingClient.isInitialized || !billingClient.isReady) return

        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                var isSubscribed = false
                purchases.forEach { purchase ->
                    if (purchase.products.any { it == subscriptionProductId } && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        isSubscribed = true
                        handlePurchase(purchase) // Handles acknowledgement and state update
                        return@forEach
                    }
                }
                if (!isSubscribed && currentTrialState == TrialManager.TrialState.PURCHASED) {
                    // If Play Store says no active sub, but we thought it was purchased,
                    // we need to revert. This is complex and ideally involves server-side validation.
                    // For now, we'll re-evaluate local trial state.
                    Log.w(TAG, "queryActiveSubscriptions: No active sub found in Play, but local state was PURCHASED. Re-evaluating trial state.")
                    val freshState = TrialManager.getTrialState(this, null) // Get state without assuming purchase
                    updateTrialState(freshState)
                    startTrialServiceIfNeeded() // Restart trial service if appropriate based on freshState
                } else if (!isSubscribed) {
                    // No active subscription, ensure trial logic is active if not already expired/purchased locally
                     val freshNonPurchaseState = TrialManager.getTrialState(this, null)
                     if (freshNonPurchaseState != TrialManager.TrialState.PURCHASED) {
                         updateTrialState(freshNonPurchaseState)
                         startTrialServiceIfNeeded()
                     }
                }
                // If isSubscribed is true, handlePurchase should have set state to PURCHASED.
            } else {
                Log.e(TAG, "Failed to query active subscriptions: ${billingResult.debugMessage}")
                 val freshState = TrialManager.getTrialState(this, null)
                 if (freshState != TrialManager.TrialState.PURCHASED) {
                    updateTrialState(freshState)
                    startTrialServiceIfNeeded()
                 }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        instance = this
        refreshAccessibilityServiceStatus()

        if (::billingClient.isInitialized && billingClient.isReady) {
            queryActiveSubscriptions()
        } else if (::billingClient.isInitialized &&
                   (billingClient.connectionState == BillingClient.ConnectionState.DISCONNECTED ||
                    billingClient.connectionState == BillingClient.ConnectionState.CLOSED) ) {
            setupBillingClient()
        } else if (!::billingClient.isInitialized) {
            setupBillingClient()
        }

        val currentKnownState = TrialManager.getTrialState(this, null)
        updateTrialState(currentKnownState) // Ensure state is fresh
        startTrialServiceIfNeeded() // Manage service based on potentially updated state

    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(trialStatusReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "trialStatusReceiver was not registered or already unregistered.", e)
        }
        if (::billingClient.isInitialized && billingClient.isReady) {
            billingClient.endConnection()
        }
        if (this == instance) {
            instance = null
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest)
        } else {
            // Permissions already granted, ensure trial service is managed
            // startTrialServiceIfNeeded() // Already handled in onCreate/onResume
        }
    }

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            updateStatusMessage("Alle Berechtigungen erteilt.")
            startTrialServiceIfNeeded() // Manage service after permissions granted
        } else {
            updateStatusMessage("Einige Berechtigungen verweigert.", true)
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
    Dialog(onDismissRequest = {
        // This dialog should be persistent and not easily dismissible
        // onDismiss() // Calling this would make it dismissible
        Log.d("TrialExpiredDialog", "onDismissRequest called (persistent dialog)")
    }) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Testzeitraum abgelaufen", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Ihr 30-minütiger Testzeitraum ist beendet. Bitte abonnieren Sie die App, um sie weiterhin nutzen zu können.", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onPurchaseClick, modifier = Modifier.fillMaxWidth()) {
                    Text("Abonnieren")
                }
            }
        }
    }
}

@Composable
fun InfoDialog( // This dialog is now only for generic info, not internet status
    message: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Information", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(24.dp))
                TextButton(onClick = onDismiss) {
                    Text("OK")
                }
            }
        }
    }
}

