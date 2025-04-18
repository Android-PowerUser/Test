package com.google.ai.sample.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.runtime.key // <<< NEU: Import für key Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ai.sample.GenerativeViewModelFactory
import com.google.ai.sample.MainActivity
import com.google.ai.sample.R
import com.google.ai.sample.ScreenOperatorAccessibilityService
import com.google.ai.sample.util.Command
import com.google.ai.sample.util.ModelPreferences // Import hinzugefügt
import android.util.Log // Import für Log

// Definitionen sollten in ChatMessage.kt sein
// enum class Participant { USER, MODEL, ERROR }
// data class ChatMessage(...)

@Composable
internal fun ChatRoute(
    viewModel: ChatViewModel = viewModel(factory = GenerativeViewModelFactory)
) {
    val chatUiState by viewModel.uiState.collectAsState()
    val commandExecutionStatus by viewModel.commandExecutionStatus.collectAsState()
    val detectedCommands by viewModel.detectedCommands.collectAsState()
    // --- NEU: Aktuellen Modellnamen beobachten ---
    val currentModelName by viewModel.currentModelName.collectAsState()

    val context = LocalContext.current
    val mainActivity = context as? MainActivity

    // Check if accessibility service is enabled
    val isAccessibilityServiceEnabled = remember {
        mutableStateOf(
            mainActivity?.let {
                ScreenOperatorAccessibilityService.isAccessibilityServiceEnabled(it)
            } ?: false
        )
    }

    // Check accessibility service status when the screen is composed
    DisposableEffect(Unit) {
        mainActivity?.checkAccessibilityServiceEnabled()
        onDispose { }
    }

    // --- ENTFERNT: LaunchedEffect für alten Trigger ---
    // LaunchedEffect(modelUpdateTrigger) { /* ... */ }

    // --- NEU: Verwende currentModelName als Key für den Screen ---
    key(currentModelName) { // Erzwingt Neukomposition des Screens bei Modellwechsel
        ChatScreen(
            uiState = chatUiState,
            commandExecutionStatus = commandExecutionStatus,
            detectedCommands = detectedCommands,
            onMessageSent = { messageText ->
                viewModel.sendMessage(messageText)
            },
            isAccessibilityServiceEnabled = isAccessibilityServiceEnabled.value,
            onEnableAccessibilityService = {
                // Aktualisiere den Status und öffne Einstellungen
                isAccessibilityServiceEnabled.value = mainActivity?.let {
                    ScreenOperatorAccessibilityService.isAccessibilityServiceEnabled(it)
                } ?: false
                mainActivity?.checkAccessibilityServiceEnabled()
            }
        )
    }
}

@Composable
fun ChatScreen(
    uiState: ChatUiState,
    commandExecutionStatus: String = "",
    detectedCommands: List<Command> = emptyList(),
    onMessageSent: (String) -> Unit,
    isAccessibilityServiceEnabled: Boolean = false,
    onEnableAccessibilityService: () -> Unit = {}
) {
    var userMessage by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Scroll to the bottom when new messages arrive
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(all = 16.dp)
    ) {
        // Accessibility Service Status Card
        if (!isAccessibilityServiceEnabled) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Accessibility Service ist nicht aktiviert", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Die Klick-Funktionalität benötigt den Accessibility Service. Bitte aktivieren Sie ihn in den Einstellungen.", color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onEnableAccessibilityService) { Text("Accessibility Service aktivieren") }
                }
            }
        }

        // Command Execution Status
        if (commandExecutionStatus.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Befehlsstatus:", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = commandExecutionStatus, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }

        // Detected Commands
        if (detectedCommands.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Erkannte Befehle:", style = MaterialTheme.typography.titleMedium)
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
                            is Command.UseHighReasoningModel -> "Wechsle zu ${ModelPreferences.HIGH_REASONING_MODEL}"
                            is Command.UseLowReasoningModel -> "Wechsle zu ${ModelPreferences.LOW_REASONING_MODEL}"
                        }
                        Text(text = "${index + 1}. $commandText", color = MaterialTheme.colorScheme.onTertiaryContainer)
                        if (index < detectedCommands.size - 1) {
                            Divider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f))
                        }
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
            items(uiState.messages) { message ->
                when (message.participant) {
                    Participant.USER -> UserChatBubble(text = message.text, isPending = message.isPending)
                    Participant.MODEL -> ModelChatBubble(text = message.text, isPending = message.isPending)
                    Participant.ERROR -> ErrorChatBubble(text = message.text)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = userMessage,
                onValueChange = { userMessage = it },
                placeholder = { Text(stringResource(R.string.chat_label)) },
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            IconButton(
                onClick = {
                    if (userMessage.isNotBlank()) {
                        onMessageSent(userMessage)
                        userMessage = ""
                    }
                }
            ) {
                Icon(Icons.Default.Send, contentDescription = stringResource(R.string.action_send), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// --- Chat Bubbles (unverändert) ---
@Composable
fun UserChatBubble(text: String, isPending: Boolean) { /* ... */ }
@Composable
fun ModelChatBubble(text: String, isPending: Boolean) { /* ... */ }
@Composable
fun ErrorChatBubble(text: String) { /* ... */ }
