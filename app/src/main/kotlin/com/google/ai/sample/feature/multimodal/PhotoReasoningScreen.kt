package com.google.ai.sample.feature.multimodal

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.Settings
import android.widget.Toast 
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items 
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Add
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
// Removed duplicate block:
// import androidx.compose.material3.AlertDialog
// import androidx.compose.material3.Button
// import androidx.compose.material3.ButtonDefaults
// import androidx.compose.material3.Card
// import androidx.compose.material3.CardDefaults
// import androidx.compose.material3.CircularProgressIndicator
// import androidx.compose.material3.Divider
// import androidx.compose.material3.Checkbox
// import androidx.compose.material3.CheckboxDefaults
// import androidx.compose.material3.DropdownMenu
// import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton // Existing, ensure it's not duplicated
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.google.ai.sample.util.SystemMessageEntry
import com.google.ai.sample.util.SystemMessageEntryPreferences
import com.google.ai.sample.util.UriSaver
import com.google.ai.sample.util.shareTextFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import android.util.Log
import kotlinx.serialization.SerializationException

// Define Colors
val DarkYellow1 = Color(0xFFF0A500) // A darker yellow
val DarkYellow2 = Color(0xFFF3C100) // A slightly lighter dark yellow

@Composable
fun StopButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Text("Stop", color = Color.White)
    }
}

@Composable
internal fun PhotoReasoningRoute(
    viewModel: PhotoReasoningViewModel = viewModel(factory = GenerativeViewModelFactory)
) {
    val photoReasoningUiState by viewModel.uiState.collectAsState()
    val commandExecutionStatus by viewModel.commandExecutionStatus.collectAsState()
    val detectedCommands by viewModel.detectedCommands.collectAsState()
    val systemMessage by viewModel.systemMessage.collectAsState()
    val chatMessages by viewModel.chatMessagesFlow.collectAsState()
    val isInitialized by viewModel.isInitialized.collectAsState()

    // Hoisted: var showNotificationRationaleDialog by rememberSaveable { mutableStateOf(false) }
    // This state will now be managed in PhotoReasoningRoute and passed down.
    // var showNotificationRationaleDialogStateInRoute by rememberSaveable { mutableStateOf(false) } // Removed


    val coroutineScope = rememberCoroutineScope()
    val imageRequestBuilder = ImageRequest.Builder(LocalContext.current)
    val imageLoader = ImageLoader.Builder(LocalContext.current).build()
    val context = LocalContext.current
    val mainActivity = context as? MainActivity

    val isAccessibilityServiceEffectivelyEnabled by mainActivity?.isAccessibilityServiceEnabledFlow?.collectAsState() ?: mutableStateOf(false)
    val isKeyboardOpen by mainActivity?.isKeyboardOpen?.collectAsState() ?: mutableStateOf(false)

    val accessibilitySettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        mainActivity?.refreshAccessibilityServiceStatus()
    }

    DisposableEffect(viewModel, mainActivity) {
        mainActivity?.setPhotoReasoningViewModel(viewModel)
        mainActivity?.refreshAccessibilityServiceStatus()
        viewModel.loadSystemMessage(context)
        onDispose { }
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
                val bitmaps = selectedItems.mapNotNull {
                    val imageRequest = imageRequestBuilder.data(it).precision(Precision.EXACT).build()
                    try {
                        val result = imageLoader.execute(imageRequest)
                        if (result is SuccessResult) (result.drawable as BitmapDrawable).bitmap else null
                    } catch (e: Exception) { null }
                }
                viewModel.reason(
                    userInput = inputText,
                    selectedImages = bitmaps,
                    screenInfoForPrompt = null, // User-initiated messages don't have prior screen context here
                    imageUrisForChat = selectedItems.map { it.toString() }
                )
            }
        },
        isAccessibilityServiceEnabled = isAccessibilityServiceEffectivelyEnabled,
        onEnableAccessibilityService = {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    try {
        accessibilitySettingsLauncher.launch(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Error opening Accessibility Settings.", Toast.LENGTH_LONG).show()
    }
},
        onClearChatHistory = {
            mainActivity?.getPhotoReasoningViewModel()?.clearChatHistory(context)
        },
        isKeyboardOpen = isKeyboardOpen,
        onStopClicked = { viewModel.onStopClicked() },
        // showNotificationRationaleDialog = showNotificationRationaleDialogStateInRoute, // Removed
        // onShowNotificationRationaleDialogChange = { showNotificationRationaleDialogStateInRoute = it }, // Removed
        isInitialized = isInitialized // Pass the collected state
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
    isKeyboardOpen: Boolean,
    onStopClicked: () -> Unit = {},
    // showNotificationRationaleDialog: Boolean, // Removed
    // onShowNotificationRationaleDialogChange: (Boolean) -> Unit, // Removed
    isInitialized: Boolean = true // Added parameter with default for preview
) {
    var userQuestion by rememberSaveable { mutableStateOf("") }
    val imageUris = rememberSaveable(saver = UriSaver()) { mutableStateListOf() }
    var isSystemMessageFocused by rememberSaveable { mutableStateOf(false) }
    var showDatabaseListPopup by rememberSaveable { mutableStateOf(false) }
    var showEditEntryPopup by rememberSaveable { mutableStateOf(false) }
    var entryToEdit: SystemMessageEntry? by rememberSaveable(stateSaver = SystemMessageEntrySaver) { mutableStateOf(null) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var systemMessageEntries by rememberSaveable { mutableStateOf(emptyList<SystemMessageEntry>()) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) { 
        systemMessageEntries = SystemMessageEntryPreferences.loadEntries(context)
    }
     LaunchedEffect(showDatabaseListPopup) { 
        if (showDatabaseListPopup) {
            systemMessageEntries = SystemMessageEntryPreferences.loadEntries(context)
        }
    }

    BackHandler(enabled = isSystemMessageFocused && !isKeyboardOpen) {
        focusManager.clearFocus() 
        isSystemMessageFocused = false 
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

    Column(modifier = Modifier.padding(all = 16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "System Message",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { showDatabaseListPopup = true },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        border = BorderStroke(1.dp, Color.Black)
                    ) { Text("Database") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                val systemMessageHeight = when {
                    isSystemMessageFocused && isKeyboardOpen -> 450.dp
                    isSystemMessageFocused && !isKeyboardOpen -> 1000.dp
                    else -> 120.dp
                }
                val currentMinLines = if (systemMessageHeight == 120.dp) 3 else 1
                val currentMaxLines = if (systemMessageHeight == 120.dp) 5 else Int.MAX_VALUE
                OutlinedTextField(
                    value = systemMessage,
                    onValueChange = onSystemMessageChanged,
                    placeholder = { Text("Enter a system message here that will be sent with every request") },
                    modifier = Modifier.fillMaxWidth().height(systemMessageHeight)
                        .onFocusChanged { focusState -> isSystemMessageFocused = focusState.isFocused },
                    minLines = currentMinLines,
                    maxLines = currentMaxLines
                )
            }
        }

        if (!isAccessibilityServiceEnabled) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Accessibility Service is not enabled", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("The click functionality requires the Accessibility Service. Please enable it in the settings.", color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = {
                        onEnableAccessibilityService()
                        Toast.makeText(context, "Open Accessibility Settings..." as CharSequence, Toast.LENGTH_SHORT).show()
                    }) { Text("Activate Accessibility Service") }
                }
            }
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(chatMessages) { message ->
                when (message.participant) {
                    PhotoParticipant.USER -> UserChatBubble(message.text, message.isPending, message.imageUris)
                    PhotoParticipant.MODEL -> ModelChatBubble(message.text, message.isPending)
                    PhotoParticipant.ERROR -> ErrorChatBubble(message.text)
                }
            }
        }

        val showStopButton = uiState is PhotoReasoningUiState.Loading || commandExecutionStatus.isNotEmpty()

        if (showStopButton) {
            StopButton(onClick = onStopClicked)
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(top = 16.dp)) {
                    Column(modifier = Modifier.padding(all = 4.dp).align(Alignment.CenterVertically)) {
                        IconButton(onClick = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.padding(bottom = 4.dp)) {
                            Icon(Icons.Rounded.Add, stringResource(R.string.add_image))
                        }
                        IconButton(onClick = onClearChatHistory, modifier = Modifier.padding(top = 4.dp).drawBehind {
                            drawCircle(color = Color.Black, radius = size.minDimension / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
                        }) { Text("New", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    }
                    OutlinedTextField(
                        value = userQuestion,
                        label = { Text(stringResource(R.string.reason_label)) },
                        placeholder = { Text(stringResource(R.string.reason_hint)) },
                        onValueChange = { userQuestion = it },
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                        IconButton(
                            onClick = {
                                if (isAccessibilityServiceEnabled) {
                                    if (userQuestion.isNotBlank()) {
                                        onReasonClicked(userQuestion, imageUris.toList())
                                        userQuestion = ""
                                    }
                                    // If accessibility is ON but userQuestion is BLANK, no action is needed here as the button's enabled state and tint convey this.
                                } else {
                                    // Accessibility is OFF
                                    onEnableAccessibilityService()
                                    Toast.makeText(context, "Enable the Accessibility service for Screen Operator", Toast.LENGTH_LONG).show()
                                } // Closes the else block
                            },
                            enabled = isInitialized && ((isAccessibilityServiceEnabled && userQuestion.isNotBlank()) || !isAccessibilityServiceEnabled),
                            modifier = Modifier.padding(all = 4.dp).align(Alignment.CenterVertically)
                        ) {
                            Icon(
                                Icons.Default.Send,
                                stringResource(R.string.action_go),
                                tint = if ((isAccessibilityServiceEnabled && userQuestion.isNotBlank()) || !isAccessibilityServiceEnabled) MaterialTheme.colorScheme.primary else Color.Gray,
                            )
                        }
                    } // Closes Row
                    LazyRow(modifier = Modifier.padding(all = 8.dp)) {
                        items(imageUris) { uri -> AsyncImage(uri, null, Modifier.padding(4.dp).requiredSize(72.dp)) }
                    }
                } // Closes Card
            }
        }

        if (commandExecutionStatus.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Command Status:", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(commandExecutionStatus, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
        if (detectedCommands.isNotEmpty()) {
            Card(modifier = Modifier.padding(vertical = 8.dp).wrapContentHeight(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Detected Commands:", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    detectedCommands.forEachIndexed { index, command ->
                        val commandText = when (command) {
                            is Command.ClickButton -> "Click on button: \"${command.buttonText}\""
                            is Command.TapCoordinates -> "Tap coordinates: (${command.x}, ${command.y})"
                            is Command.TakeScreenshot -> "Take screenshot"
                            else -> command::class.simpleName ?: "Unknown Command"
                        }
                        Text("${index + 1}. $commandText", color = MaterialTheme.colorScheme.onTertiaryContainer)
                        if (index < detectedCommands.size - 1) Divider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f))
                    }
                }
            }
        }

        if (showDatabaseListPopup) {
            DatabaseListPopup(
                onDismissRequest = { showDatabaseListPopup = false },
                entries = systemMessageEntries,
                onNewClicked = {
                    entryToEdit = null 
                    showEditEntryPopup = true
                },
                onEntryClicked = { entry ->
                    entryToEdit = entry 
                    showEditEntryPopup = true
                },
                onDeleteClicked = { entry ->
                    SystemMessageEntryPreferences.deleteEntry(context, entry)
                    systemMessageEntries = SystemMessageEntryPreferences.loadEntries(context) 
                },
                onImportCompleted = { 
                    systemMessageEntries = SystemMessageEntryPreferences.loadEntries(context)
                }
            )
        }

        if (showEditEntryPopup) {
            EditEntryPopup(
                entry = entryToEdit,
                onDismissRequest = { showEditEntryPopup = false },
                onSaveClicked = { title, guide, originalEntry ->
                    val currentEntry = SystemMessageEntry(title.trim(), guide.trim()) 
                    if (title.isBlank() || guide.isBlank()) { 
                        Toast.makeText(context, "Title and Guide cannot be empty." as CharSequence, Toast.LENGTH_SHORT).show()
                        return@EditEntryPopup
                    }
                    if (originalEntry == null) { 
                        val existingEntry = systemMessageEntries.find { it.title.equals(currentEntry.title, ignoreCase = true) }
                        if (existingEntry == null) {
                            SystemMessageEntryPreferences.addEntry(context, currentEntry)
                            showEditEntryPopup = false
                            systemMessageEntries = SystemMessageEntryPreferences.loadEntries(context) 
                        } else {
                            Toast.makeText(context, "An entry with this title already exists." as CharSequence, Toast.LENGTH_SHORT).show()
                            return@EditEntryPopup 
                        }
                    } else { 
                        val existingEntryWithNewTitle = systemMessageEntries.find { it.title.equals(currentEntry.title, ignoreCase = true) && it.guide != originalEntry.guide }
                        if (existingEntryWithNewTitle != null && originalEntry.title != currentEntry.title) {
                            Toast.makeText(context, "Another entry with this new title already exists." as CharSequence, Toast.LENGTH_SHORT).show()
                            return@EditEntryPopup 
                        }
                        SystemMessageEntryPreferences.updateEntry(context, originalEntry, currentEntry)
                        showEditEntryPopup = false
                        systemMessageEntries = SystemMessageEntryPreferences.loadEntries(context) 
                    }
                }
            )
        }
    }


@Composable
fun DatabaseListPopup(
    onDismissRequest: () -> Unit,
    entries: List<SystemMessageEntry>,
    onNewClicked: () -> Unit,
    onEntryClicked: (SystemMessageEntry) -> Unit,
    onDeleteClicked: (SystemMessageEntry) -> Unit,
    onImportCompleted: () -> Unit
) {
    val TAG_IMPORT_PROCESS = "ImportProcess"
    val scope = rememberCoroutineScope()
    var entryMenuToShow: SystemMessageEntry? by remember { mutableStateOf(null) }
    var selectionModeActive by rememberSaveable { mutableStateOf(false) }
    var selectedEntryTitles by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var selectAllChecked by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    var entryToConfirmOverwrite by remember { mutableStateOf<Pair<SystemMessageEntry, SystemMessageEntry>?>(null) }
    var remainingEntriesToImport by remember { mutableStateOf<List<SystemMessageEntry>>(emptyList()) }
    var skipAllDuplicates by remember { mutableStateOf(false) }

    // processImportedEntries is defined within DatabaseListPopup, so it has access to context, onImportCompleted, etc.
    fun processImportedEntries( 
        imported: List<SystemMessageEntry>,
        currentSystemEntries: List<SystemMessageEntry>
    ) {
        val TAG_IMPORT_PROCESS_FUNCTION = "ImportProcessFunction" 
        Log.d(TAG_IMPORT_PROCESS_FUNCTION, "Starting processImportedEntries. Imported: ${imported.size}, Current: ${currentSystemEntries.size}, SkipAll: $skipAllDuplicates")

        var newCount = 0
        var updatedCount = 0 
        var skippedCount = 0
        val entriesToProcess = imported.toMutableList()

        while (entriesToProcess.isNotEmpty()) {
            val newEntry = entriesToProcess.removeAt(0)
            Log.d(TAG_IMPORT_PROCESS_FUNCTION, "Processing entry: Title='${newEntry.title}'. Remaining in batch: ${entriesToProcess.size}")
            val existingEntry = currentSystemEntries.find { it.title.equals(newEntry.title, ignoreCase = true) }

            if (existingEntry != null) {
                Log.d(TAG_IMPORT_PROCESS_FUNCTION, "Duplicate found for title: '${newEntry.title}'. Existing guide: '${existingEntry.guide.take(50)}', New guide: '${newEntry.guide.take(50)}'")
                if (skipAllDuplicates) {
                    Log.d(TAG_IMPORT_PROCESS_FUNCTION, "Skipping duplicate '${newEntry.title}' due to skipAllDuplicates flag.")
                    skippedCount++
                    continue
                }
                Log.d(TAG_IMPORT_PROCESS_FUNCTION, "Calling askForOverwrite for '${newEntry.title}'.")
                entryToConfirmOverwrite = Pair(existingEntry, newEntry) 
                remainingEntriesToImport = entriesToProcess.toList() 
                return 
            } else {
                Log.i(TAG_IMPORT_PROCESS_FUNCTION, "Adding new entry: Title='${newEntry.title}'")
                SystemMessageEntryPreferences.addEntry(context, newEntry)
                newCount++
            }
        }
        Log.i(TAG_IMPORT_PROCESS_FUNCTION, "Finished processing batch. newCount=$newCount, updatedCount=$updatedCount, skippedCount=$skippedCount")
        val summary = "Import finished: $newCount added, $updatedCount updated, $skippedCount skipped."
        Toast.makeText(context, summary as CharSequence, Toast.LENGTH_LONG).show()
        onImportCompleted() 
        skipAllDuplicates = false 
    }


    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            Log.d(TAG_IMPORT_PROCESS, "FilePickerLauncher onResult triggered.")
            if (uri == null) {
                Log.w(TAG_IMPORT_PROCESS, "URI is null, no file selected or operation cancelled.")
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "No file selected." as CharSequence, Toast.LENGTH_SHORT).show()
                }
                return@rememberLauncherForActivityResult
            }

            Log.i(TAG_IMPORT_PROCESS, "Selected file URI: $uri")
            scope.launch(Dispatchers.Main) {
                Toast.makeText(context, "File selected: $uri. Starting import..." as CharSequence, Toast.LENGTH_SHORT).show()
            }

            scope.launch(Dispatchers.IO) { 
                try {
                    Log.d(TAG_IMPORT_PROCESS, "Attempting to open InputStream for URI: $uri on thread: ${Thread.currentThread().name}")
                    
                    var fileSize = -1L
                    try {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            fileSize = pfd.statSize
                        }
                        Log.i(TAG_IMPORT_PROCESS, "Estimated file size: $fileSize bytes.")
                    } catch (e: Exception) {
                        Log.w(TAG_IMPORT_PROCESS, "Could not determine file size for URI: $uri. Will proceed without size check.", e)
                    }

                    val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024 
                    if (fileSize != -1L && fileSize > MAX_FILE_SIZE_BYTES) {
                        Log.e(TAG_IMPORT_PROCESS, "File size ($fileSize bytes) exceeds limit of $MAX_FILE_SIZE_BYTES bytes.")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "File is too large (max 10MB)." as CharSequence, Toast.LENGTH_LONG).show()
                        }
                        return@launch 
                    }
                     if (fileSize == 0L) { 
                         Log.w(TAG_IMPORT_PROCESS, "Imported file is empty (0 bytes).")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Imported file is empty." as CharSequence, Toast.LENGTH_LONG).show()
                        }
                        return@launch 
                    }

                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        Log.i(TAG_IMPORT_PROCESS, "InputStream opened. Reading text on thread: ${Thread.currentThread().name}")
                        val jsonString = inputStream.bufferedReader().readText() 
                        Log.i(TAG_IMPORT_PROCESS, "File content read. Size: ${jsonString.length} chars.")
                        Log.v(TAG_IMPORT_PROCESS, "File content snippet: ${jsonString.take(500)}")

                        if (jsonString.isBlank()) {
                            Log.w(TAG_IMPORT_PROCESS, "Imported file content is blank.")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Imported file content is blank." as CharSequence, Toast.LENGTH_LONG).show()
                            }
                            return@use 
                        }

                        Log.d(TAG_IMPORT_PROCESS, "Attempting to parse JSON string on thread: ${Thread.currentThread().name}")
                        val parsedEntries = Json.decodeFromString(ListSerializer(SystemMessageEntry.serializer()), jsonString)
                        Log.i(TAG_IMPORT_PROCESS, "JSON parsed. Found ${parsedEntries.size} entries.")

                        val currentSystemEntries = SystemMessageEntryPreferences.loadEntries(context) 
                        Log.d(TAG_IMPORT_PROCESS, "Current system entries loaded: ${currentSystemEntries.size} entries.")

                        withContext(Dispatchers.Main) { 
                            Log.d(TAG_IMPORT_PROCESS, "Switching to Main thread for processImportedEntries: ${Thread.currentThread().name}")
                            skipAllDuplicates = false 
                            processImportedEntries(
                                imported = parsedEntries,
                                currentSystemEntries = currentSystemEntries
                            )
                        }
                    } ?: Log.w(TAG_IMPORT_PROCESS, "ContentResolver.openInputStream returned null for URI: $uri (second check).")
                } catch (e: Exception) {
                    Log.e(TAG_IMPORT_PROCESS, "Error during file import for URI: $uri on thread: ${Thread.currentThread().name}", e)
                    withContext(Dispatchers.Main) {
                        val errorMessage = if (e is OutOfMemoryError) {
                            "Out of memory. File may be too large or contain too many entries."
                        } else {
                            e.message ?: "Unknown error during import."
                        }
                        Toast.makeText(context, "Error importing file: $errorMessage" as CharSequence, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    )

    if (entryToConfirmOverwrite != null) {
        val (existingEntry, newEntry) = entryToConfirmOverwrite!!
        OverwriteConfirmationDialog(
            entryTitle = newEntry.title,
            onConfirm = {
                Log.d(TAG_IMPORT_PROCESS, "Overwrite confirmed for title: '${newEntry.title}'")
                SystemMessageEntryPreferences.updateEntry(context, existingEntry, newEntry)
                Toast.makeText(context, "Entry '${newEntry.title}' overwritten." as CharSequence, Toast.LENGTH_SHORT).show()
                entryToConfirmOverwrite = null
                val currentSystemEntriesAfterUpdate = SystemMessageEntryPreferences.loadEntries(context) 
                Log.d(TAG_IMPORT_PROCESS, "Continuing with remaining ${remainingEntriesToImport.size} entries after dialog (Confirm).")
                processImportedEntries( 
                    imported = remainingEntriesToImport,
                    currentSystemEntries = currentSystemEntriesAfterUpdate
                )
            },
            onDeny = { 
                Log.d(TAG_IMPORT_PROCESS, "Overwrite denied for title: '${newEntry.title}'")
                entryToConfirmOverwrite = null
                val currentSystemEntriesAfterDeny = SystemMessageEntryPreferences.loadEntries(context) 
                Log.d(TAG_IMPORT_PROCESS, "Continuing with remaining ${remainingEntriesToImport.size} entries after dialog (Deny).")
                processImportedEntries( 
                    imported = remainingEntriesToImport,
                    currentSystemEntries = currentSystemEntriesAfterDeny
                )
            },
            onSkipAll = { 
                Log.d(TAG_IMPORT_PROCESS, "Skip All selected for title: '${newEntry.title}'")
                skipAllDuplicates = true
                entryToConfirmOverwrite = null
                val currentSystemEntriesAfterSkipAll = SystemMessageEntryPreferences.loadEntries(context) 
                Log.d(TAG_IMPORT_PROCESS, "Continuing with remaining ${remainingEntriesToImport.size} entries after dialog (SkipAll).")
                processImportedEntries( 
                    imported = remainingEntriesToImport,
                    currentSystemEntries = currentSystemEntriesAfterSkipAll
                )
            },
            onDismiss = { 
                Log.d(TAG_IMPORT_PROCESS, "Overwrite dialog dismissed for title: '${entryToConfirmOverwrite?.second?.title}'. Import process for this batch might halt.")
                entryToConfirmOverwrite = null 
                remainingEntriesToImport = emptyList() 
                skipAllDuplicates = false
                Toast.makeText(context, "Import cancelled for remaining items." as CharSequence, Toast.LENGTH_SHORT).show()
                onImportCompleted() 
            }
        )
    }


    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f) 
                .fillMaxHeight(0.85f), 
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkYellow1)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp) 
                    .fillMaxSize()
            ) {
                val displayRowCount = 15
                val newButtonRowIndex = entries.size 

                LazyColumn(modifier = Modifier.weight(1f)) { 
                    items(displayRowCount) { rowIndex ->
                        val currentAlternatingColor = if (rowIndex % 2 == 0) DarkYellow1 else DarkYellow2

                        when {
                            rowIndex < entries.size -> {
                                val entry = entries[rowIndex]
                                val isSelected = selectedEntryTitles.contains(entry.title)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(currentAlternatingColor)
                                        .clickable {
                                            if (selectionModeActive) {
                                                val entryTitle = entry.title
                                                val isCurrentlySelected = selectedEntryTitles.contains(entryTitle)
                                                selectedEntryTitles = if (isCurrentlySelected) {
                                                    selectedEntryTitles - entryTitle
                                                } else {
                                                    selectedEntryTitles + entryTitle
                                                }
                                                selectAllChecked = if (selectedEntryTitles.size == entries.size && entries.isNotEmpty()) true else if (selectedEntryTitles.isEmpty()) false else false
                                            } else {
                                                onEntryClicked(entry) 
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (selectionModeActive) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { isChecked ->
                                                val entryTitle = entry.title
                                                selectedEntryTitles = if (isChecked) {
                                                    selectedEntryTitles + entryTitle
                                                } else {
                                                    selectedEntryTitles - entryTitle
                                                }
                                                selectAllChecked = if (selectedEntryTitles.size == entries.size && entries.isNotEmpty()) true else if (selectedEntryTitles.isEmpty()) false else false
                                            },
                                            modifier = Modifier.padding(end = 8.dp),
                                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .border(BorderStroke(1.dp, Color.Black), shape = RoundedCornerShape(12.dp))
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Text(entry.title, color = Color.Black, style = MaterialTheme.typography.bodyLarge)
                                    }
                                    if (!selectionModeActive) {
                                        Box {
                                            IconButton(onClick = { entryMenuToShow = entry }) {
                                                Icon(Icons.Filled.MoreVert, "More options", tint = Color.Black)
                                            }
                                            DropdownMenu(
                                                expanded = entryMenuToShow == entry,
                                                onDismissRequest = { entryMenuToShow = null }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("Delete") },
                                                    onClick = {
                                                        onDeleteClicked(entry)
                                                        entryMenuToShow = null
                                                    }
                                                )
                                            }
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.width(48.dp)) 
                                    }
                                }
                            }
                            rowIndex == newButtonRowIndex -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth().background(currentAlternatingColor)
                                        .padding(8.dp).clickable(onClick = onNewClicked),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.End 
                                ) {
                                    Text("This is also sent to the AI", color = Color.Black.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Button(onClick = onNewClicked, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary), modifier = Modifier.padding(start = 8.dp)) {
                                        Text("New")
                                    }
                                }
                            }
                            else -> {
                                Box(modifier = Modifier.fillMaxWidth().height(56.dp).background(currentAlternatingColor).padding(16.dp)) {}
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (selectionModeActive) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selectAllChecked,
                                onCheckedChange = { isChecked ->
                                    selectAllChecked = isChecked
                                    selectedEntryTitles = if (isChecked) entries.map { it.title }.toSet() else emptySet()
                                },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                            )
                            Text("All", color = Color.Black, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(80.dp)) // Placeholder for alignment
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { filePickerLauncher.launch("*/*") }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary), modifier = Modifier.padding(end = 8.dp)) { Text("Import") }
                        Button(
                            onClick = {
                                if (selectionModeActive) { 
                                    if (selectedEntryTitles.isEmpty()) {
                                        Toast.makeText(context, "No entries selected for export." as CharSequence, Toast.LENGTH_SHORT).show()
                                    } else {
                                        val entriesToExport = entries.filter { selectedEntryTitles.contains(it.title) }
                                        val jsonString = Json.encodeToString(ListSerializer(SystemMessageEntry.serializer()), entriesToExport)
                                        shareTextFile(context, "system_messages_export.txt", jsonString)
                                    }
                                    selectionModeActive = false
                                    selectedEntryTitles = emptySet()
                                    selectAllChecked = false
                                } else { 
                                    selectionModeActive = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) { Text("Export") } // Text is now always "Export"
                    }
                }
            }
        }
    }
}


@Composable
fun OverwriteConfirmationDialog(
    entryTitle: String,
    onConfirm: () -> Unit,
    onDeny: () -> Unit,
    onSkipAll: () -> Unit, 
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Overwrite") },
        text = { Text("An entry with the title \"$entryTitle\" already exists. Do you want to overwrite its guide?") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Yes") }
        },
        dismissButton = {
            TextButton(onClick = onDeny) { Text("No") }
        }
    )
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
    MaterialTheme {
        PhotoReasoningScreen(
            uiState = PhotoReasoningUiState.Success("This is a preview of the photo reasoning screen."),
            commandExecutionStatus = "Command executed: Take screenshot",
            detectedCommands = listOf(
                Command.TakeScreenshot,
                Command.ClickButton("OK")
            ),
            systemMessage = "This is a system message for the AI",
            chatMessages = listOf(
                PhotoReasoningMessage(text = "Hello, how can I help you?", participant = PhotoParticipant.USER),
                PhotoReasoningMessage(text = "I am here to help you. What do you want to know?", participant = PhotoParticipant.MODEL)
            ),
            isKeyboardOpen = false,
            onStopClicked = {},
            isInitialized = true
        )
    }
}

@Composable
fun EditEntryPopup(
    entry: SystemMessageEntry?,
    onDismissRequest: () -> Unit,
    onSaveClicked: (title: String, guide: String, originalEntry: SystemMessageEntry?) -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false) 
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.7f).padding(16.dp), 
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkYellow1) 
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                var titleInput by rememberSaveable { mutableStateOf(entry?.title ?: "") }
                var guideInput by rememberSaveable { mutableStateOf(entry?.guide ?: "") }

                Text("Title", style = MaterialTheme.typography.labelMedium, color = Color.Black.copy(alpha = 0.7f)) 
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    placeholder = { Text("App/Task", color = Color.Gray) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors( 
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        disabledContainerColor = Color.White,
                        cursorColor = Color.Black, 
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black,
                    ),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Guide", style = MaterialTheme.typography.labelMedium, color = Color.Black.copy(alpha = 0.7f)) 
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = guideInput,
                    onValueChange = { guideInput = it },
                    placeholder = { Text("Write a guide for an LLM on how it should perform certain tasks to be successful", color = Color.Gray) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        disabledContainerColor = Color.White,
                        cursorColor = Color.Black, 
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black,
                    ),
                    minLines = 5
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { onSaveClicked(titleInput, guideInput, entry) },
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("Save") }
            }
        }
    }
}

@Preview(showBackground = true, name = "EditEntryPopup New")
@Composable
fun EditEntryPopupNewPreview() {
    MaterialTheme {
        EditEntryPopup(entry = null, onDismissRequest = {}, onSaveClicked = { _, _, _ -> })
    }
}

@Preview(showBackground = true, name = "EditEntryPopup Edit")
@Composable
fun EditEntryPopupEditPreview() {
    MaterialTheme {
        EditEntryPopup(entry = SystemMessageEntry("Existing Title", "Existing Guide"), onDismissRequest = {}, onSaveClicked = { _, _, _ -> })
    }
}

val SystemMessageEntrySaver = Saver<SystemMessageEntry?, List<String?>>(
    save = { entry -> if (entry == null) listOf(null, null) else listOf(entry.title, entry.guide) },
    restore = { list ->
        val title = list[0]
        val guide = list[1]
        if (title != null && guide != null) SystemMessageEntry(title, guide) else null
    }
)

@Composable
@Preview(showSystemUi = true)
fun PhotoReasoningScreenPreviewEmpty() {
    MaterialTheme {
        PhotoReasoningScreen(
            isKeyboardOpen = false,
            onStopClicked = {},
            isInitialized = true
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DatabaseListPopupPreview() {
    MaterialTheme {
        DatabaseListPopup(
            onDismissRequest = {},
            entries = listOf(
                SystemMessageEntry("Title 1", "Guide for prompt 1"),
                SystemMessageEntry("Title 2", "Another guide for prompt 2"),
                SystemMessageEntry("Title 3", "Yet another guide for prompt 3.")
            ),
            onNewClicked = {},
            onEntryClicked = {},
            onDeleteClicked = {},
            onImportCompleted = {}
        )
    }
}

@Preview(showBackground = true, name = "DatabaseListPopup Empty")
@Composable
fun DatabaseListPopupEmptyPreview() {
    MaterialTheme {
        DatabaseListPopup(onDismissRequest = {}, entries = emptyList(), onNewClicked = {}, onEntryClicked = {}, onDeleteClicked = {}, onImportCompleted = {})
    }
}

@Preview(showBackground = true, name = "Stop Button Preview")
@Composable
fun StopButtonPreview() {
    MaterialTheme {
        StopButton {}
    }
}
