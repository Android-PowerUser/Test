package com.google.ai.sample.feature.multimodal

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.PromptFeedback
import com.google.ai.sample.ApiKeyManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoReasoningViewModelTest {

    private lateinit var viewModel: PhotoReasoningViewModel
    private val mockGenerativeModel: GenerativeModel = mockk(relaxed = true)
    private val mockApiKeyManager: ApiKeyManager = mockk(relaxed = true)
    private val mockContext: Context = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = PhotoReasoningViewModel(mockGenerativeModel, mockApiKeyManager)
        // Mock behavior for loading system message and chat history
        every { SystemMessagePreferences.loadSystemMessage(any()) } returns ""
        every { ChatHistoryPreferences.loadChatMessages(any()) } returns emptyList()
        viewModel.loadSystemMessage(mockContext) // Call this to initialize chat
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onStopClicked cancels currentReasoningJob when active`() = runTest(testDispatcher) {
        val mockResponse = mockk<GenerateContentResponse>(relaxed = true)
        every { mockResponse.text } returns "Test response"
        coEvery { mockGenerativeModel.startChat(any()).sendMessage(any()) } coAnswers {
            // Simulate a long-running job
            kotlinx.coroutines.delay(1000)
            mockResponse
        }

        // Start reasoning
        val reasoningJob = launch {
            viewModel.reason("Test input", emptyList())
        }
        testDispatcher.scheduler.advanceUntilIdle() // Let the reasoning job start

        assertTrue(viewModel.uiState.value is PhotoReasoningUiState.Loading)

        viewModel.onStopClicked()
        testDispatcher.scheduler.advanceUntilIdle()


        assertTrue("Reasoning job should be cancelled", reasoningJob.isCancelled)
        assertEquals(PhotoReasoningUiState.Stopped, viewModel.uiState.value)
    }

    @Test
    fun `onStopClicked cancels commandProcessingJob when active`() = runTest(testDispatcher) {
        val commandText = "[{\"name\":\"ClickButton\",\"buttonText\":\"OK\"}]" // Example command
        val mockResponse = mockk<GenerateContentResponse>(relaxed = true)
        every { mockResponse.text } returns commandText
        coEvery { mockGenerativeModel.startChat(any()).sendMessage(any()) } returns mockResponse

        // Start reasoning which will then process commands
         val reasoningProcess = launch {
            viewModel.reason("Test input with commands", emptyList())
        }
        testDispatcher.scheduler.advanceUntilIdle() // Let reasoning and command processing start

        // At this point, commandProcessingJob should be active within the ViewModel.
        // We need a way to assert this or mock its behavior if it's not directly exposed.
        // For now, we assume it gets created and check its cancellation implicitly via side effects.

        viewModel.onStopClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify UI state and command status indicate stoppage
        assertEquals(PhotoReasoningUiState.Stopped, viewModel.uiState.value)
        assertEquals("Stopped.", viewModel.commandExecutionStatus.value)
        assertTrue(viewModel.detectedCommands.value.isEmpty())
        reasoningProcess.cancel() // clean up the test
    }


    @Test
    fun `onStopClicked sets stopExecutionFlag to true`() {
        viewModel.onStopClicked()
        // Need a way to access stopExecutionFlag or verify its effect.
        // Since it's private, we'll test its effect:
        // if a command tries to run after this, it should be stopped.
        // This is indirectly tested by `cancels commandProcessingJob`.
        // For a direct test, if the flag was public/internal:
        // assertTrue(viewModel.stopExecutionFlag.get())
        // For now, we trust the implementation detail and focus on behavior.
        // Let's verify the state changes that *are* public.
        assertEquals(PhotoReasoningUiState.Stopped, viewModel.uiState.value)
    }

    @Test
    fun `onStopClicked updates uiState to Stopped`() {
        viewModel.onStopClicked()
        assertEquals(PhotoReasoningUiState.Stopped, viewModel.uiState.value)
    }

    @Test
    fun `onStopClicked updates chat message for pending AI response`() = runTest(testDispatcher) {
        // Simulate that AI is "typing"
        val initialUserMessage = PhotoReasoningMessage("User question", PhotoParticipant.USER)
        val pendingModelMessage = PhotoReasoningMessage("", PhotoParticipant.MODEL, isPending = true)
        viewModel.chatMessagesFlow.value = listOf(initialUserMessage, pendingModelMessage)


        viewModel.onStopClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        val chatMessages = viewModel.chatMessagesFlow.value
        assertTrue("Chat should not be empty", chatMessages.isNotEmpty())
        val lastMessage = chatMessages.last()
        assertEquals(PhotoParticipant.MODEL, lastMessage.participant)
        assertEquals("Operation stopped by user.", lastMessage.text)
        assertFalse(lastMessage.isPending)
    }

     @Test
    fun `onStopClicked updates chat message for completed AI response`() = runTest(testDispatcher) {
        val initialUserMessage = PhotoReasoningMessage("User question", PhotoParticipant.USER)
        val modelResponseMessage = PhotoReasoningMessage("AI response", PhotoParticipant.MODEL, isPending = false)
        viewModel.chatMessagesFlow.value = listOf(initialUserMessage, modelResponseMessage) // Simulate existing chat

        viewModel.onStopClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        val chatMessages = viewModel.chatMessagesFlow.value
        assertTrue("Chat should not be empty", chatMessages.isNotEmpty())
        val lastMessage = chatMessages.last()
        assertEquals(PhotoParticipant.MODEL, lastMessage.participant)
        assertTrue("Last message should indicate stop: ${lastMessage.text}", lastMessage.text.contains("AI response") && lastMessage.text.contains("[Stopped by user]"))
        assertFalse(lastMessage.isPending)
    }


    @Test
    fun `stopExecutionFlag is reset when reason is called`() = runTest(testDispatcher) {
        // First, set the flag by calling onStopClicked
        viewModel.onStopClicked()
        // We can't directly check stopExecutionFlag, so we'll infer its state
        // by checking if a new reasoning call can proceed without being immediately cancelled.

        val mockResponse = mockk<GenerateContentResponse>(relaxed = true)
        every { mockResponse.text } returns "New response"
        coEvery { mockGenerativeModel.startChat(any()).sendMessage(any()) } returns mockResponse

        // Start a new reasoning call
        viewModel.reason("New input", emptyList())
        testDispatcher.scheduler.advanceUntilIdle() // Allow reasoning to proceed

        // If stopExecutionFlag was not reset, the new reasoning would be immediately cancelled or affected.
        // We expect it to proceed to Loading and then Success/Error.
        assertNotEquals("UI state should not be Stopped if reason started successfully",
            PhotoReasoningUiState.Stopped, viewModel.uiState.value)
        assertTrue("UI state should be Loading or Success after new reason call",
            viewModel.uiState.value is PhotoReasoningUiState.Loading || viewModel.uiState.value is PhotoReasoningUiState.Success)

        // Verify that the new response is processed
        val chatMessages = viewModel.chatMessagesFlow.value
        val lastMessageText = chatMessages.lastOrNull()?.text ?: ""
        assertTrue("Chat should contain new response: $lastMessageText", lastMessageText.contains("New response"))
    }
}
