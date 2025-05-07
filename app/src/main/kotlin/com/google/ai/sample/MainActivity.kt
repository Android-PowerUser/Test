package com.google.ai.sample

import android.Manifest
import android.app.Activity
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
        checkAccessibilityServiceEnabled()

        // Initialize BillingClient
        setupBillingClient()

        setContent {
            GenerativeAISample {
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
                                },
                                onDonationButtonClicked = { // Handle donation button click
                                    initiateDonationPurchase()
                                }
                            )
                        }
                        composable("photo_reasoning") {
                            PhotoReasoningRoute()
                        }
                    }
                    if (showApiKeyDialog) {
                        ApiKeyDialog(
                            apiKeyManager = apiKeyManager,
                            isFirstLaunch = apiKeyManager.getApiKeys().isEmpty(),
                            onDismiss = {
                                showApiKeyDialog = false
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

    private fun setupBillingClient() {
        billingClient = BillingClient.newBuilder(this)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases() // Required for subscriptions and other pending transactions
            .build()

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "BillingClient setup successful.")
                    // Query for product details once setup is complete
                    queryProductDetails()
                    // Query for existing purchases
                    queryActiveSubscriptions()
                } else {
                    Log.e(TAG, "BillingClient setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "BillingClient service disconnected. Retrying...")
                // Try to restart the connection on the next request to Google Play by calling the startConnection() method.
                // You can implement a retry policy here.
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
            // Optionally, try to reconnect or inform the user
            if (billingClient.connectionState == BillingClient.ConnectionState.CLOSED || billingClient.connectionState == BillingClient.ConnectionState.DISCONNECTED){
                setupBillingClient() // Attempt to reconnect
            }
            return
        }

        if (monthlyDonationProductDetails == null) {
            Log.e(TAG, "Product details not loaded yet. Attempting to query again.")
            Toast.makeText(this, "Spendeninformationen werden geladen. Bitte kurz warten und erneut versuchen.", Toast.LENGTH_LONG).show()
            queryProductDetails() // Try to load them again
            return
        }

        monthlyDonationProductDetails?.let { productDetails ->
            // Ensure there's a subscription offer token. For basic subscriptions, it's usually the first one.
            val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
            if (offerToken == null) {
                Log.e(TAG, "No offer token found for product: ${productDetails.productId}")
                Toast.makeText(this, "Spendenangebot nicht gefunden.", Toast.LENGTH_LONG).show()
                return
            }

            val productDetailsParamsList = listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .setOfferToken(offerToken) // Required for subscriptions
                    .build()
            )

            val billingFlowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(productDetailsParamsList)
                .build()

            val billingResult = billingClient.launchBillingFlow(this as Activity, billingFlowParams)
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
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.d(TAG, "Purchase acknowledged successfully for ${purchase.products.joinToString()}")
                        Toast.makeText(this, "Vielen Dank für Ihre Spende!", Toast.LENGTH_LONG).show()
                        // Grant entitlement or update UI for the donation here
                    } else {
                        Log.e(TAG, "Failed to acknowledge purchase: ${billingResult.debugMessage}")
                    }
                }
            } else {
                // Purchase already acknowledged
                Log.d(TAG, "Purchase already acknowledged for ${purchase.products.joinToString()}")
                Toast.makeText(this, "Spende bereits erhalten. Vielen Dank!", Toast.LENGTH_LONG).show()
            }
        } else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            Log.d(TAG, "Purchase is pending for ${purchase.products.joinToString()}. Please complete the transaction.")
            Toast.makeText(this, "Ihre Spende ist in Bearbeitung.", Toast.LENGTH_LONG).show()
        } else if (purchase.purchaseState == Purchase.PurchaseState.UNSPECIFIED_STATE) {
            Log.e(TAG, "Purchase in unspecified state for ${purchase.products.joinToString()}")
            Toast.makeText(this, "Unbekannter Status für Ihre Spende.", Toast.LENGTH_LONG).show()
        }
        // It's crucial to also implement server-side validation for purchases, especially for subscriptions.
        // This client-side handling is for immediate feedback and basic entitlement.
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
                purchases.forEach { purchase ->
                    if (purchase.products.contains(subscriptionProductId) && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        Log.d(TAG, "User has an active donation subscription: $subscriptionProductId")
                        // Potentially update UI to reflect active donation status
                        // If not acknowledged, handle it
                        if (!purchase.isAcknowledged) {
                            handlePurchase(purchase)
                        }
                    }
                }
            } else {
                Log.e(TAG, "Failed to query active subscriptions: ${billingResult.debugMessage}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        instance = this
        Log.d(TAG, "onResume: Setting MainActivity instance")
        checkAccessibilityServiceEnabled()
        // Query purchases when the app resumes, in case of purchases made outside the app.
        if (::billingClient.isInitialized && billingClient.isReady) {
            queryActiveSubscriptions()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            Log.d(TAG, "onDestroy: Clearing MainActivity instance")
            instance = null
        }
        if (::billingClient.isInitialized && billingClient.isReady) {
            Log.d(TAG, "Closing BillingClient connection.")
            billingClient.endConnection()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d(TAG, "All permissions already granted")
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

    fun checkAccessibilityServiceEnabled() {
        val isEnabled = ScreenOperatorAccessibilityService.isAccessibilityServiceEnabled(this)
        Log.d(TAG, "Accessibility service enabled: $isEnabled")
        if (!isEnabled) {
            Toast.makeText(
                this,
                "Bitte aktivieren Sie den Accessibility Service für die Klick-Funktionalität",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }

    fun updateStatusMessage(message: String, isError: Boolean) {
        runOnUiThread {
            val duration = if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            Toast.makeText(this, message, duration).show()
            Log.d(TAG, "Status message: $message, isError: $isError")
        }
    }

    fun areAllPermissionsGranted(): Boolean {
        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    fun showApiKeyDialog() {
        showApiKeyDialog = true
    }

    fun getCurrentApiKey(): String? {
        return apiKeyManager.getCurrentApiKey()
    }

    companion object {
        private const val TAG = "MainActivityBilling"
        @Volatile
        private var instance: MainActivity? = null
        fun getInstance(): MainActivity? {
            Log.d(TAG, "getInstance called, returning: ${instance != null}")
            return instance
        }
    }

    // onPause is intentionally left as is to keep MainActivity instance for accessibility service
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: Keeping MainActivity instance")
    }
}

