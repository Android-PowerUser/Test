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
    
    // Home button patterns
    private val PRESS_HOME_PATTERNS = listOf(
        Regex("(?i)\\b(?:press|click|tap|go to|navigate to|drücke|klicke|tippe auf|gehe zu) (?:the )?(?:home|home button|home screen|startseite|home-taste|home-bildschirm|startbildschirm)\\b"),
        Regex("(?i)\\b(?:return|go back|zurück) (?:to )?(?:the )?(?:home|home screen|startseite|startbildschirm)\\b"),
        Regex("(?i)\\bpressHome\\(\\)"),
        Regex("(?i)\\bgoHome\\(\\)")
    )
    
    // Back button patterns
    private val PRESS_BACK_PATTERNS = listOf(
        Regex("(?i)\\b(?:press|click|tap|go|navigate|drücke|klicke|tippe auf|gehe) (?:the )?(?:back|back button|zurück|zurück-taste|zurücktaste)\\b"),
        Regex("(?i)\\b(?:go back|navigate back|zurückgehen|zurück gehen|zurück navigieren)\\b"),
        Regex("(?i)\\bpressBack\\(\\)"),
        Regex("(?i)\\bgoBack\\(\\)")
    )
    
    // Recent apps patterns
    private val SHOW_RECENT_APPS_PATTERNS = listOf(
        Regex("(?i)\\b(?:show|open|display|view|zeige|öffne|anzeigen) (?:the )?(?:recent|recent apps|recent applications|recents|app overview|task manager|letzte|letzte apps|letzte anwendungen|app-übersicht|aufgabenmanager)\\b"),
        Regex("(?i)\\b(?:press|click|tap|drücke|klicke|tippe auf) (?:the )?(?:recent apps|recents|overview|letzte apps|übersicht) (?:button|key|taste|knopf)?\\b"),
        Regex("(?i)\\bshowRecentApps\\(\\)"),
        Regex("(?i)\\bopenRecentApps\\(\\)")
    )
    
    // Status bar down patterns
    private val PULL_STATUS_BAR_DOWN_PATTERNS = listOf(
        Regex("(?i)\\b(?:pull|swipe|drag|ziehe|wische) (?:down|herunter|nach unten) (?:the )?(?:status bar|notification bar|notifications|statusleiste|benachrichtigungsleiste|benachrichtigungen)\\b"),
        Regex("(?i)\\b(?:open|show|display|öffne|zeige) (?:the )?(?:status bar|notification bar|notifications|notification shade|notification panel|statusleiste|benachrichtigungsleiste|benachrichtigungen)\\b"),
        Regex("(?i)\\bpullStatusBarDown\\(\\)"),
        Regex("(?i)\\bopenNotifications\\(\\)")
    )
    
    // Status bar down twice patterns
    private val PULL_STATUS_BAR_DOWN_TWICE_PATTERNS = listOf(
        Regex("(?i)\\b(?:pull|swipe|drag|ziehe|wische) (?:down|herunter|nach unten) (?:the )?(?:status bar|notification bar|statusleiste|benachrichtigungsleiste) (?:twice|two times|2 times|zweimal|2 mal)\\b"),
        Regex("(?i)\\b(?:open|show|display|öffne|zeige) (?:the )?(?:quick settings|quick toggles|system toggles|schnelleinstellungen|systemeinstellungen)\\b"),
        Regex("(?i)\\bpullStatusBarDownTwice\\(\\)"),
        Regex("(?i)\\bopenQuickSettings\\(\\)")
    )
    
    // Status bar up patterns
    private val PUSH_STATUS_BAR_UP_PATTERNS = listOf(
        Regex("(?i)\\b(?:push|swipe|drag|close|schiebe|wische|schließe) (?:up|nach oben|hoch) (?:the )?(?:status bar|notification bar|notifications|statusleiste|benachrichtigungsleiste|benachrichtigungen)\\b"),
        Regex("(?i)\\b(?:close|dismiss|hide|schließe|verwerfe|verstecke) (?:the )?(?:status bar|notification bar|notifications|notification shade|notification panel|statusleiste|benachrichtigungsleiste|benachrichtigungen)\\b"),
        Regex("(?i)\\bpushStatusBarUp\\(\\)"),
        Regex("(?i)\\bcloseNotifications\\(\\)")
    )
    
    // Scroll up patterns
    private val SCROLL_UP_PATTERNS = listOf(
        Regex("(?i)\\b(?:scroll|swipe|flick|rolle|wische|scrolle) (?:up|upward|nach oben|aufwärts|hoch)\\b"),
        Regex("(?i)\\bscrollUp\\(\\)")
    )
    
    // Scroll down patterns
    private val SCROLL_DOWN_PATTERNS = listOf(
        Regex("(?i)\\b(?:scroll|swipe|flick|rolle|wische|scrolle) (?:down|downward|nach unten|abwärts|runter)\\b"),
        Regex("(?i)\\bscrollDown\\(\\)")
    )
    
    // Scroll left patterns
    private val SCROLL_LEFT_PATTERNS = listOf(
        Regex("(?i)\\b(?:scroll|swipe|flick|rolle|wische|scrolle) (?:left|leftward|nach links|linkswärts|links)\\b"),
        Regex("(?i)\\bscrollLeft\\(\\)")
    )
    
    // Scroll right patterns
    private val SCROLL_RIGHT_PATTERNS = listOf(
        Regex("(?i)\\b(?:scroll|swipe|flick|rolle|wische|scrolle) (?:right|rightward|nach rechts|rechtswärts|rechts)\\b"),
        Regex("(?i)\\bscrollRight\\(\\)")
    )
    
    // Open app patterns
    private val OPEN_APP_PATTERNS = listOf(
        Regex("(?i)\\b(?:open|launch|start|öffne|starte) (?:the )?(?:app|application|anwendung|applikation)(?: named| called| namens)? [\"']([^\"']+)[\"']"),
        Regex("(?i)\\b(?:open|launch|start|öffne|starte) (?:the )?[\"']([^\"']+)[\"'] (?:app|application|anwendung|applikation)"),
        Regex("(?i)\\b(?:open|launch|start|öffne|starte) (?:the )?(?:app|application|anwendung|applikation)(?: named| called| namens)? ([\\w\\s\\-]+)(?:\\s|$)"),
        Regex("(?i)\\bopenApp\\([\"']([^\"']+)[\"']\\)"),
        Regex("(?i)\\blaunchApp\\([\"']([^\"']+)[\"']\\)")
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
                    is Command.PressHome -> Log.d(TAG, "Command details: PressHome")
                    is Command.PressBack -> Log.d(TAG, "Command details: PressBack")
                    is Command.ShowRecentApps -> Log.d(TAG, "Command details: ShowRecentApps")
                    is Command.PullStatusBarDown -> Log.d(TAG, "Command details: PullStatusBarDown")
                    is Command.PullStatusBarDownTwice -> Log.d(TAG, "Command details: PullStatusBarDownTwice")
                    is Command.PushStatusBarUp -> Log.d(TAG, "Command details: PushStatusBarUp")
                    is Command.ScrollUp -> Log.d(TAG, "Command details: ScrollUp")
                    is Command.ScrollDown -> Log.d(TAG, "Command details: ScrollDown")
                    is Command.ScrollLeft -> Log.d(TAG, "Command details: ScrollLeft")
                    is Command.ScrollRight -> Log.d(TAG, "Command details: ScrollRight")
                    is Command.OpenApp -> Log.d(TAG, "Command details: OpenApp(\"${command.appName}\")")
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
        findPressHomeCommands(text, commands)
        
        // Look for back button commands
        findPressBackCommands(text, commands)
        
        // Look for recent apps commands
        findShowRecentAppsCommands(text, commands)
        
        // Look for status bar down commands
        findPullStatusBarDownCommands(text, commands)
        
        // Look for status bar down twice commands
        findPullStatusBarDownTwiceCommands(text, commands)
        
        // Look for status bar up commands
        findPushStatusBarUpCommands(text, commands)
        
        // Look for scroll up commands
        findScrollUpCommands(text, commands)
        
        // Look for scroll down commands
        findScrollDownCommands(text, commands)
        
        // Look for scroll left commands
        findScrollLeftCommands(text, commands)
        
        // Look for scroll right commands
        findScrollRightCommands(text, commands)
        
        // Look for open app commands
        findOpenAppCommands(text, commands)
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
    private fun findPressHomeCommands(text: String, commands: MutableList<Command>) {
        // Try each pattern
        for (pattern in PRESS_HOME_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                // Check if this command is already in the list (avoid duplicates)
                if (!commands.any { it is Command.PressHome }) {
                    Log.d(TAG, "Found press home command with pattern ${pattern.pattern}")
                    commands.add(Command.PressHome)
                    // Only add one home command even if multiple matches are found
                    break
                }
            }
        }
    }
    
    /**
     * Find back button commands in the text
     */
    private fun findPressBackCommands(text: String, commands: MutableList<Command>) {
        // Try each pattern
        for (pattern in PRESS_BACK_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                // Check if this command is already in the list (avoid duplicates)
                if (!commands.any { it is Command.PressBack }) {
                    Log.d(TAG, "Found press back command with pattern ${pattern.pattern}")
                    commands.add(Command.PressBack)
                    // Only add one back command even if multiple matches are found
                    break
                }
            }
        }
    }
    
    /**
     * Find recent apps commands in the text
     */
    private fun findShowRecentAppsCommands(text: String, commands: MutableList<Command>) {
        // Try each pattern
        for (pattern in SHOW_RECENT_APPS_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                // Check if this command is already in the list (avoid duplicates)
                if (!commands.any { it is Command.ShowRecentApps }) {
                    Log.d(TAG, "Found show recent apps command with pattern ${pattern.pattern}")
                    commands.add(Command.ShowRecentApps)
                    // Only add one recent apps command even if multiple matches are found
                    break
                }
            }
        }
    }
    
    /**
     * Find status bar down commands in the text
     */
    private fun findPullStatusBarDownCommands(text: String, commands: MutableList<Command>) {
        // Try each pattern
        for (pattern in PULL_STATUS_BAR_DOWN_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                // Check if this command is already in the list (avoid duplicates)
                if (!commands.any { it is Command.PullStatusBarDown }) {
                    Log.d(TAG, "Found pull status bar down command with pattern ${pattern.pattern}")
                    commands.add(Command.PullStatusBarDown)
                    // Only add one status bar down command even if multiple matches are found
                    break
                }
            }
        }
    }
    
    /**
     * Find status bar down twice commands in the text
     */
    private fun findPullStatusBarDownTwiceCommands(text: String, commands: MutableList<Command>) {
        // Try each pattern
        for (pattern in PULL_STATUS_BAR_DOWN_TWICE_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                // Check if this command is already in the list (avoid duplicates)
                if (!commands.any { it is Command.PullStatusBarDownTwice }) {
                    Log.d(TAG, "Found pull status bar down twice command with pattern ${pattern.pattern}")
                    commands.add(Command.PullStatusBarDownTwice)
                    // Only add one status bar down twice command even if multiple matches are found
                    break
                }
            }
        }
    }
    
    /**
     * Find status bar up commands in the text
     */
    private fun findPushStatusBarUpCommands(text: String, commands: MutableList<Command>) {
        // Try each pattern
        for (pattern in PUSH_STATUS_BAR_UP_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                // Check if this command is already in the list (avoid duplicates)
                if (!commands.any { it is Command.PushStatusBarUp }) {
                    Log.d(TAG, "Found push status bar up command with pattern ${pattern.pattern}")
                    commands.add(Command.PushStatusBarUp)
                    // Only add one status bar up command even if multiple matches are found
                    break
                }
            }
        }
    }
    
    /**
     * Find scroll up commands in the text
     */
    private fun findScrollUpCommands(text: String, commands: MutableList<Command>) {
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
    
    /**
     * Find scroll down commands in the text
     */
    private fun findScrollDownCommands(text: String, commands: MutableList<Command>) {
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
    
    /**
     * Find scroll left commands in the text
     */
    private fun findScrollLeftCommands(text: String, commands: MutableList<Command>) {
        // Try each pattern
        for (pattern in SCROLL_LEFT_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                // Check if this command is already in the list (avoid duplicates)
                if (!commands.any { it is Command.ScrollLeft }) {
                    Log.d(TAG, "Found scroll left command with pattern ${pattern.pattern}")
                    commands.add(Command.ScrollLeft)
                    // Only add one scroll left command even if multiple matches are found
                    break
                }
            }
        }
    }
    
    /**
     * Find scroll right commands in the text
     */
    private fun findScrollRightCommands(text: String, commands: MutableList<Command>) {
        // Try each pattern
        for (pattern in SCROLL_RIGHT_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                // Check if this command is already in the list (avoid duplicates)
                if (!commands.any { it is Command.ScrollRight }) {
                    Log.d(TAG, "Found scroll right command with pattern ${pattern.pattern}")
                    commands.add(Command.ScrollRight)
                    // Only add one scroll right command even if multiple matches are found
                    break
                }
            }
        }
    }
    
    /**
     * Find open app commands in the text
     */
    private fun findOpenAppCommands(text: String, commands: MutableList<Command>) {
        // Try each pattern
        for (pattern in OPEN_APP_PATTERNS) {
            val matches = pattern.findAll(text)
            for (match in matches) {
                try {
                    if (match.groupValues.size > 1) {
                        val appName = match.groupValues[1].trim()
                        if (appName.isNotEmpty()) {
                            // Check if this command is already in the list (avoid duplicates)
                            if (!commands.any { it is Command.OpenApp && it.appName == appName }) {
                                Log.d(TAG, "Found open app command with pattern ${pattern.pattern}: \"$appName\"")
                                commands.add(Command.OpenApp(appName))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing open app match: ${e.message}", e)
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
