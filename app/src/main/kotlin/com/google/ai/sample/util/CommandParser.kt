package com.google.ai.sample.util

import android.util.Log

/**
 * Command parser for extracting commands from AI responses
 */
object CommandParser {
    private const val TAG = "CommandParser"
    
    // Regex patterns for different command formats
    
    // Click button patterns - significantly expanded to catch more variations
    private val CLICK_BUTTON_PATTERNS = listOf(
        // Standard patterns with quotes
        Regex("(?i)\\b(?:click|tap|press|klick|tippe auf|drücke|klicke auf|drücke auf) (?:on )?(?:the )?(?:button|knopf|schaltfläche|button labeled|knopf mit text|schaltfläche mit text)? [\"']([^\"']+)[\"']"),
        Regex("(?i)\\b(?:click|tap|press|klick|tippe auf|drücke|klicke auf|drücke auf) (?:on )?(?:the )?[\"']([^\"']+)[\"'] (?:button|knopf|schaltfläche)?"),
        
        // Patterns without quotes
        Regex("(?i)\\b(?:click|tap|press|klick|tippe auf|drücke|klicke auf|drücke auf) (?:on )?(?:the )?(?:button|knopf|schaltfläche) ([\\w\\s\\-]+)\\b"),
        Regex("(?i)\\b(?:click|tap|press|klick|tippe auf|drücke|klicke auf|drücke auf) (?:on )?(?:the )?(?:button|knopf|schaltfläche) labeled ([\\w\\s\\-]+)\\b"),
        
        // Direct command patterns
        Regex("(?i)\\b(?:click|tap|press|klick|tippe auf|drücke|klicke auf|drücke auf) ([\\w\\s\\-]+) (?:button|knopf|schaltfläche)\\b"),
        
        // Function-like patterns
        Regex("(?i)\\bclickOnButton\\([\"']([^\"']+)[\"']\\)"),
        Regex("(?i)\\btapOnButton\\([\"']([^\"']+)[\"']\\)"),
        Regex("(?i)\\bpressButton\\([\"']([^\"']+)[\"']\\)")
    )
    
    // Tap coordinates patterns - expanded to catch more variations
    private val TAP_COORDINATES_PATTERNS = listOf(
        // Standard patterns
        Regex("(?i)\\b(?:tap|click|press|tippe|klicke|tippe auf|klicke auf) (?:at|on|auf) (?:coordinates?|koordinaten|position|stelle|punkt)[:\\s]\\s*\\(?\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*\\)?"),
        Regex("(?i)\\b(?:tap|click|press|tippe|klicke|tippe auf|klicke auf) (?:at|on|auf) \\(?\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*\\)?"),
        
        // Function-like patterns
        Regex("(?i)\\btapAtCoordinates\\(\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*\\)"),
        Regex("(?i)\\bclickAtPosition\\(\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*\\)"),
        Regex("(?i)\\btapAt\\(\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*\\)")
    )
    
    // Screenshot patterns - expanded for consistency
    private val TAKE_SCREENSHOT_PATTERNS = listOf(
        Regex("(?i)\\b(?:take|capture|make|nimm|erstelle|mache|nehme|erzeuge) (?:a |ein(?:e)? )?(?:screenshot|bildschirmfoto|bildschirmaufnahme|bildschirmabbild)"),
        Regex("(?i)\\btakeScreenshot\\(\\)"),
        Regex("(?i)\\bcaptureScreen\\(\\)")
    )
    
    // Home button patterns - for pressing the home button
    private val HOME_BUTTON_PATTERNS = listOf(
        // Function-like patterns
        Regex("(?i)\\bhome\\(\\)"),
        Regex("(?i)\\bpressHome\\(\\)"),
        Regex("(?i)\\bgoHome\\(\\)"),
        
        // Natural language patterns
        Regex("(?i)\\b(?:press|click|tap|go to|navigate to|return to|drücke|klicke|tippe auf|gehe zu|navigiere zu|kehre zurück zu) (?:the )?home(?: button| screen)?\\b"),
        Regex("(?i)\\b(?:zurück zum|zurück zur) (?:home|startseite|hauptbildschirm)\\b")
    )
    
    // Back button patterns - for pressing the back button
    private val BACK_BUTTON_PATTERNS = listOf(
        // Function-like patterns
        Regex("(?i)\\bback\\(\\)"),
        Regex("(?i)\\bpressBack\\(\\)"),
        Regex("(?i)\\bgoBack\\(\\)"),
        
        // Natural language patterns
        Regex("(?i)\\b(?:press|click|tap|go|navigate|return|drücke|klicke|tippe auf|gehe|navigiere|kehre) (?:the )?back(?: button)?\\b"),
        Regex("(?i)\\b(?:zurück|zurückgehen)\\b")
    )
    
    // Recent apps patterns - for showing recent apps
    private val RECENT_APPS_PATTERNS = listOf(
        // Function-like patterns
        Regex("(?i)\\brecentApps\\(\\)"),
        Regex("(?i)\\bshowRecentApps\\(\\)"),
        Regex("(?i)\\bopenRecentApps\\(\\)"),
        
        // Natural language patterns
        Regex("(?i)\\b(?:show|open|display|view|zeige|öffne|anzeigen) (?:the )?recent(?: apps| applications| tasks)?\\b"),
        Regex("(?i)\\b(?:letzte apps|letzte anwendungen|app übersicht|app-übersicht|übersicht)\\b")
    )
    
    // Scroll down patterns - for scrolling down
    private val SCROLL_DOWN_PATTERNS = listOf(
        // Function-like patterns
        Regex("(?i)\\bscrollDown\\(\\)"),
        Regex("(?i)\\bscrollDownPage\\(\\)"),
        Regex("(?i)\\bpageDown\\(\\)"),
        
        // Natural language patterns
        Regex("(?i)\\b(?:scroll|swipe|move|nach unten|runter) (?:down|nach unten|runter)\\b"),
        Regex("(?i)\\b(?:nach unten scrollen|runter scrollen|nach unten wischen|runter wischen)\\b")
    )
    
    // Scroll up patterns - for scrolling up
    private val SCROLL_UP_PATTERNS = listOf(
        // Function-like patterns
        Regex("(?i)\\bscrollUp\\(\\)"),
        Regex("(?i)\\bscrollUpPage\\(\\)"),
        Regex("(?i)\\bpageUp\\(\\)"),
        
        // Natural language patterns
        Regex("(?i)\\b(?:scroll|swipe|move|nach oben|hoch) (?:up|nach oben|hoch)\\b"),
        Regex("(?i)\\b(?:nach oben scrollen|hoch scrollen|nach oben wischen|hoch wischen)\\b")
    )
    
    // Buffer for storing partial text between calls
    private var textBuffer = ""
    
    // Flag to indicate if we should clear the buffer on next call
    private var shouldClearBuffer = false
    
    /**
     * Parse commands from the given text
     * 
     * @param text The text to parse for commands
     * @param clearBuffer Whether to clear the buffer before parsing (default: false)
     * @return A list of commands found in the text
     */
    fun parseCommands(text: String, clearBuffer: Boolean = false): List<Command> {
        val commands = mutableListOf<Command>()
        
        try {
            // Clear buffer if requested or if flag is set
            if (clearBuffer || shouldClearBuffer) {
                textBuffer = ""
                shouldClearBuffer = false
                Log.d(TAG, "Buffer cleared")
            }
            
            // Normalize the text (trim whitespace, normalize line breaks)
            val normalizedText = normalizeText(text)
            
            // Append to buffer
            textBuffer += normalizedText
            
            // Debug the buffer
            Log.d(TAG, "Current buffer for command parsing: $textBuffer")
            
            // Process the buffer line by line
            val lines = textBuffer.split("\n")
            
            // Process each line and the combined buffer
            processText(textBuffer, commands)
            
            // If we found commands, clear the buffer for next time
            if (commands.isNotEmpty()) {
                shouldClearBuffer = true
                Log.d(TAG, "Commands found, buffer will be cleared on next call")
            }
            
            Log.d(TAG, "Found ${commands.size} commands in text")
            
            // Debug each found command
            commands.forEach { command ->
                when (command) {
                    is Command.ClickButton -> Log.d(TAG, "Command details: ClickButton(\"${command.buttonText}\")")
                    is Command.TapCoordinates -> Log.d(TAG, "Command details: TapCoordinates(${command.x}, ${command.y})")
                    is Command.TakeScreenshot -> Log.d(TAG, "Command details: TakeScreenshot")
                    is Command.PressHomeButton -> Log.d(TAG, "Command details: PressHomeButton")
                    is Command.PressBackButton -> Log.d(TAG, "Command details: PressBackButton")
                    is Command.ShowRecentApps -> Log.d(TAG, "Command details: ShowRecentApps")
                    is Command.ScrollDown -> Log.d(TAG, "Command details: ScrollDown")
                    is Command.ScrollUp -> Log.d(TAG, "Command details: ScrollUp")
                    is Command.ScrollDownFromCoordinates -> Log.d(TAG, "Command details: ScrollDownFromCoordinates(${command.x}, ${command.y}, ${command.distance}, ${command.duration})")
                    is Command.ScrollUpFromCoordinates -> Log.d(TAG, "Command details: ScrollUpFromCoordinates(${command.x}, ${command.y}, ${command.distance}, ${command.duration})")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing commands: ${e.message}", e)
        }
        
        return commands
    }
    
    /**
     * Process text to find commands
     */
    private fun processText(text: String, commands: MutableList<Command>) {
        // Look for click button commands
        findClickButtonCommands(text, commands)
        
        // Look for tap coordinates commands
        findTapCoordinatesCommands(text, commands)
        
        // Look for take screenshot commands
        findTakeScreenshotCommands(text, commands)
        
        // Look for home button commands
        findHomeButtonCommands(text, commands)
        
        // Look for back button commands
        findBackButtonCommands(text, commands)
        
        // Look for recent apps commands
        findRecentAppsCommands(text, commands)
        
        // Look for scroll down commands
        findScrollDownCommands(text, commands)
        
        // Look for scroll up commands
        findScrollUpCommands(text, commands)
    }
    
    /**
     * Normalize text by trimming whitespace and normalizing line breaks
     */
    private fun normalizeText(text: String): String {
        // Replace multiple spaces with a single space
        var normalized = text.replace(Regex("\\s+"), " ")
        
        // Ensure consistent line breaks
        normalized = normalized.replace(Regex("\\r\\n|\\r"), "\n")
        
        return normalized
    }
    
    /**
     * Find click button commands in the text
     */
    private fun findClickButtonCommands(text: String, commands: MutableList<Command>) {
        // Try each pattern
        for (pattern in CLICK_BUTTON_PATTERNS) {
            val matches = pattern.findAll(text)
            for (match in matches) {
                try {
                    if (match.groupValues.size > 1) {
                        val buttonText = match.groupValues[1].trim()
                        if (buttonText.isNotEmpty()) {
                            // Check if this command is already in the list (avoid duplicates)
                            if (!commands.any { it is Command.ClickButton && it.buttonText == buttonText }) {
                                Log.d(TAG, "Found click button command with pattern ${pattern.pattern}: \"$buttonText\"")
                                commands.add(Command.ClickButton(buttonText))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing click button match: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * Find tap coordinates commands in the text
     */
    private fun findTapCoordinatesCommands(text: String, commands: MutableList<Command>) {
        // Try each pattern
        for (pattern in TAP_COORDINATES_PATTERNS) {
            val matches = pattern.findAll(text)
            for (match in matches) {
                try {
                    if (match.groupValues.size > 2) {
                        val x = match.groupValues[1].trim().toFloat()
                        val y = match.groupValues[2].trim().toFloat()
                        
                        // Check if this command is already in the list (avoid duplicates)
                        if (!commands.any { it is Command.TapCoordinates && it.x == x && it.y == y }) {
                            Log.d(TAG, "Found tap coordinates command with pattern ${pattern.pattern}: ($x, $y)")
                            commands.add(Command.TapCoordinates(x, y))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing tap coordinates match: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * Find take screenshot commands in the text
     */
    private fun findTakeScreenshotCommands(text: String, commands: MutableList<Command>) {
        // Try each pattern
        for (pattern in TAKE_SCREENSHOT_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                // Check if this command is already in the list (avoid duplicates)
                if (!commands.any { it is Command.TakeScreenshot }) {
                    Log.d(TAG, "Found take screenshot command with pattern ${pattern.pattern}")
                    commands.add(Command.TakeScreenshot)
                    // Only add one screenshot command even if multiple matches are found
                    break
                }
            }
        }
    }
    
    /**
     * Find home button commands in the text
     */
    private fun findHomeButtonCommands(text: String, commands: MutableList<Command>) {
        // Try each pattern
        for (pattern in HOME_BUTTON_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                // Check if this command is already in the list (avoid duplicates)
                if (!commands.any { it is Command.PressHomeButton }) {
                    Log.d(TAG, "Found home button command with pattern ${pattern.pattern}")
                    commands.add(Command.PressHomeButton)
                    // Only add one home button command even if multiple matches are found
                    break
                }
            }
        }
    }
    
    /**
     * Find back button commands in the text
     */
    private fun findBackButtonCommands(text: String, commands: MutableList<Command>) {
        // Try each pattern
        for (pattern in BACK_BUTTON_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                // Check if this command is already in the list (avoid duplicates)
                if (!commands.any { it is Command.PressBackButton }) {
                    Log.d(TAG, "Found back button command with pattern ${pattern.pattern}")
                    commands.add(Command.PressBackButton)
                    // Only add one back button command even if multiple matches are found
                    break
                }
            }
        }
    }
    
    /**
     * Find recent apps commands in the text
     */
    private fun findRecentAppsCommands(text: String, commands: MutableList<Command>) {
        // Try each pattern
        for (pattern in RECENT_APPS_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                // Check if this command is already in the list (avoid duplicates)
                if (!commands.any { it is Command.ShowRecentApps }) {
                    Log.d(TAG, "Found recent apps command with pattern ${pattern.pattern}")
                    commands.add(Command.ShowRecentApps)
                    // Only add one recent apps command even if multiple matches are found
                    break
                }
            }
        }
    }
    
    /**
     * Find scroll down commands in the text
     */
    private fun findScrollDownCommands(text: String, commands: MutableList<Command>) {
        // First check for coordinate-based scroll down commands
        val coordPattern = Regex("(?i)\\bscrollDown\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)")
        val matches = coordPattern.findAll(text)
        
        for (match in matches) {
            if (match.groupValues.size >= 5) {
                try {
                    val x = match.groupValues[1].toFloat()
                    val y = match.groupValues[2].toFloat()
                    val distance = match.groupValues[3].toFloat()
                    val duration = match.groupValues[4].toLong()
                    
                    Log.d(TAG, "Found coordinate-based scroll down command: scrollDown($x, $y, $distance, $duration)")
                    commands.add(Command.ScrollDownFromCoordinates(x, y, distance, duration))
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing coordinate-based scroll down command: ${e.message}")
                }
            }
        }
        
        // If no coordinate-based commands were found, look for simple scroll down commands
        if (!commands.any { it is Command.ScrollDownFromCoordinates }) {
            // Try each pattern
            for (pattern in SCROLL_DOWN_PATTERNS) {
                if (pattern.containsMatchIn(text)) {
                    // Check if this command is already in the list (avoid duplicates)
                    if (!commands.any { it is Command.ScrollDown }) {
                        Log.d(TAG, "Found scroll down command with pattern ${pattern.pattern}")
                        commands.add(Command.ScrollDown)
                        // Only add one scroll down command even if multiple matches are found
                        break
                    }
                }
            }
        }
    }
    
    /**
     * Find scroll up commands in the text
     */
    private fun findScrollUpCommands(text: String, commands: MutableList<Command>) {
        // First check for coordinate-based scroll up commands
        val coordPattern = Regex("(?i)\\bscrollUp\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)")
        val matches = coordPattern.findAll(text)
        
        for (match in matches) {
            if (match.groupValues.size >= 5) {
                try {
                    val x = match.groupValues[1].toFloat()
                    val y = match.groupValues[2].toFloat()
                    val distance = match.groupValues[3].toFloat()
                    val duration = match.groupValues[4].toLong()
                    
                    Log.d(TAG, "Found coordinate-based scroll up command: scrollUp($x, $y, $distance, $duration)")
                    commands.add(Command.ScrollUpFromCoordinates(x, y, distance, duration))
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing coordinate-based scroll up command: ${e.message}")
                }
            }
        }
        
        // If no coordinate-based commands were found, look for simple scroll up commands
        if (!commands.any { it is Command.ScrollUpFromCoordinates }) {
            // Try each pattern
            for (pattern in SCROLL_UP_PATTERNS) {
                if (pattern.containsMatchIn(text)) {
                    // Check if this command is already in the list (avoid duplicates)
                    if (!commands.any { it is Command.ScrollUp }) {
                        Log.d(TAG, "Found scroll up command with pattern ${pattern.pattern}")
                        commands.add(Command.ScrollUp)
                        // Only add one scroll up command even if multiple matches are found
                        break
                    }
                }
            }
        }
    }
    
    /**
     * Clear the text buffer
     */
    fun clearBuffer() {
        textBuffer = ""
        shouldClearBuffer = false
        Log.d(TAG, "Buffer manually cleared")
    }
    
    /**
     * Debug method to test if a specific command would be recognized
     */
    fun testCommandRecognition(commandText: String): List<Command> {
        Log.d(TAG, "Testing command recognition for: \"$commandText\"")
        
        // Clear buffer for testing
        clearBuffer()
        
        val commands = parseCommands(commandText)
        Log.d(TAG, "Recognition test result: ${commands.size} commands found")
        return commands
    }
    
    /**
     * Get the current buffer content (for debugging)
     */
    fun getBufferContent(): String {
        return textBuffer
    }
}

/**
 * Sealed class representing different types of commands
 */
sealed class Command {
    /**
     * Command to click a button with the specified text
     */
    data class ClickButton(val buttonText: String) : Command()
    
    /**
     * Command to tap at the specified coordinates
     */
    data class TapCoordinates(val x: Float, val y: Float) : Command()
    
    /**
     * Command to take a screenshot
     */
    object TakeScreenshot : Command()
    
    /**
     * Command to press the home button
     */
    object PressHomeButton : Command()
    
    /**
     * Command to press the back button
     */
    object PressBackButton : Command()
    
    /**
     * Command to show recent apps
     */
    object ShowRecentApps : Command()
    
    /**
     * Command to scroll down
     */
    object ScrollDown : Command()
    
    /**
     * Command to scroll up
     */
    object ScrollUp : Command()
    
    /**
     * Command to scroll down from specific coordinates with custom distance and duration
     */
    data class ScrollDownFromCoordinates(val x: Float, val y: Float, val distance: Float, val duration: Long) : Command()
    
    /**
     * Command to scroll up from specific coordinates with custom distance and duration
     */
    data class ScrollUpFromCoordinates(val x: Float, val y: Float, val distance: Float, val duration: Long) : Command()
}
