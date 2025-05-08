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
import com.google.ai.sample.ui.theme.GenerativeAISample
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var photoReasoningViewModel: com.google.ai.sample.feature.multimodal.PhotoReasoningViewModel? = null
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
                    updateTrialState(TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY)
                }
                TrialTimerService.ACTION_INTERNET_TIME_AVAILABLE -> {
                    val internetTime = intent.getLongExtra(TrialTimerService.EXTRA_CURRENT_UTC_TIME_MS, 0L)
                    Log.d(TAG, "Internet time available broadcast received: $internetTime")
                    if (internetTime > 0) {
                        // Re-evaluate state with the new internet time
                        val state = TrialManager.getTrialState(this@MainActivity, internetTime)
                        updateTrialState(state)
                        if (state == TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET) {
                            // This implies the service just started the trial
                            TrialManager.startTrialIfNecessaryWithInternetTime(this@MainActivity, internetTime)
                            updateTrialState(TrialManager.getTrialState(this@MainActivity, internetTime))
                        }
                    }
                }
            }
        }
    }

    private fun updateTrialState(newState: TrialManager.TrialState) {
        currentTrialState = newState
        Log.d(TAG, "Trial state updated to: $currentTrialState")
        when (currentTrialState) {
            TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET -> {
                trialInfoMessage = "Warte auf Internetverbindung zur Verifizierung der Testzeit..."
                showTrialInfoDialog = true // Show a non-blocking info dialog or a banner
            }
            TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY -> {
                trialInfoMessage = "Testzeit kann nicht verifiziert werden. Bitte Internetverbindung prüfen."
                showTrialInfoDialog = true // Show a non-blocking info dialog or a banner
            }
            TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED -> {
                trialInfoMessage = "Ihr 30-minütiger Testzeitraum ist beendet. Bitte abonnieren Sie die App, um sie weiterhin nutzen zu können."
                showTrialInfoDialog = true // This will trigger the persistent dialog
            }
            TrialManager.TrialState.ACTIVE_INTERNET_TIME_CONFIRMED,
            TrialManager.TrialState.PURCHASED -> {
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

    fun getPhotoReasoningViewModel(): com.google.ai.sample.feature.multimodal.PhotoReasoningViewModel? {
        return photoReasoningViewModel
    }

    fun setPhotoReasoningViewModel(viewModel: com.google.ai.sample.feature.multimodal.PhotoReasoningViewModel) {
        photoReasoningViewModel = viewModel
    }

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.POST_NOTIFICATIONS // For foreground service notifications if used
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
            startTrialServiceIfNeeded()
        } else {
            Log.d(TAG, "Some permissions denied")
            Toast.makeText(this, "Einige Berechtigungen wurden verweigert. Die App benötigt diese für volle Funktionalität.", Toast.LENGTH_LONG).show()
            // Handle specific permission denials if necessary
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        Log.d(TAG, "onCreate: Setting MainActivity instance")

        apiKeyManager = ApiKeyManager.getInstance(this)
        val apiKey = apiKeyManager.getCurrentApiKey()
        if (apiKey.isNullOrEmpty()) {
            showApiKeyDialog = true
        }

        checkAndRequestPermissions()
        checkAccessibilityServiceEnabled()
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

        // Initial check of trial state without internet time (will likely be INTERNET_UNAVAILABLE or NOT_YET_STARTED)
        updateTrialState(TrialManager.getTrialState(this, null))
        startTrialServiceIfNeeded()

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
                                if (apiKeyManager.getApiKeys().isNotEmpty()) {
                                    // Consider if recreate() is still needed
                                }
                            }
                        )
                    }
                    // Handle different trial states with dialogs
                    when (currentTrialState) {
                        TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED -> {
                            TrialExpiredDialog(
                                onPurchaseClick = { initiateDonationPurchase() },
                                onDismiss = { /* Persistent dialog, dismiss does nothing or closes app */ }
                            )
                        }
                        TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET,
                        TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY -> {
                            if (showTrialInfoDialog) { // Show a less intrusive dialog/banner for these states
                                InfoDialog(message = trialInfoMessage, onDismiss = { showTrialInfoDialog = false })
                            }
                        }
                        else -> { /* ACTIVE or PURCHASED, no special dialog needed here */ }
                    }
                }
            }
        }
    }

    @Composable
    fun AppNavigation(navController: NavHostController) {
        val isAppUsable = currentTrialState == TrialManager.TrialState.ACTIVE_INTERNET_TIME_CONFIRMED ||
                          currentTrialState == TrialManager.TrialState.PURCHASED

        NavHost(navController = navController, startDestination = "menu") {
            composable("menu") {
                MenuScreen(
                    onItemClicked = { routeId ->
                        if (isAppUsable) {
                            navController.navigate(routeId)
                        } else {
                            Toast.makeText(this@MainActivity, trialInfoMessage, Toast.LENGTH_LONG).show()
                        }
                    },
                    onApiKeyButtonClicked = {
                        if (isAppUsable) {
                            showApiKeyDialog = true
                        } else {
                            Toast.makeText(this@MainActivity, trialInfoMessage, Toast.LENGTH_LONG).show()
                        }
                    },
                    onDonationButtonClicked = { initiateDonationPurchase() },
                    isTrialExpired = (currentTrialState == TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED) ||
                                     (currentTrialState == TrialManager.TrialState.NOT_YET_STARTED_AWAITING_INTERNET) ||
                                     (currentTrialState == TrialManager.TrialState.INTERNET_UNAVAILABLE_CANNOT_VERIFY)
                )
            }
            composable("photo_reasoning") {
                if (isAppUsable) {
                    PhotoReasoningRoute()
                } else {
                    LaunchedEffect(Unit) {
                        navController.popBackStack()
                        Toast.makeText(this@MainActivity, trialInfoMessage, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun startTrialServiceIfNeeded() {
        if (currentTrialState != TrialManager.TrialState.PURCHASED && currentTrialState != TrialManager.TrialState.EXPIRED_INTERNET_TIME_CONFIRMED) {
            Log.d(TAG, "Starting TrialTimerService.")
            val serviceIntent = Intent(this, TrialTimerService::class.java)
            serviceIntent.action = TrialTimerService.ACTION_START_TIMER
            startService(serviceIntent)
        } else {
            Log.d(TAG, "Trial service not started. State: $currentTrialState")
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
                    queryActiveSubscriptions() // Check for existing purchases
                } else {
                    Log.e(TAG, "BillingClient setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "BillingClient service disconnected.")
                // Consider a retry policy
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
            Toast.makeText(this, "Bezahldienst nicht bereit. Bitte später versuchen.", Toast.LENGTH_SHORT).show()
            if (billingClient.connectionState == BillingClient.ConnectionState.CLOSED || billingClient.connectionState == BillingClient.ConnectionState.DISCONNECTED){
                setupBillingClient()
            }
            return
        }
        if (monthlyDonationProductDetails == null) {
            Log.e(TAG, "Product details not loaded yet.")
            Toast.makeText(this, "Spendeninformationen werden geladen. Bitte kurz warten und erneut versuchen.", Toast.LENGTH_LONG).show()
            queryProductDetails()
            return
        }
        monthlyDonationProductDetails?.let { productDetails ->
            val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
            if (offerToken == null) {
                Log.e(TAG, "No offer token found for product: ${productDetails.productId}")
                Toast.makeText(this, "Spendenangebot nicht gefunden.", Toast.LENGTH_LONG).show()
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
            }
        } ?: run {
            Log.e(TAG, "Donation product details are null.")
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
                            Toast.makeText(this, "Vielen Dank für Ihr Abonnement!", Toast.LENGTH_LONG).show()
                            TrialManager.markAsPurchased(this)
                            updateTrialState(TrialManager.TrialState.PURCHASED)
                            val stopIntent = Intent(this, TrialTimerService::class.java)
                            stopIntent.action = TrialTimerService.ACTION_STOP_TIMER
                            startService(stopIntent) // Stop the service
                        } else {
                            Log.e(TAG, "Failed to acknowledge purchase: ${ackBillingResult.debugMessage}")
                        }
                    }
                } else {
                    Log.d(TAG, "Subscription already acknowledged.")
                    Toast.makeText(this, "Abonnement bereits aktiv.", Toast.LENGTH_LONG).show()
                    TrialManager.markAsPurchased(this) // Ensure state is correct
                    updateTrialState(TrialManager.TrialState.PURCHASED)
                }
            }
        } else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            Toast.makeText(this, "Ihre Zahlung ist in Bearbeitung.", Toast.LENGTH_LONG).show()
        }
    }

    private fun queryActiveSubscriptions() {
        if (!billingClient.isReady) return
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                var isSubscribed = false
                purchases.forEach { purchase ->
                    if (purchase.products.contains(subscriptionProductId) && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        isSubscribed = true
                        if (!purchase.isAcknowledged) handlePurchase(purchase) // Acknowledge if needed
                    }
                }
                if (isSubscribed) {
                    Log.d(TAG, "User has an active subscription.")
                    TrialManager.markAsPurchased(this)
                    updateTrialState(TrialManager.TrialState.PURCHASED)
                    val stopIntent = Intent(this, TrialTimerService::class.java)
                    stopIntent.action = TrialTimerService.ACTION_STOP_TIMER
                    startService(stopIntent) // Stop service if already purchased
                } else {
                    Log.d(TAG, "User has no active subscription. Trial logic will apply.")
                    // If not purchased, ensure trial state is checked and service started if needed
                    updateTrialState(TrialManager.getTrialState(this, null)) // Re-check with null initially
                    startTrialServiceIfNeeded()
                }
            } else {
                Log.e(TAG, "Failed to query active subscriptions: ${billingResult.debugMessage}")
                // Fallback: if query fails, still check local trial status and start service
                updateTrialState(TrialManager.getTrialState(this, null))
                startTrialServiceIfNeeded()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        instance = this
        checkAccessibilityServiceEnabled()
        if (::billingClient.isInitialized && billingClient.isReady) {
            queryActiveSubscriptions() // This will also trigger trial state updates
        } else {
            // If billing client not ready, still update trial state based on local info and start service
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
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest)
        } else {
            // Permissions already granted, ensure service starts if needed
            startTrialServiceIfNeeded()
        }
    }

    // Made internal to be accessible from other classes in the same module
    internal fun checkAccessibilityServiceEnabled() {
        // Dummy implementation
        Log.d(TAG, "Checking accessibility service (dummy check).")
        // Consider providing a real implementation or a way for other classes to know the status
    }

    // Made internal to be accessible from other classes in the same module
    internal fun requestManageExternalStoragePermission() {
        // Dummy implementation
        Log.d(TAG, "Requesting manage external storage permission (dummy).")
        // Consider providing a real implementation
    }

    // Added to provide API key to other classes like ViewModels
    fun getCurrentApiKey(): String? {
        return if (::apiKeyManager.isInitialized) {
            apiKeyManager.getCurrentApiKey()
        } else {
            null
        }
    }

    // Added to allow other classes to show messages to the user via MainActivity
    fun updateStatusMessage(message: String) {
        // Displaying as a Toast for now, can be changed to Snackbar or other UI element
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.d(TAG, "Status Message Updated: $message")
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
    Dialog(onDismissRequest = onDismiss) {
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
                    text = "Information",
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

