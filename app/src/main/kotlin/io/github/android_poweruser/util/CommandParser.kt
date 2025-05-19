package com.google.ai.sample.util

import android.util.Log

/**
 * Command parser for extracting commands from AI responses
 */
object CommandParser {
    private const val TAG = "CommandParser"

    // Regex patterns for different command formats
    
    // Enter key patterns - for simulating Enter key press
    private val ENTER_KEY_PATTERNS = listOf(
        // Function-like patterns
        Regex("(?i)\\benter\\(\\)"),
        Regex("(?i)\\bpressEnter\\(\\)"),
        Regex("(?i)\\benterKey\\(\\)"),
        
        // Natural language patterns
        Regex("(?i)\\b(?:press|hit|tap|drücke|tippe auf) (?:the )?enter(?: key| button)?\\b"),
        Regex("(?i)\\b(?:press|hit|tap|drücke|tippe auf) (?:the )?return(?: key| button)?\\b")
    )

    // Model selection patterns - for switching between high and low reasoning models
    private val MODEL_SELECTION_PATTERNS = listOf(
        // High reasoning model patterns
        Regex("(?i)\\bhighReasoningModel\\(\\)"),
        Regex("(?i)\\buseHighReasoningModel\\(\\)"),
        Regex("(?i)\\bswitchToHighReasoningModel\\(\\)"),
        Regex("(?i)\\b(?:use|switch to|enable|activate|verwende|wechsle zu|aktiviere) (?:the )?(?:high|advanced|better|improved|höhere|verbesserte|bessere) (?:reasoning|thinking|intelligence|denk|intelligenz) model\\b"),
        Regex("(?i)\\b(?:use|switch to|enable|activate|verwende|wechsle zu|aktiviere) (?:the )?gemini(?:\\-|\\s)?2\\.5(?:\\-|\\s)?pro\\b"),

        // Low reasoning model patterns
        Regex("(?i)\\blowReasoningModel\\(\\)"),
        Regex("(?i)\\buseLowReasoningModel\\(\\)"),
        Regex("(?i)\\bswitchToLowReasoningModel\\(\\)"),
        Regex("(?i)\\b(?:use|switch to|enable|activate|verwende|wechsle zu|aktiviere) (?:the )?(?:low|basic|simple|standard|niedrige|einfache|standard) (?:reasoning|thinking|intelligence|denk|intelligenz) model\\b"),
        Regex("(?i)\\b(?:use|switch to|enable|activate|verwende|wechsle zu|aktiviere) (?:the )?gemini(?:\\-|\\s)?2\\.0(?:\\-|\\s)?flash\\b")
    )

    // Write text patterns - for writing text into focused text fields
    private val WRITE_TEXT_PATTERNS = listOf(
        // Function-like patterns
        Regex("(?i)\\bwriteText\\([\"']([^\"']+)[\"']\\)"),
        Regex("(?i)\\benterText\\([\"']([^\"']+)[\"']\\)"),
        Regex("(?i)\\btypeText\\([\"']([^\"']+)[\"']\\)"),

        // Natural language patterns with quotes
        Regex("(?i)\\b(?:write|enter|type|input|schreibe|gib ein|tippe) (?:the )?(?:text|text string|string|text value|value|text content|content|text input|input)? [\"']([^\"']+)[\"']"),
        Regex("(?i)\\b(?:write|enter|type|input|schreibe|gib ein|tippe) [\"']([^\"']+)[\"'] (?:into|in|to|auf|in das|ins) (?:the )?(?:text field|input field|field|text box|input box|box|text input|input|textfeld|eingabefeld|feld|textbox|eingabebox|box|texteingabe|eingabe)"),

        // Natural language patterns without quotes
        Regex("(?i)\\b(?:write|enter|type|input|schreibe|gib ein|tippe) (?:the )?(?:text|text string|string|text value|value|text content|content|text input|input)? \"([^\"]+)\""),
        Regex("(?i)\\b(?:write|enter|type|input|schreibe|gib ein|tippe) \"([^\"]+)\" (?:into|in|to|auf|in das|ins) (?:the )?(?:text field|input field|field|text box|input box|box|text input|input|textfeld|eingabefeld|feld|textbox|eingabebox|box|texteingabe|eingabe)")
    )

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

    // Scroll left patterns - for scrolling left
    private val SCROLL_LEFT_PATTERNS = listOf(
        // Function-like patterns
        Regex("(?i)\\bscrollLeft\\(\\)"),
        Regex("(?i)\\bscrollLeftPage\\(\\)"),
        Regex("(?i)\\bpageLeft\\(\\)"),

        // Natural language patterns
        Regex("(?i)\\b(?:scroll|swipe|move|nach links) (?:left|nach links)\\b"),
        Regex("(?i)\\b(?:nach links scrollen|links scrollen|nach links wischen|links wischen)\\b")
    )

    // Scroll right patterns - for scrolling right
    private val SCROLL_RIGHT_PATTERNS = listOf(
        // Function-like patterns
        Regex("(?i)\\bscrollRight\\(\\)"),
        Regex("(?i)\\bscrollRightPage\\(\\)"),
        Regex("(?i)\\bpageRight\\(\\)"),

        // Natural language patterns
        Regex("(?i)\\b(?:scroll|swipe|move|nach rechts) (?:right|nach rechts)\\b"),
        Regex("(?i)\\b(?:nach rechts scrollen|rechts scrollen|nach rechts wischen|rechts wischen)\\b")
    )

    // Open app patterns - for opening apps
    private val OPEN_APP_PATTERNS = listOf(
        // Function-like patterns
        Regex("(?i)\\bopenApp\\([\"']([^\"']+)[\"']\\)"),
        Regex("(?i)\\blaunchApp\\([\"']([^\"']+)[\"']\\)"),
        Regex("(?i)\\bstartApp\\([\"']([^\"']+)[\"']\\)"),

        // Natural language patterns
        Regex("(?i)\\b(?:open|launch|start|öffne|starte) (?:the )?(?:app|application|anwendung) [\"']([^\"']+)[\"']"),
        Regex("(?i)\\b(?:öffne|starte) [\"']([^\"']+)[\"']")
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

            Log.d(TAG, "Found ${commands.size} commands in text") // This log remains

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
                    is Command.ScrollLeft -> Log.d(TAG, "Command details: ScrollLeft")
                    is Command.ScrollRight -> Log.d(TAG, "Command details: ScrollRight")
                    is Command.ScrollDownFromCoordinates -> Log.d(TAG, "Command details: ScrollDownFromCoordinates(${command.x}, ${command.y}, ${command.distance}, ${command.duration})")
                    is Command.UseHighReasoningModel -> Log.d(TAG, "Command details: UseHighReasoningModel")
                    is Command.UseLowReasoningModel -> Log.d(TAG, "Command details: UseLowReasoningModel")
                    is Command.ScrollUpFromCoordinates -> Log.d(TAG, "Command details: ScrollUpFromCoordinates(${command.x}, ${command.y}, ${command.distance}, ${command.duration})")
                    is Command.ScrollLeftFromCoordinates -> Log.d(TAG, "Command details: ScrollLeftFromCoordinates(${command.x}, ${command.y}, ${command.distance}, ${command.duration})")
                    is Command.ScrollRightFromCoordinates -> Log.d(TAG, "Command details: ScrollRightFromCoordinates(${command.x}, ${command.y}, ${command.distance}, ${command.duration})")
                    is Command.OpenApp -> Log.d(TAG, "Command details: OpenApp(\"${command.packageName}\")")
                    is Command.WriteText -> Log.d(TAG, "Command details: WriteText(\"${command.text}\")")
                    is Command.PressEnterKey -> Log.d(TAG, "Command details: PressEnterKey")
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
        // Look for model selection commands
        findModelSelectionCommands(text, commands)

        // Look for write text commands
        findWriteTextCommands(text, commands)

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

        // Look for scroll left commands
        findScrollLeftCommands(text, commands)

        // Look for scroll right commands
        findScrollRightCommands(text, commands)

        // Look for open app commands
        findOpenAppCommands(text, commands)
        
        // Look for enter key commands
        findEnterKeyCommands(text, commands)
    }
    
    /**
     * Find enter key commands in the text
     */
    private fun findEnterKeyCommands(text: String, commands: MutableList<Command>) {
        // Try each pattern
        for (pattern in ENTER_KEY_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                // Check if this command is already in the list (avoid duplicates)
                if (!commands.any { it is Command.PressEnterKey }) {
                    Log.d(TAG, "Found enter key command with pattern ${pattern.pattern}")
                    commands.add(Command.PressEnterKey)
                    // Only add one enter key command even if multiple matches are found
                    break
                }
            }
        }
    }

    /**
     * Find model selection commands in the text
     */
    private fun findModelSelectionCommands(text: String, commands: MutableList<Command>) {
        // --- HINZUGEFÜGTE LOGS START ---
        Log.d(TAG, "--- Checking High Reasoning Patterns ---")
        // First check for high reasoning model commands
        for (i in 0 until 5) { // First 5 patterns are for high reasoning model
            val pattern = MODEL_SELECTION_PATTERNS[i]
            // LOG 1: Welches Muster wird geprüft?
            Log.d(TAG, "High Check: Pattern='${pattern.pattern}'")

            // LOG 2: Was ist der Text und seine Codes DIREKT vor dem Match?
            Log.d(TAG, "High Check: Attempting match against text: [$text]")
            Log.d(TAG, "High Check: Text character codes: ${text.map { it.code }}")

            val matchFound = pattern.containsMatchIn(text) // Der eigentliche Match-Versuch
            // LOG 3: Was ist das Ergebnis des Matchings?
            Log.d(TAG, "High Check: Match found = $matchFound")

            if (matchFound) {
                // LOG 4: Wird die Duplikatprüfung ausgeführt?
                Log.d(TAG, "High Check: Pattern matched. Checking for duplicates...")
                if (!commands.any { it is Command.UseHighReasoningModel }) {
                    Log.d(TAG, "Found high reasoning model command with pattern ${pattern.pattern}")
                    commands.add(Command.UseHighReasoningModel)
                    break
                } else {
                    // LOG 5: Duplikat gefunden
                    Log.d(TAG, "High Check: Duplicate command already exists.")
                }
            }
        }
        Log.d(TAG, "--- Finished High Reasoning Patterns ---")

        Log.d(TAG, "--- Checking Low Reasoning Patterns ---")
        // Then check for low reasoning model commands
        for (i in 5 until MODEL_SELECTION_PATTERNS.size) { // Remaining patterns are for low reasoning model
            val pattern = MODEL_SELECTION_PATTERNS[i]
            // LOG 1 (analog): Welches Muster wird geprüft?
            Log.d(TAG, "Low Check: Pattern='${pattern.pattern}'")

            // LOG 2 (analog): Was ist der Text und seine Codes DIREKT vor dem Match?
            Log.d(TAG, "Low Check: Attempting match against text: [$text]")
            Log.d(TAG, "Low Check: Text character codes: ${text.map { it.code }}")

            val matchFound = pattern.containsMatchIn(text) // Der eigentliche Match-Versuch
            // LOG 3 (analog): Was ist das Ergebnis des Matchings?
            Log.d(TAG, "Low Check: Match found = $matchFound")

            if (matchFound) {
                // LOG 4 (analog): Wird die Duplikatprüfung ausgeführt?
                Log.d(TAG, "Low Check: Pattern matched. Checking for duplicates...")
                if (!commands.any { it is Command.UseLowReasoningModel }) {
                    Log.d(TAG, "Found low reasoning model command with pattern ${pattern.pattern}")
                    commands.add(Command.UseLowReasoningModel)
                    break
                } else {
                    // LOG 5 (analog): Duplikat gefunden
                    Log.d(TAG, "Low Check: Duplicate command already exists.")
                }
            }
        }
        Log.d(TAG, "--- Finished Low Reasoning Patterns ---")
        // --- HINZUGEFÜGTE LOGS ENDE ---
    }


    /**
     * Find write text commands in the text
     */
    private fun findWriteTextCommands(text: String, commands: MutableList<Command>) {
        // Try each pattern
        for (pattern in WRITE_TEXT_PATTERNS) {
            val matches = pattern.findAll(text)
            for (match in matches) {
                try {
                    if (match.groupValues.size > 1) {
                        val textToWrite = match.groupValues[1].trim()
                        if (textToWrite.isNotEmpty()) {
                            // Check if this command is already in the list (avoid duplicates)
                            if (!commands.any { it is Command.WriteText && it.text == textToWrite }) {
                                Log.d(TAG, "Found write text command with pattern ${pattern.pattern}: \"$textToWrite\"")
                                commands.add(Command.WriteText(textToWrite))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing write text match: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Normalize text by trimming whitespace and normalizing line breaks
     */
    private fun normalizeText(text: String): String {
        // Replace multiple spaces with a single space
        var normalized = text.replace(Regex("\\s+"), " ")

        // Ensure consistent line breaks
        normalized = normalized.replace(Regex("\\r\\n|\\r"), "\n")

        return normalized.trim() // Added trim() here as well for good measure
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
     * Find scroll left commands in the text
     */
    private fun findScrollLeftCommands(text: String, commands: MutableList<Command>) {
        // First check for coordinate-based scroll left commands
        val coordPattern = Regex("(?i)\\bscrollLeft\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)")
        val matches = coordPattern.findAll(text)

        for (match in matches) {
            if (match.groupValues.size >= 5) {
                try {
                    val x = match.groupValues[1].toFloat()
                    val y = match.groupValues[2].toFloat()
                    val distance = match.groupValues[3].toFloat()
                    val duration = match.groupValues[4].toLong()

                    Log.d(TAG, "Found coordinate-based scroll left command: scrollLeft($x, $y, $distance, $duration)")
                    commands.add(Command.ScrollLeftFromCoordinates(x, y, distance, duration))
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing coordinate-based scroll left command: ${e.message}")
                }
            }
        }

        // If no coordinate-based commands were found, look for simple scroll left commands
        if (!commands.any { it is Command.ScrollLeftFromCoordinates }) {
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
    }

    /**
     * Find scroll right commands in the text
     */
    private fun findScrollRightCommands(text: String, commands: MutableList<Command>) {
        // First check for coordinate-based scroll right commands
        val coordPattern = Regex("(?i)\\bscrollRight\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)")
        val matches = coordPattern.findAll(text)

        for (match in matches) {
            if (match.groupValues.size >= 5) {
                try {
                    val x = match.groupValues[1].toFloat()
                    val y = match.groupValues[2].toFloat()
                    val distance = match.groupValues[3].toFloat()
                    val duration = match.groupValues[4].toLong()

                    Log.d(TAG, "Found coordinate-based scroll right command: scrollRight($x, $y, $distance, $duration)")
                    commands.add(Command.ScrollRightFromCoordinates(x, y, distance, duration))
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing coordinate-based scroll right command: ${e.message}")
                }
            }
        }

        // If no coordinate-based commands were found, look for simple scroll right commands
        if (!commands.any { it is Command.ScrollRightFromCoordinates }) {
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
                        val packageName = match.groupValues[1].trim()
                        if (packageName.isNotEmpty()) {
                            // Check if this command is already in the list (avoid duplicates)
                            if (!commands.any { it is Command.OpenApp && it.packageName == packageName }) {
                                Log.d(TAG, "Found open app command with pattern ${pattern.pattern}: \"$packageName\"")
                                commands.add(Command.OpenApp(packageName))
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
     * Command to scroll left
     */
    object ScrollLeft : Command()
    
    /**
     * Command to press the Enter key
     */
    object PressEnterKey : Command()

    /**
     * Command to scroll right
     */
    object ScrollRight : Command()

    /**
     * Command to scroll down from specific coordinates with custom distance and duration
     */
    data class ScrollDownFromCoordinates(val x: Float, val y: Float, val distance: Float, val duration: Long) : Command()

    /**
     * Command to scroll up from specific coordinates with custom distance and duration
     */
    data class ScrollUpFromCoordinates(val x: Float, val y: Float, val distance: Float, val duration: Long) : Command()

    /**
     * Command to scroll left from specific coordinates with custom distance and duration
     */
    data class ScrollLeftFromCoordinates(val x: Float, val y: Float, val distance: Float, val duration: Long) : Command()

    /**
     * Command to scroll right from specific coordinates with custom distance and duration
     */
    data class ScrollRightFromCoordinates(val x: Float, val y: Float, val distance: Float, val duration: Long) : Command()

    /**
     * Command to open an app by package name
     */
    data class OpenApp(val packageName: String) : Command()

    /**
     * Command to write text into the currently focused text field
     */
    data class WriteText(val text: String) : Command()

    /**
     * Command to switch to high reasoning model (gemini-2.5-pro-preview-03-25)
     */
    object UseHighReasoningModel : Command()

    /**
     * Command to switch to low reasoning model (gemini-2.0-flash-lite)
     */
    object UseLowReasoningModel : Command()
}
