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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
    private val subscriptionProductId = "donation_monthly_2_90_eur" // IMPORTANT: Replace with your actual Product ID from Google Play Console

    private var showTrialExpiredDialog by mutableStateOf(false)
    private lateinit var navController: NavHostController

    private val trialExpiredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TrialTimerService.ACTION_TRIAL_EXPIRED) {
                Log.d(TAG, "Received ACTION_TRIAL_EXPIRED broadcast.")
                checkTrialStatusAndShowDialog()
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
        Log.d(TAG, "getPhotoReasoningViewModel called, returning: ${photoReasoningViewModel != null}")
        return photoReasoningViewModel
    }

    fun setPhotoReasoningViewModel(viewModel: com.google.ai.sample.feature.multimodal.PhotoReasoningViewModel) {
        Log.d(TAG, "setPhotoReasoningViewModel called with viewModel: $viewModel")
        photoReasoningViewModel = viewModel
    }

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.POST_NOTIFICATIONS // Required for services in foreground on Android 13+
        )
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
            // POST_NOTIFICATIONS is not needed for foreground services before Android 13 in the same way
        )
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.d(TAG, "All permissions granted")
            Toast.makeText(this, "Alle Berechtigungen erteilt", Toast.LENGTH_SHORT).show()
            // After permissions are granted, re-check trial status and start service if needed
            checkTrialStatusAndShowDialog()
        } else {
            Log.d(TAG, "Some permissions denied")
            Toast.makeText(this, "Einige Berechtigungen wurden verweigert. Die App benötigt diese für volle Funktionalität.", Toast.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !permissions[Manifest.permission.READ_EXTERNAL_STORAGE]!!) {
                requestManageExternalStoragePermission()
            }
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
            Log.d(TAG, "No API key found, showing dialog")
        } else {
            Log.d(TAG, "API key found: ${apiKey.take(5)}...")
        }

        checkAndRequestPermissions()
        checkAccessibilityServiceEnabled() // Assuming this is a pre-existing check

        // Initialize BillingClient
        setupBillingClient()

        // Register broadcast receiver for trial expiration
        val intentFilter = IntentFilter(TrialTimerService.ACTION_TRIAL_EXPIRED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trialExpiredReceiver, intentFilter, RECEIVER_NOT_EXPORTED) // More secure for Android 13+
        } else {
            registerReceiver(trialExpiredReceiver, intentFilter)
        }

        // Check trial status on create
        checkTrialStatusAndShowDialog()

        setContent {
            navController = rememberNavController()
            GenerativeAISample {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(navController)
                    if (showApiKeyDialog) {
                        ApiKeyDialog(
                            apiKeyManager = apiKeyManager,
                            isFirstLaunch = apiKeyManager.getApiKeys().isEmpty(),
                            onDismiss = {
                                showApiKeyDialog = false
                                if (apiKeyManager.getApiKeys().isNotEmpty()) {
                                    // Consider if recreate() is still needed or if a recomposition/state update is enough
                                }
                            }
                        )
                    }
                    if (showTrialExpiredDialog) {
                        TrialExpiredDialog(
                            onPurchaseClick = {
                                initiateDonationPurchase() // Or your specific purchase logic
                            },
                            onDismiss = {
                                // The dialog is persistent, so dismiss might not be an option
                                // or it could minimize the app, or just stay there.
                                // For now, let's keep it showing.
                                Log.d(TAG, "TrialExpiredDialog dismiss attempted, but it's persistent.")
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun AppNavigation(navController: NavHostController) {
        NavHost(navController = navController, startDestination = "menu") {
            composable("menu") {
                MenuScreen(
                    onItemClicked = { routeId ->
                        if (!showTrialExpiredDialog) {
                            navController.navigate(routeId)
                        } else {
                            Toast.makeText(this@MainActivity, "Bitte abonnieren Sie die App, um fortzufahren.", Toast.LENGTH_LONG).show()
                        }
                    },
                    onApiKeyButtonClicked = {
                        if (!showTrialExpiredDialog) {
                            showApiKeyDialog = true
                        } else {
                            Toast.makeText(this@MainActivity, "Bitte abonnieren Sie die App, um fortzufahren.", Toast.LENGTH_LONG).show()
                        }
                    },
                    onDonationButtonClicked = { // Handle donation button click
                        initiateDonationPurchase()
                    },
                    isTrialExpired = showTrialExpiredDialog // Pass trial status to MenuScreen
                )
            }
            composable("photo_reasoning") {
                // Potentially block access if trial is expired and dialog is shown
                if (showTrialExpiredDialog) {
                    // Redirect to menu or show a message, prevent access to feature
                    LaunchedEffect(Unit) { // Use LaunchedEffect for navigation from composable
                        navController.popBackStack() // Go back to menu
                        Toast.makeText(this@MainActivity, "Testzeitraum abgelaufen. Bitte abonnieren.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    PhotoReasoningRoute()
                }
            }
        }
    }


    private fun checkTrialStatusAndShowDialog() {
        if (TrialManager.isTrialExpired(this)) {
            Log.d(TAG, "Trial is expired. Showing dialog.")
            showTrialExpiredDialog = true
            // Stop the service if it's running and trial is now confirmed expired
            val stopIntent = Intent(this, TrialTimerService::class.java)
            stopIntent.action = TrialTimerService.ACTION_STOP_TIMER
            startService(stopIntent)
        } else {
            showTrialExpiredDialog = false
            TrialManager.startTrialIfNecessary(this)
            if (TrialManager.isTrialStarted(this)) {
                Log.d(TAG, "Trial started or ongoing. Starting TrialTimerService.")
                val serviceIntent = Intent(this, TrialTimerService::class.java)
                serviceIntent.action = TrialTimerService.ACTION_START_TIMER
                startService(serviceIntent)
            } else {
                 Log.d(TAG, "Trial not started and not expired (e.g. fresh install, no KeyStore entry).")
            }
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
                    queryActiveSubscriptions() // Check for existing purchases on setup
                } else {
                    Log.e(TAG, "BillingClient setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "BillingClient service disconnected. Retrying...")
                // setupBillingClient() // Consider a retry policy
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
                if (monthlyDonationProductDetails == null) {
                     Log.e(TAG, "Product details not found for $subscriptionProductId")
                } else {
                    Log.d(TAG, "Product details loaded: ${monthlyDonationProductDetails?.name}")
                }
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
                setupBillingClient() // Attempt to reconnect
            }
            return
        }

        if (monthlyDonationProductDetails == null) {
            Log.e(TAG, "Product details not loaded yet. Attempting to query again.")
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
                Toast.makeText(this, "Fehler beim Starten des Spendevorgangs: ${billingResult.debugMessage}", Toast.LENGTH_LONG).show()
            }
        } ?: run {
            Log.e(TAG, "Donation product details are null, cannot launch purchase flow.")
            Toast.makeText(this, "Spendenprodukt nicht verfügbar.", Toast.LENGTH_LONG).show()
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (purchase.products.contains(subscriptionProductId)) { // Check if it's the trial-unlocking product
                if (!purchase.isAcknowledged) {
                    val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                    billingClient.acknowledgePurchase(acknowledgePurchaseParams) { ackBillingResult ->
                        if (ackBillingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            Log.d(TAG, "Purchase acknowledged successfully for ${purchase.products.joinToString()}")
                            Toast.makeText(this, "Vielen Dank für Ihr Abonnement!", Toast.LENGTH_LONG).show()
                            TrialManager.markAsPurchased(this) // Mark trial as purchased
                            showTrialExpiredDialog = false // Hide the dialog
                            // Stop the service as it's no longer needed for trial timing
                            val stopIntent = Intent(this, TrialTimerService::class.java)
                            stopIntent.action = TrialTimerService.ACTION_STOP_TIMER
                            startService(stopIntent)
                            // Potentially navigate user or refresh UI
                            if (::navController.isInitialized) {
                                navController.popBackStack("menu", inclusive = false)
                                navController.navigate("menu") // Refresh menu screen
                            }
                        } else {
                            Log.e(TAG, "Failed to acknowledge purchase: ${ackBillingResult.debugMessage}")
                        }
                    }
                } else {
                    Log.d(TAG, "Purchase already acknowledged for ${purchase.products.joinToString()}")
                    Toast.makeText(this, "Abonnement bereits aktiv.", Toast.LENGTH_LONG).show()
                    TrialManager.markAsPurchased(this) // Ensure state is correct even if already acknowledged
                    showTrialExpiredDialog = false
                }
            } else {
                // Handle other product purchases if any
                Log.d(TAG, "Purchase of a different product: ${purchase.products.joinToString()}")
            }
        } else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            Log.d(TAG, "Purchase is pending for ${purchase.products.joinToString()}. Please complete the transaction.")
            Toast.makeText(this, "Ihre Zahlung ist in Bearbeitung.", Toast.LENGTH_LONG).show()
        } else if (purchase.purchaseState == Purchase.PurchaseState.UNSPECIFIED_STATE) {
            Log.e(TAG, "Purchase in unspecified state for ${purchase.products.joinToString()}")
            Toast.makeText(this, "Unbekannter Status für Ihre Zahlung.", Toast.LENGTH_LONG).show()
        }
    }

    private fun queryActiveSubscriptions() {
        if (!billingClient.isReady) {
            Log.e(TAG, "queryActiveSubscriptions: BillingClient not ready.")
            return
        }
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                var isSubscribed = false
                purchases.forEach { purchase ->
                    if (purchase.products.contains(subscriptionProductId) && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        Log.d(TAG, "User has an active subscription: $subscriptionProductId")
                        isSubscribed = true
                        TrialManager.markAsPurchased(this) // Mark as purchased if active sub found
                        showTrialExpiredDialog = false
                        if (!purchase.isAcknowledged) {
                            handlePurchase(purchase) // Acknowledge if necessary
                        }
                    }
                }
                if (isSubscribed) {
                    Log.d(TAG, "User is subscribed. Trial logic will be bypassed.")
                } else {
                    Log.d(TAG, "User is not subscribed. Proceeding with trial check.")
                    // If no active subscription, ensure trial status is checked correctly
                    checkTrialStatusAndShowDialog()
                }
            } else {
                Log.e(TAG, "Failed to query active subscriptions: ${billingResult.debugMessage}")
                // If query fails, still check local trial status as a fallback
                 checkTrialStatusAndShowDialog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        instance = this
        Log.d(TAG, "onResume: Setting MainActivity instance")
        checkAccessibilityServiceEnabled()
        if (::billingClient.isInitialized && billingClient.isReady) {
            queryActiveSubscriptions() // Re-check subscription status and then trial status
        } else {
            checkTrialStatusAndShowDialog() // Fallback if billing client not ready
        }
    }

    override fun onPause() {
        super.onPause()
        // Consider if service should be stopped or if it should continue running in background
        // As per requirement, it should run in background, so we don't stop it here.
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(trialExpiredReceiver)
        // Stop the service when the activity is destroyed if it's not meant to run indefinitely
        // However, for a trial timer that needs to persist, this might not be desired unless app is fully closed.
        // The service will stop itself when timer expires or is explicitly stopped.
        if (::billingClient.isInitialized) {
            billingClient.endConnection()
        }
        if (this == instance) {
            instance = null
            Log.d(TAG, "onDestroy: Clearing MainActivity instance")
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: ${permissionsToRequest.joinToString()}")
            requestPermissionLauncher.launch(permissionsToRequest)
        } else {
            Log.d(TAG, "All required permissions already granted.")
            // If permissions are already granted, proceed to check trial status
            // This is important if onCreate is called again after permissions were granted previously
            checkTrialStatusAndShowDialog()
        }
    }

    private fun checkAccessibilityServiceEnabled() {
        // Dummy implementation, replace with actual check if needed
        Log.d(TAG, "Checking accessibility service (dummy check).")
        // val service = "${packageName}/.YourAccessibilityService"
        // try {
        //     val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        //     if (enabledServices?.contains(service) == true) {
        //         Log.d(TAG, "Accessibility Service is enabled.")
        //     } else {
        //         Log.d(TAG, "Accessibility Service is NOT enabled. Requesting user to enable.")
        //         Toast.makeText(this, "Bitte aktivieren Sie den Accessibility Service für diese App.", Toast.LENGTH_LONG).show()
        //         startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        //     }
        // } catch (e: Exception) {
        //     Log.e(TAG, "Error checking accessibility service", e)
        // }
    }

    private fun requestManageExternalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                startActivity(intent)
                Toast.makeText(this, "Bitte erteilen Sie die Berechtigung zur Dateiverwaltung.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting manage external storage permission", e)
                val intent = Intent()
                intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                startActivity(intent)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private var instance: MainActivity? = null
        fun getInstance(): MainActivity? {
            Log.d(TAG, "getInstance called, instance is ${if (instance == null) "null" else "not null"}")
            return instance
        }
    }
}

@Composable
fun TrialExpiredDialog(
    onPurchaseClick: () -> Unit,
    onDismiss: () -> Unit // Though persistent, a dismiss action might be needed for specific scenarios
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) { // onDismissRequest is mandatory for Dialog
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
                // Optional: Add a button to close the app or a non-functional dismiss if truly persistent
                // TextButton(onClick = { (context as? Activity)?.finish() }) {
                //     Text("App schließen")
                // }
            }
        }
    }
}


