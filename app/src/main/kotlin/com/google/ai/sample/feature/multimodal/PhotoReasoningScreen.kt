package com.google.ai.sample.feature.multimodal

import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    
    // Get the MainActivity instance from the context and share the ViewModel
    val mainActivity = context as? MainActivity
    
    // Share the ViewModel with MainActivity for AccessibilityService access
    DisposableEffect(viewModel) {
        // Set the ViewModel in MainActivity when the composable is first composed
        mainActivity?.setPhotoReasoningViewModel(viewModel)
        Log.d("PhotoReasoningRoute", "ViewModel shared with MainActivity: ${mainActivity != null}")
        
        // Check if accessibility service is enabled
        mainActivity?.checkAccessibilityServiceEnabled()
        
        // Load the saved system message
        viewModel.loadSystemMessage(context)
        
        // When the composable is disposed, clear the reference if needed
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
                
                // Process all selected images
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
                
                // Send to AI
                viewModel.reason(inputText, bitmaps)
            }
        },
         isAccessibilityServiceEnabled = mainActivity?.let {
            ScreenOperatorAccessibilityService.isAccessibilityServiceEnabled(it)
        } ?: false,
        onEnableAccessibilityService = {
            mainActivity?.openAccessibilitySettings() // Geändert, um die Einstellungen zu öffnen
        },
        onClearChatHistory = {
            mainActivity?.let {
                val viewModel = it.getPhotoReasoningViewModel()
                viewModel?.clearChatHistory(context)
            }
        }
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
    onClearChatHistory: () -> Unit = {}
) {
    var userQuestion by rememberSaveable { mutableStateOf("") }
    val imageUris = rememberSaveable(saver = UriSaver()) { mutableStateListOf() }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    
    // Get the MainActivity instance from the context
    val mainActivity = context as? MainActivity
    
    // Media picker for adding images
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { imageUris.add(it) }
    }

    // Scroll to the bottom when new messages arrive
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .padding(all = 16.dp)
    ) {
        // System Message Field
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
                OutlinedTextField(
                    value = systemMessage,
                    onValueChange = onSystemMessageChanged,
                    placeholder = { Text("Geben Sie hier eine System-Nachricht ein, die bei jeder Anfrage mitgesendet wird") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp), // Height increased by 50% (from 80dp to 120dp)
                    maxLines = 5, // Increased to accommodate more visible lines
                    minLines = 3
                )
            }
        }
        
        // Accessibility Service Status Card
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
                        text = "Accessibility Service ist nicht aktiviert",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Die Klick-Funktionalität benötigt den Accessibility Service. Bitte aktivieren Sie ihn in den Einstellungen.",
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = onEnableAccessibilityService
                    ) {
                        Text("Accessibility Service aktivieren")
                    }
                }
            }
        }

        // Chat History
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
                }
            }
        }

        // Input Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(top = 16.dp)
            ) {
                // Column for the two buttons (+ and New)
                Column(
                    modifier = Modifier
                        .padding(all = 4.dp)
                        .align(Alignment.CenterVertically)
                ) {
                    // Add image button (moved up)
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
                    
                    // New button to clear chat history
                    IconButton(
                        onClick = {
                            // Clear chat history directly without confirmation
                            onClearChatHistory()
                        },
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .drawBehind {
                                // Draw a thin black circular border
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
                        if (userQuestion.isNotBlank()) {
                            onReasonClicked(userQuestion, imageUris.toList())
                            userQuestion = ""
                        }
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
        
        // Command Execution Status
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
                        text = "Befehlsstatus:",
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
        
        // Detected Commands
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
                        text = "Erkannte Befehle:",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    detectedCommands.forEachIndexed { index, command ->
                        val commandText = when (command) {
                            is Command.ClickButton -> "Klick auf Button: \"${command.buttonText}\""
                            is Command.TapCoordinates -> "Tippen auf Koordinaten: (${command.x}, ${command.y})"
                            is Command.TakeScreenshot -> "Screenshot aufnehmen"
                            is Command.PressHomeButton -> "Home-Button drücken"
                            is Command.PressBackButton -> "Zurück-Button drücken"
                            is Command.ShowRecentApps -> "Übersicht der letzten Apps öffnen"
                            is Command.ScrollDown -> "Nach unten scrollen"
                            is Command.ScrollUp -> "Nach oben scrollen"
                            is Command.ScrollLeft -> "Nach links scrollen"
                            is Command.ScrollRight -> "Nach rechts scrollen"
                            is Command.ScrollDownFromCoordinates -> "Nach unten scrollen von Position (${command.x}, ${command.y}) mit Distanz ${command.distance}px und Dauer ${command.duration}ms"
                            is Command.ScrollUpFromCoordinates -> "Nach oben scrollen von Position (${command.x}, ${command.y}) mit Distanz ${command.distance}px und Dauer ${command.duration}ms"
                            is Command.ScrollLeftFromCoordinates -> "Nach links scrollen von Position (${command.x}, ${command.y}) mit Distanz ${command.distance}px und Dauer ${command.duration}ms"
                            is Command.ScrollRightFromCoordinates -> "Nach rechts scrollen von Position (${command.x}, ${command.y}) mit Distanz ${command.distance}px und Dauer ${command.duration}ms"
                            is Command.OpenApp -> "App öffnen: \"${command.packageName}\""
                            is Command.WriteText -> "Text schreiben: \"${command.text}\""
                            is Command.UseHighReasoningModel -> "Wechsle zu leistungsfähigerem Modell (gemini-2.5-pro-preview-03-25)"
                            is Command.UseLowReasoningModel -> "Wechsle zu schnellerem Modell (gemini-2.0-flash-lite)"
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
                
                // Display images if any
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
    PhotoReasoningScreen(
        uiState = PhotoReasoningUiState.Success("This is a preview of the photo reasoning screen."),
        commandExecutionStatus = "Befehl ausgeführt: Screenshot aufnehmen",
        detectedCommands = listOf(
            Command.TakeScreenshot,
            Command.ClickButton("OK")
        ),
        systemMessage = "Dies ist eine System-Nachricht für die KI",
        chatMessages = listOf(
            PhotoReasoningMessage(
                text = "Hallo, wie kann ich dir helfen?",
                participant = PhotoParticipant.USER
            ),
            PhotoReasoningMessage(
                text = "Ich bin hier, um dir zu helfen. Was möchtest du wissen?",
                participant = PhotoParticipant.MODEL
            )
        )
    )
}

@Composable
@Preview(showSystemUi = true)
fun PhotoReasoningScreenPreviewEmpty() {
    PhotoReasoningScreen()
}
