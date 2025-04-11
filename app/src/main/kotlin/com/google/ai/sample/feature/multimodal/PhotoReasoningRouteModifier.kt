package com.google.ai.sample.feature.multimodal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ai.sample.GenerativeViewModelFactory
import com.google.ai.sample.MainActivity

/**
 * Modified PhotoReasoningRoute that ensures the ViewModel is shared with MainActivity
 * This file should be used to replace the content in PhotoReasoningScreen.kt where
 * the PhotoReasoningRoute composable is defined
 */
@Composable
internal fun PhotoReasoningRoute() {
    // Get the current context
    val context = LocalContext.current
    
    // Create the ViewModel using the factory
    val viewModel: PhotoReasoningViewModel = viewModel(factory = GenerativeViewModelFactory)
    
    // Get the MainActivity instance from the context
    val mainActivity = context as? MainActivity
    
    // Share the ViewModel with MainActivity for AccessibilityService access
    DisposableEffect(viewModel) {
        // Set the ViewModel in MainActivity when the composable is first composed
        mainActivity?.setPhotoReasoningViewModel(viewModel)
        
        // When the composable is disposed, clear the reference if needed
        onDispose {
            // Optional: clear the reference when navigating away
            // mainActivity?.clearPhotoReasoningViewModel()
        }
    }
    
    // Use the existing PhotoReasoningScreen with the ViewModel
    val photoReasoningUiState = viewModel.uiState.collectAsState().value
    
    PhotoReasoningScreen(
        uiState = photoReasoningUiState,
        onReasonClicked = { inputText, selectedItems ->
            // Process images and send to AI
            processImagesAndReason(viewModel, inputText, selectedItems)
        }
    )
}

// Helper function to process images and send to AI
private fun processImagesAndReason(
    viewModel: PhotoReasoningViewModel,
    inputText: String,
    selectedItems: List<android.net.Uri>
) {
    // This code should match the existing implementation in PhotoReasoningScreen.kt
    androidx.compose.runtime.rememberCoroutineScope().launch {
        android.util.Log.d("PhotoReasoningScreen", "Go button clicked, processing images")
        
        // Process all selected images
        val bitmaps = selectedItems.mapNotNull {
            android.util.Log.d("PhotoReasoningScreen", "Processing image: $it")
            val imageRequestBuilder = coil.request.ImageRequest.Builder(LocalContext.current)
            val imageLoader = coil.ImageLoader.Builder(LocalContext.current).build()
            
            val imageRequest = imageRequestBuilder
                .data(it)
                .precision(coil.size.Precision.EXACT)
                .build()
            try {
                val result = imageLoader.execute(imageRequest)
                if (result is coil.request.SuccessResult) {
                    android.util.Log.d("PhotoReasoningScreen", "Successfully processed image")
                    return@mapNotNull (result.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                } else {
                    android.util.Log.e("PhotoReasoningScreen", "Failed to process image: result is not SuccessResult")
                    return@mapNotNull null
                }
            } catch (e: Exception) {
                android.util.Log.e("PhotoReasoningScreen", "Error processing image: ${e.message}")
                return@mapNotNull null
            }
        }
        
        android.util.Log.d("PhotoReasoningScreen", "Processed ${bitmaps.size} images")
        
        // Send to AI
        viewModel.reason(inputText, bitmaps)
    }
}
