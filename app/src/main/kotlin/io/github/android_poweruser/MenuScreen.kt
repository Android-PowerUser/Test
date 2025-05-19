package com.google.ai.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.sp
import android.widget.Toast

data class MenuItem(
    val routeId: String,
    val titleResId: Int,
    val descriptionResId: Int
)

@Composable
fun MenuScreen(
    onItemClicked: (String) -> Unit = { },
    onApiKeyButtonClicked: () -> Unit = { },
    onDonationButtonClicked: () -> Unit = { },
    isTrialExpired: Boolean = false // New parameter to indicate trial status
) {
    val context = LocalContext.current
    val menuItems = listOf(
        MenuItem("photo_reasoning", R.string.menu_reason_title, R.string.menu_reason_description)
    )

    // Get current model
    val currentModel = GenerativeAiViewModelFactory.getCurrentModel()
    var selectedModel by remember { mutableStateOf(currentModel) }
    var expanded by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier
            .padding(top = 16.dp, bottom = 16.dp)
    ) {
        // API Key Management Button
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .padding(all = 16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "API Key Management",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onApiKeyButtonClicked() },
                        enabled = true, // Always enabled
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(text = "Change API Key")
                    }
                }
            }
        }

        // Model Selection
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(all = 16.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = "Model Selection",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Current model: ${selectedModel.displayName}",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.weight(1f))

                        Button(
                            onClick = { expanded = true },
                            enabled = true // Always enabled
                        ) {
                            Text("Change Model")
                        }

                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            val orderedModels = listOf(
                                ModelOption.GEMINI_FLASH_LITE,
                                ModelOption.GEMINI_FLASH,
                                ModelOption.GEMINI_FLASH_PREVIEW,
                                ModelOption.GEMINI_PRO
                            )

                            orderedModels.forEach { modelOption ->
                                DropdownMenuItem(
                                    text = { Text(modelOption.displayName) },
                                    onClick = {
                                        selectedModel = modelOption
                                        GenerativeAiViewModelFactory.setModel(modelOption)
                                        expanded = false
                                    },
                                    enabled = true // Always enabled
                                )
                            }
                        }
                    }
                }
            }
        }

        // Menu Items
        items(menuItems) { menuItem ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(all = 16.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(menuItem.titleResId),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(menuItem.descriptionResId),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    TextButton(
                        onClick = {
                            if (isTrialExpired) {
                                Toast.makeText(context, "Bitte abonnieren Sie die App, um fortzufahren.", Toast.LENGTH_LONG).show()
                            } else {
                                onItemClicked(menuItem.routeId)
                            }
                        },
                        enabled = !isTrialExpired, // Disable button if trial is expired
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(text = stringResource(R.string.action_try))
                    }
                }
            }
        }

        // Donation Button Card (Should always be enabled)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .padding(all = 16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Support more Features",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onDonationButtonClicked, // This button should always be active
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(text = "Pro (2,90 €/Month)")
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                val annotatedText = buildAnnotatedString {
                    append("Screenshots are saved in Pictures/Screenshots and you should delete them afterwards. Google has discontinued free API access to Gemini 2.5 Pro without a deposited billing account. There are rate limits for free use of Gemini models. The less powerful the models are, the more you can use them. The limits range from a maximum of 10 to 30 calls per minute. After each screenshot (every 2-3 seconds) the LLM must respond again. More information is available at ")

                    pushStringAnnotation(tag = "URL", annotation = "https://ai.google.dev/gemini-api/docs/rate-limits")
                    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)) {
                        append("https://ai.google.dev/gemini-api/docs/rate-limits")
                    }
                    pop()
                }

                val uriHandler = LocalUriHandler.current

                ClickableText(
                    text = annotatedText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(all = 16.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    onClick = { offset ->
                        // Allow clicking links even if trial is expired
                        annotatedText.getStringAnnotations(tag = "URL", start = offset, end = offset)
                            .firstOrNull()?.let { annotation ->
                                uriHandler.openUri(annotation.item)
                            }
                    }
                )
            }
        }
    }
}

@Preview(showSystemUi = true)
@Composable
fun MenuScreenPreview() {
    // Preview with trial not expired
    MenuScreen(isTrialExpired = false)
}

@Preview(showSystemUi = true)
@Composable
fun MenuScreenTrialExpiredPreview() {
    // Preview with trial expired
    MenuScreen(isTrialExpired = true)
}

