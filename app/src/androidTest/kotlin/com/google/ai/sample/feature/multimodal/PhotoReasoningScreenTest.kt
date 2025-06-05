package com.google.ai.sample.feature.multimodal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.sample.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.mockk
import io.mockk.verify

@RunWith(AndroidJUnit4::class)
class PhotoReasoningScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val mockOnReasonClicked: (String, List<android.net.Uri>) -> Unit = mockk(relaxed = true)
    private val mockOnSystemMessageChanged: (String) -> Unit = mockk(relaxed = true)
    private val mockOnEnableAccessibilityService: () -> Unit = mockk(relaxed = true)
    private val mockOnClearChatHistory: () -> Unit = mockk(relaxed = true)
    private val mockOnStopClicked: () -> Unit = mockk(relaxed = true)


    @Test
    fun stopButton_displayed_when_uiState_isLoading() {
        composeTestRule.setContent {
            PhotoReasoningScreen(
                uiState = PhotoReasoningUiState.Loading,
                onReasonClicked = mockOnReasonClicked,
                onSystemMessageChanged = mockOnSystemMessageChanged,
                onEnableAccessibilityService = mockOnEnableAccessibilityService,
                onClearChatHistory = mockOnClearChatHistory,
                isKeyboardOpen = false,
                onStopClicked = mockOnStopClicked
            )
        }
        composeTestRule.onNodeWithText("Stop").assertIsDisplayed()
    }

    @Test
    fun stopButton_displayed_when_commandExecutionStatus_isNotEmpty() {
        composeTestRule.setContent {
            PhotoReasoningScreen(
                uiState = PhotoReasoningUiState.Success("Some output"),
                commandExecutionStatus = "Executing command...",
                onReasonClicked = mockOnReasonClicked,
                onSystemMessageChanged = mockOnSystemMessageChanged,
                onEnableAccessibilityService = mockOnEnableAccessibilityService,
                onClearChatHistory = mockOnClearChatHistory,
                isKeyboardOpen = false,
                onStopClicked = mockOnStopClicked
            )
        }
        composeTestRule.onNodeWithText("Stop").assertIsDisplayed()
    }

    @Test
    fun regularInputBanner_hidden_when_stopButton_isVisible_dueToLoading() {
        composeTestRule.setContent {
            PhotoReasoningScreen(
                uiState = PhotoReasoningUiState.Loading,
                onReasonClicked = mockOnReasonClicked,
                onSystemMessageChanged = mockOnSystemMessageChanged,
                onEnableAccessibilityService = mockOnEnableAccessibilityService,
                onClearChatHistory = mockOnClearChatHistory,
                isKeyboardOpen = false,
                onStopClicked = mockOnStopClicked
            )
        }
        // Assert that input elements are not displayed
        // Using string resources for labels/placeholders if available, otherwise direct text
        composeTestRule.onNodeWithText(androidx.compose.ui.R.string.search_bar_search).assertDoesNotExist() // Placeholder for text field
        composeTestRule.onNodeWithText("Add Image").assertDoesNotExist() // Or R.string.add_image if defined
        composeTestRule.onNodeWithText("New").assertDoesNotExist()
        composeTestRule.onNodeWithText("Send").assertDoesNotExist() // Or R.string.action_go if defined
    }

    @Test
    fun regularInputBanner_hidden_when_stopButton_isVisible_dueToCommandExecution() {
        composeTestRule.setContent {
            PhotoReasoningScreen(
                uiState = PhotoReasoningUiState.Success("Output"),
                commandExecutionStatus = "Executing...",
                onReasonClicked = mockOnReasonClicked,
                onSystemMessageChanged = mockOnSystemMessageChanged,
                onEnableAccessibilityService = mockOnEnableAccessibilityService,
                onClearChatHistory = mockOnClearChatHistory,
                isKeyboardOpen = false,
                onStopClicked = mockOnStopClicked
            )
        }
        composeTestRule.onNodeWithText(androidx.compose.ui.R.string.search_bar_search).assertDoesNotExist()
        composeTestRule.onNodeWithText("Add Image").assertDoesNotExist()
        composeTestRule.onNodeWithText("New").assertDoesNotExist()
        composeTestRule.onNodeWithText("Send").assertDoesNotExist()
    }


    @Test
    fun regularInputBanner_visible_when_stopButton_isNotVisible_InitialState() {
        composeTestRule.setContent {
            PhotoReasoningScreen(
                uiState = PhotoReasoningUiState.Initial,
                commandExecutionStatus = "", // Important: no command execution
                onReasonClicked = mockOnReasonClicked,
                onSystemMessageChanged = mockOnSystemMessageChanged,
                onEnableAccessibilityService = mockOnEnableAccessibilityService,
                onClearChatHistory = mockOnClearChatHistory,
                isKeyboardOpen = false,
                onStopClicked = mockOnStopClicked
            )
        }
        composeTestRule.onNodeWithText("Stop").assertDoesNotExist()
        // Check for one of the prominent elements in the input banner
        // The text field might be identified by its label or placeholder from R.string
        // For now, let's assume R.string.reason_label is "Message" or similar
        // composeTestRule.onNodeWithText(getString(R.string.reason_label)).assertIsDisplayed()
         composeTestRule.onNodeWithText("Send", useUnmergedTree = true).assertIsDisplayed() // "Send" icon button
    }

    @Test
    fun regularInputBanner_visible_when_stopButton_isNotVisible_SuccessState() {
        composeTestRule.setContent {
            PhotoReasoningScreen(
                uiState = PhotoReasoningUiState.Success("Done"),
                commandExecutionStatus = "", // Important: no command execution
                onReasonClicked = mockOnReasonClicked,
                onSystemMessageChanged = mockOnSystemMessageChanged,
                onEnableAccessibilityService = mockOnEnableAccessibilityService,
                onClearChatHistory = mockOnClearChatHistory,
                isKeyboardOpen = false,
                onStopClicked = mockOnStopClicked
            )
        }
        composeTestRule.onNodeWithText("Stop").assertDoesNotExist()
        composeTestRule.onNodeWithText("Send", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun regularInputBanner_visible_when_stopButton_isNotVisible_ErrorState() {
        composeTestRule.setContent {
            PhotoReasoningScreen(
                uiState = PhotoReasoningUiState.Error("Some error"),
                commandExecutionStatus = "", // Important: no command execution
                onReasonClicked = mockOnReasonClicked,
                onSystemMessageChanged = mockOnSystemMessageChanged,
                onEnableAccessibilityService = mockOnEnableAccessibilityService,
                onClearChatHistory = mockOnClearChatHistory,
                isKeyboardOpen = false,
                onStopClicked = mockOnStopClicked
            )
        }
        composeTestRule.onNodeWithText("Stop").assertDoesNotExist()
        composeTestRule.onNodeWithText("Send", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun regularInputBanner_visible_when_stopButton_isNotVisible_StoppedState() {
        composeTestRule.setContent {
            PhotoReasoningScreen(
                uiState = PhotoReasoningUiState.Stopped,
                commandExecutionStatus = "", // After stopping, status might be empty or "Stopped."
                                            // Assuming it becomes empty for this test of banner visibility.
                onReasonClicked = mockOnReasonClicked,
                onSystemMessageChanged = mockOnSystemMessageChanged,
                onEnableAccessibilityService = mockOnEnableAccessibilityService,
                onClearChatHistory = mockOnClearChatHistory,
                isKeyboardOpen = false,
                onStopClicked = mockOnStopClicked
            )
        }
        composeTestRule.onNodeWithText("Stop").assertDoesNotExist()
        composeTestRule.onNodeWithText("Send", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun stopButton_click_invokes_onStopClickedLambda() {
        composeTestRule.setContent {
            PhotoReasoningScreen(
                uiState = PhotoReasoningUiState.Loading, // To make stop button visible
                onReasonClicked = mockOnReasonClicked,
                onSystemMessageChanged = mockOnSystemMessageChanged,
                onEnableAccessibilityService = mockOnEnableAccessibilityService,
                onClearChatHistory = mockOnClearChatHistory,
                isKeyboardOpen = false,
                onStopClicked = mockOnStopClicked // The mock we want to verify
            )
        }
        composeTestRule.onNodeWithText("Stop").performClick()
        verify(exactly = 1) { mockOnStopClicked() }
    }
}
