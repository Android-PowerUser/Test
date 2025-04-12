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
        Regex("(?i)\\b(?:tap|click|press|tippe|klicke|tippe auf|klicke auf) (?:at|on|auf) (?:coordinates?|koordinaten|position|stelle|punkt)[:\\s]\\s*\\(?\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)?"),
        Regex("(?i)\\b(?:tap|click|press|tippe|klicke|tippe auf|klicke auf) (?:at|on|auf) \\(?\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)?"),
        
        // Function-like patterns
        Regex("(?i)\\btapAtCoordinates\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)"),
        Regex("(?i)\\bclickAtPosition\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)"),
        Regex("(?i)\\btapAt\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)")
    )
    
    // Screenshot patterns - already working well but expanded for consistency
    private val TAKE_SCREENSHOT_PATTERNS = listOf(
        Regex("(?i)\\b(?:take|capture|make|nimm|erstelle|mache) (?:a )?(?:screenshot|bildschirmfoto|bildschirmaufnahme)"),
        Regex("(?i)\\btakeScreenshot\\(\\)"),
        Regex("(?i)\\bcaptureScreen\\(\\)")
    )
    
    /**
     * Parse commands from the given text
     * 
     * @param text The text to parse for commands
     * @return A list of commands found in the text
     */
    fun parseCommands(text: String): List<Command> {
        val commands = mutableListOf<Command>()
        
        try {
            // Debug the input text
            Log.d(TAG, "Parsing text for commands: $text")
            
            // Look for click button commands
            findClickButtonCommands(text, commands)
            
            // Look for tap coordinates commands
            findTapCoordinatesCommands(text, commands)
            
            // Look for take screenshot commands
            findTakeScreenshotCommands(text, commands)
            
            Log.d(TAG, "Found ${commands.size} commands in text")
            
            // Debug each found command
            commands.forEach { command ->
                when (command) {
                    is Command.ClickButton -> Log.d(TAG, "Command details: ClickButton(\"${command.buttonText}\")")
                    is Command.TapCoordinates -> Log.d(TAG, "Command details: TapCoordinates(${command.x}, ${command.y})")
                    is Command.TakeScreenshot -> Log.d(TAG, "Command details: TakeScreenshot")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing commands: ${e.message}", e)
        }
        
        return commands
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
                            Log.d(TAG, "Found click button command with pattern ${pattern.pattern}: \"$buttonText\"")
                            commands.add(Command.ClickButton(buttonText))
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
                        Log.d(TAG, "Found tap coordinates command with pattern ${pattern.pattern}: ($x, $y)")
                        commands.add(Command.TapCoordinates(x, y))
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
                Log.d(TAG, "Found take screenshot command with pattern ${pattern.pattern}")
                commands.add(Command.TakeScreenshot)
                // Only add one screenshot command even if multiple matches are found
                break
            }
        }
    }
    
    /**
     * Debug method to test if a specific command would be recognized
     */
    fun testCommandRecognition(commandText: String): List<Command> {
        Log.d(TAG, "Testing command recognition for: \"$commandText\"")
        val commands = parseCommands(commandText)
        Log.d(TAG, "Recognition test result: ${commands.size} commands found")
        return commands
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
}
