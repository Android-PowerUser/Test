package com.google.ai.sample.feature.multimodal

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.Settings
import android.widget.Toast // Added for Toast message
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.ai.sample.GenerativeViewModelFactory
import com.google.ai.sample.MainActivity
import coil.size.Precision
import com.google.ai.sample.R
import com.google.ai.sample.ScreenOperatorAccessibilityService
import com.google.ai.sample.ui.theme.GenerativeAISample
import com.google.ai.sample.util.Command
import com.google.ai.sample.util.UriSaver
import kotlinx.coroutines.launch
import android.util.Log

@Composable
internal fun PhotoReasoningRoute(
    viewModel: PhotoReasoningViewModel = viewModel(factory = GenerativeViewModelFactory)
) {
    val photoReasoningUiState by viewModel.uiState.collectAsState()
    val commandExecutionStatus by viewModel.commandExecutionStatus.collectAsState()
    val detectedCommands by viewModel.detectedCommands.collectAsState()
    val systemMessage by viewModel.systemMessage.collectAsState()
    val chatMessages by viewModel.chatMessagesFlow.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val imageRequestBuilder = ImageRequest.Builder(LocalContext.current)
    val imageLoader = ImageLoader.Builder(LocalContext.current).build()
    val context = LocalContext.current
    val mainActivity = context as? MainActivity

    // Observe the accessibility service status from MainActivity
    val isAccessibilityServiceEffectivelyEnabled by mainActivity?.isAccessibilityServiceEnabledFlow?.collectAsState() ?: mutableStateOf(false)
    val isKeyboardOpen by mainActivity?.isKeyboardOpen?.collectAsState() ?: mutableStateOf(false)

    // Launcher for opening accessibility settings
    val accessibilitySettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // This block is called when the settings activity returns.
        // Re-check the accessibility service status in MainActivity, which will update the flow.
        Log.d("PhotoReasoningRoute", "Returned from Accessibility Settings. Refreshing status.")
        mainActivity?.refreshAccessibilityServiceStatus()
    }

    DisposableEffect(viewModel, mainActivity) {
        mainActivity?.setPhotoReasoningViewModel(viewModel)
        Log.d("PhotoReasoningRoute", "ViewModel shared with MainActivity: ${mainActivity != null}")

        // Initial check of accessibility service status when the composable enters
        mainActivity?.refreshAccessibilityServiceStatus()
        viewModel.loadSystemMessage(context)

        onDispose {
            // Optional: clear the reference when navigating away
            // mainActivity?.clearPhotoReasoningViewModel()
        }
    }

    PhotoReasoningScreen(
        uiState = photoReasoningUiState,
        commandExecutionStatus = commandExecutionStatus,
        detectedCommands = detectedCommands,
        systemMessage = systemMessage,
        chatMessages = chatMessages,
        onSystemMessageChanged = { message ->
            viewModel.updateSystemMessage(message, context)
        },
        onReasonClicked = { inputText, selectedItems ->
            coroutineScope.launch {
                Log.d("PhotoReasoningScreen", "Go button clicked, processing images")
                val bitmaps = selectedItems.mapNotNull {
                    Log.d("PhotoReasoningScreen", "Processing image: $it")
                    val imageRequest = imageRequestBuilder
                        .data(it)
                        .precision(Precision.EXACT)
                        .build()
                    try {
                        val result = imageLoader.execute(imageRequest)
                        if (result is SuccessResult) {
                            Log.d("PhotoReasoningScreen", "Successfully processed image")
                            return@mapNotNull (result.drawable as BitmapDrawable).bitmap
                        } else {
                            Log.e("PhotoReasoningScreen", "Failed to process image: result is not SuccessResult")
                            return@mapNotNull null
                        }
                    } catch (e: Exception) {
                        Log.e("PhotoReasoningScreen", "Error processing image: ${e.message}")
                        return@mapNotNull null
                    }
                }
                Log.d("PhotoReasoningScreen", "Processed ${bitmaps.size} images")
                viewModel.reason(inputText, bitmaps)
            }
        },
        isAccessibilityServiceEnabled = isAccessibilityServiceEffectivelyEnabled,
        onEnableAccessibilityService = {
            mainActivity?.let {
                val intent = it.getAccessibilitySettingsIntent()
                try {
                    accessibilitySettingsLauncher.launch(intent)
                    // Removed the toast from here as it's now handled by the send button
                    // and the dedicated "activate" button on the warning card.
                } catch (e: Exception) {
                    Log.e("PhotoReasoningRoute", "Error opening accessibility settings", e)
                    it.updateStatusMessage("Error opening Accessibility Settings.", true)
                }
            }
        },
        onClearChatHistory = {
            mainActivity?.let {
                val vm = it.getPhotoReasoningViewModel()
                vm?.clearChatHistory(context)
            }
        },
        isKeyboardOpen = isKeyboardOpen
    )
}

@Composable
fun PhotoReasoningScreen(
    uiState: PhotoReasoningUiState = PhotoReasoningUiState.Initial,
    commandExecutionStatus: String = "",
    detectedCommands: List<Command> = emptyList(),
    systemMessage: String = "",
    chatMessages: List<PhotoReasoningMessage> = emptyList(),
    onSystemMessageChanged: (String) -> Unit = {},
    onReasonClicked: (String, List<Uri>) -> Unit = { _, _ -> },
    isAccessibilityServiceEnabled: Boolean = false,
    onEnableAccessibilityService: () -> Unit = {},
    onClearChatHistory: () -> Unit = {},
    isKeyboardOpen: Boolean
) {
    var userQuestion by rememberSaveable { mutableStateOf("") }
    val imageUris = rememberSaveable(saver = UriSaver()) { mutableStateListOf() }
    var isSystemMessageFocused by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current // Get context for Toast
    val focusManager = LocalFocusManager.current

    BackHandler(enabled = isSystemMessageFocused && !isKeyboardOpen) {
        focusManager.clearFocus() // Clear focus first
        isSystemMessageFocused = false // Then update the state that controls height
    }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { imageUris.add(it) }
    }

    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(all = 16.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "System Message",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                val systemMessageHeight = when {
                    isSystemMessageFocused && isKeyboardOpen -> 450.dp // Changed from 600.dp
                    isSystemMessageFocused && !isKeyboardOpen -> 1000.dp
                    else -> 120.dp
                }
                val currentMinLines = if (systemMessageHeight == 120.dp) 3 else 1
                val currentMaxLines = if (systemMessageHeight == 120.dp) 5 else Int.MAX_VALUE
                OutlinedTextField(
                    value = systemMessage,
                    onValueChange = onSystemMessageChanged,
                    placeholder = { Text("Enter a system message here that will be sent with every request") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(systemMessageHeight)
                        .onFocusChanged { focusState -> isSystemMessageFocused = focusState.isFocused },
                    minLines = currentMinLines,
                    maxLines = currentMaxLines
                )
            }
        }

        if (!isAccessibilityServiceEnabled) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Accessibility Service is not enabled",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "The click functionality requires the Accessibility Service. Please enable it in the settings.",
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            onEnableAccessibilityService()
                            // Optionally, show a toast here as well if the user clicks this specific button
                            Toast.makeText(context, "Open Accessibility Settings...", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Activate Accessibility Service")
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(chatMessages) { message ->
                when (message.participant) {
                    PhotoParticipant.USER -> {
                        UserChatBubble(
                            text = message.text,
                            isPending = message.isPending,
                            imageUris = message.imageUris
                        )
                    }
                    PhotoParticipant.MODEL -> {
                        ModelChatBubble(
                            text = message.text,
                            isPending = message.isPending
                        )
                    }
                    PhotoParticipant.ERROR -> {
                        ErrorChatBubble(
                            text = message.text
                        )
                    }
                } // Closing brace for 'when'
            } // Closing brace for 'items' lambda
        } // Closing brace for 'LazyColumn'

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(all = 4.dp)
                        .align(Alignment.CenterVertically)
                ) {
                    IconButton(
                        onClick = {
                            pickMedia.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.add_image),
                        )
                    }
                    IconButton(
                        onClick = {
                            onClearChatHistory()
                        },
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .drawBehind {
                                drawCircle(
                                    color = Color.Black,
                                    radius = size.minDimension / 2,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = 1.dp.toPx()
                                    )
                                )
                            }
                    ) {
                        Text(
                            text = "New",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                OutlinedTextField(
                    value = userQuestion,
                    label = { Text(stringResource(R.string.reason_label)) },
                    placeholder = { Text(stringResource(R.string.reason_hint)) },
                    onValueChange = { userQuestion = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )
                IconButton(
                    onClick = {
                        // START: Updated Send button logic
                        if (isAccessibilityServiceEnabled) {
                            if (userQuestion.isNotBlank()) {
                                onReasonClicked(userQuestion, imageUris.toList())
                                userQuestion = "" // Clear input after sending
                            }
                        } else {
                            // Accessibility service is not enabled
                            onEnableAccessibilityService() // Open settings
                            Toast.makeText(context, "Enable the Accessibility service for Screen Operator", Toast.LENGTH_LONG).show()
                        }
                        // END: Updated Send button logic
                    },
                    modifier = Modifier
                        .padding(all = 4.dp)
                        .align(Alignment.CenterVertically)
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = stringResource(R.string.action_go),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            LazyRow(
                modifier = Modifier.padding(all = 8.dp)
            ) {
                items(imageUris) { imageUri ->
                    AsyncImage(
                        model = imageUri,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(4.dp)
                            .requiredSize(72.dp)
                    )
                }
            }
        }

        if (commandExecutionStatus.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Command Status:",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = commandExecutionStatus,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        if (detectedCommands.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Detected Commands:",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    detectedCommands.forEachIndexed { index, command ->
                        val commandText = when (command) {
                            is Command.ClickButton -> "Click on button: \"${command.buttonText}\""
                            is Command.TapCoordinates -> "Tap coordinates: (${command.x}, ${command.y})"
                            is Command.TakeScreenshot -> "Take screenshot"
                            is Command.PressHomeButton -> "Press Home button"
                            is Command.PressBackButton -> "Press Back button"
                            is Command.ShowRecentApps -> "Open recent apps overview"
                            is Command.ScrollDown -> "Scroll down"
                            is Command.ScrollUp -> "Scroll up"
                            is Command.ScrollLeft -> "Scroll left"
                            is Command.ScrollRight -> "Scroll right"
                            is Command.ScrollDownFromCoordinates -> "Scroll down from position (${command.x}, ${command.y}) with distance ${command.distance}px and duration ${command.duration}ms"
                            is Command.ScrollUpFromCoordinates -> "Scroll up from position (${command.x}, ${command.y}) with distance ${command.distance}px and duration ${command.duration}ms"
                            is Command.ScrollLeftFromCoordinates -> "Scroll left from position (${command.x}, ${command.y}) with distance ${command.distance}px and duration ${command.duration}ms"
                            is Command.ScrollRightFromCoordinates -> "Scroll right from position (${command.x}, ${command.y}) with distance ${command.distance}px and duration ${command.duration}ms"
                            is Command.OpenApp -> "Open app: \"${command.packageName}\""
                            is Command.WriteText -> "Write text: \"${command.text}\""
                            is Command.UseHighReasoningModel -> "Switch to more powerful model (gemini-2.5-pro-preview-03-25)"
                            is Command.UseLowReasoningModel -> "Switch to faster model (gemini-2.0-flash-lite)"
                            is Command.PressEnterKey -> "Enter command detected"
                        }
                        Text(
                            text = "${index + 1}. $commandText",
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        if (index < detectedCommands.size - 1) {
                            Divider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UserChatBubble(
    text: String,
    isPending: Boolean,
    imageUris: List<String> = emptyList()
) {
    Row(
        modifier = Modifier
            .padding(vertical = 8.dp, horizontal = 8.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            modifier = Modifier.weight(4f)
        ) {
            Column(
                modifier = Modifier.padding(all = 16.dp)
            ) {
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (imageUris.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        items(imageUris) { uri ->
                            AsyncImage(
                                model = uri,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(4.dp)
                                    .requiredSize(100.dp)
                            )
                        }
                    }
                }
                if (isPending) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .requiredSize(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}

@Composable
fun ModelChatBubble(
    text: String,
    isPending: Boolean
) {
    Row(
        modifier = Modifier
            .padding(vertical = 8.dp, horizontal = 8.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            modifier = Modifier.weight(4f)
        ) {
            Row(
                modifier = Modifier.padding(all = 16.dp)
            ) {
                Icon(
                    Icons.Outlined.Person,
                    contentDescription = "AI Assistant",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .requiredSize(24.dp)
                        .drawBehind {
                            drawCircle(color = Color.White)
                        }
                        .padding(end = 8.dp)
                )
                Column {
                    Text(
                        text = text,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    if (isPending) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .requiredSize(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
fun ErrorChatBubble(
    text: String
) {
    Box(
        modifier = Modifier
            .padding(vertical = 8.dp, horizontal = 8.dp)
            .fillMaxWidth()
    ) {
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(all = 16.dp)
            )
        }
    }
}

@Preview
@Composable
fun PhotoReasoningScreenPreviewWithContent() {
    GenerativeAISample {
        PhotoReasoningScreen(
            uiState = PhotoReasoningUiState.Success("This is a preview of the photo reasoning screen."),
            commandExecutionStatus = "Command executed: Take screenshot",
        detectedCommands = listOf(
            Command.TakeScreenshot,
            Command.ClickButton("OK")
        ),
        systemMessage = "This is a system message for the AI",
        chatMessages = listOf(
            PhotoReasoningMessage(
                text = "Hello, how can I help you?",
                participant = PhotoParticipant.USER
            ),
            PhotoReasoningMessage(
                text = "I am here to help you. What do you want to know?",
                participant = PhotoParticipant.MODEL
            )
        ),
        isKeyboardOpen = false
    )
}

@Composable
@Preview(showSystemUi = true)
fun PhotoReasoningScreenPreviewEmpty() {
    GenerativeAISample {
        PhotoReasoningScreen(isKeyboardOpen = false)
    }
}
