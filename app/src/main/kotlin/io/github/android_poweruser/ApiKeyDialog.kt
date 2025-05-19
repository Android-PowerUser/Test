package io.github.android_poweruser

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Dialog for API key input and management
 */
@Composable
fun ApiKeyDialog(
    apiKeyManager: ApiKeyManager,
    isFirstLaunch: Boolean = false,
    onDismiss: () -> Unit
) {
    var apiKeyInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    val apiKeys = remember { mutableStateListOf<String>() }
    var selectedKeyIndex by remember { mutableStateOf(apiKeyManager.getCurrentKeyIndex()) }
    val context = LocalContext.current
    
    // Load existing keys
    LaunchedEffect(Unit) {
        apiKeys.clear()
        apiKeys.addAll(apiKeyManager.getApiKeys())
    }
    
    Dialog(onDismissRequest = {
        // Only allow dismissal if not first launch or if keys exist
        if (!isFirstLaunch || apiKeys.isNotEmpty()) {
            onDismiss()
        }
    }) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = if (isFirstLaunch) "API Key Required" else "Manage API Keys",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                if (isFirstLaunch && apiKeys.isEmpty()) {
                    Text(
                        text = "Please enter a Gemini API key to use this application.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
                
                // Get API Key button
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://makersuite.google.com/app/apikey"))
                        context.startActivity(intent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Text("Get API Key")
                }
                
                // Input field for new API key
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { 
                        apiKeyInput = it
                        errorMessage = ""
                    },
                    label = { Text("Enter API Key") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    singleLine = true
                )
                
                // Error message
                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                
                // Add button
                Button(
                    onClick = {
                        if (apiKeyInput.isBlank()) {
                            errorMessage = "API key cannot be empty"
                            return@Button
                        }
                        
                        val added = apiKeyManager.addApiKey(apiKeyInput)
                        if (added) {
                            apiKeys.clear()
                            apiKeys.addAll(apiKeyManager.getApiKeys())
                            apiKeyInput = ""
                            selectedKeyIndex = apiKeyManager.getCurrentKeyIndex()
                        } else {
                            errorMessage = "API key already exists"
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(bottom = 16.dp)
                ) {
                    Text("Add Key")
                }
                
                // List of existing keys
                if (apiKeys.isNotEmpty()) {
                    Text(
                        text = "Saved API Keys",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                    ) {
                        itemsIndexed(apiKeys) { index, key ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = index == selectedKeyIndex,
                                    onClick = {
                                        apiKeyManager.setCurrentKeyIndex(index)
                                        selectedKeyIndex = index
                                    }
                                )
                                
                                Text(
                                    text = key.take(10) + "..." + key.takeLast(5),
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 8.dp)
                                )
                                
                                IconButton(
                                    onClick = {
                                        apiKeyManager.removeApiKey(key)
                                        apiKeys.clear()
                                        apiKeys.addAll(apiKeyManager.getApiKeys())
                                        selectedKeyIndex = apiKeyManager.getCurrentKeyIndex()
                                    }
                                ) {
                                    Text("✕", textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
                
                // Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (!isFirstLaunch || apiKeys.isNotEmpty()) {
                        TextButton(
                            onClick = onDismiss
                        ) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}
