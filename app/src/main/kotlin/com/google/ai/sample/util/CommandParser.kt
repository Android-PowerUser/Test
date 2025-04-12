package com.google.ai.sample.util

import android.util.Log

/**
 * Command parser for extracting commands from AI responses
 */
object CommandParser {
    private const val TAG = "CommandParser"
    
    // Regex patterns for different command formats
    
    // Click button patterns - reorganized by priority
    private val CLICK_BUTTON_PATTERNS = listOf(
        // Function-like patterns (highest priority)
        Regex("(?i)\\b(?:clickOnButton|tapOnButton|pressButton)\\([\"']([^\"']+)[\"']\\)"),
        
        // Standard patterns with quotes (high priority)
        Regex("(?i)\\b(?:click|tap|press|klick|tippe auf|drücke|klicke auf|drücke auf) (?:on )?(?:the )?(?:button|knopf|schaltfläche)?(?: labeled| mit text)? [\"']([^\"']+)[\"']"),
        Regex("(?i)\\b(?:click|tap|press|klick|tippe auf|drücke|klicke auf|drücke auf) (?:on )?(?:the )?[\"']([^\"']+)[\"'] (?:button|knopf|schaltfläche)?"),
        
        // Patterns with "labeled" keyword (medium priority)
        Regex("(?i)\\b(?:click|tap|press|klick|tippe auf|drücke|klicke auf|drücke auf) (?:on )?(?:the )?(?:button|knopf|schaltfläche) labeled ([\\w\\s\\-]+)(?:\\b|$)"),
        
        // Direct command patterns (lower priority)
        Regex("(?i)\\b(?:click|tap|press|klick|tippe auf|drücke|klicke auf|drücke auf) (?:on )?(?:the )?([\\w\\s\\-]+) (?:button|knopf|schaltfläche)\\b"),
        
        // Patterns without quotes (lowest priority)
        Regex("(?i)\\b(?:click|tap|press|klick|tippe auf|drücke|klicke auf|drücke auf) (?:on )?(?:the )?(?:button|knopf|schaltfläche) ([\\w\\s\\-]+)(?:\\b|$)")
    )
    
    // Tap coordinates patterns - updated to support decimal numbers
    private val TAP_COORDINATES_PATTERNS = listOf(
        // Function-like patterns
        Regex("(?i)\\b(?:tapAtCoordinates|clickAtPosition|tapAt)\\(\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*\\)"),
        
        // Standard patterns
        Regex("(?i)\\b(?:tap|click|press|tippe|klicke|tippe auf|klicke auf) (?:at|on|auf) (?:coordinates?|koordinaten|position|stelle|punkt)[:\\s]\\s*\\(?\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*\\)?"),
        Regex("(?i)\\b(?:tap|click|press|tippe|klicke|tippe auf|klicke auf) (?:at|on|auf) \\(?\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*\\)?")
    )
    
    // Take screenshot patterns - expanded German language support
    private val TAKE_SCREENSHOT_PATTERNS = listOf(
        // English patterns
        Regex("(?i)\\b(?:take|capture|make) (?:a )?(?:screenshot|screen shot|screen-shot)(?:\\s|$)"),
        
        // German patterns (expanded)
        Regex("(?i)\\b(?:nimm|erstelle|mache|nehme|erzeuge) (?:ein(?:e)? )?(?:bildschirmfoto|screenshot|bildschirmaufnahme|bildschirmabbild)(?:\\s|$)"),
        
        // Function-like patterns
        Regex("(?i)\\b(?:takeScreenshot|captureScreen)\\(\\)")
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
            
            // Process the text line by line to improve context separation
            val lines = text.split("\n")
            for (line in lines) {
                // Skip empty lines
                if (line.trim().isEmpty()) continue
                
                // Track if we found a command in this line
                var commandFoundInLine = false
                
                // Look for click button commands
                val clickButtonCommand = findClickButtonCommand(line)
                if (clickButtonCommand != null) {
                    commands.add(clickButtonCommand)
                    commandFoundInLine = true
                    Log.d(TAG, "Found click button command in line: $line")
                    continue  // Move to next line after finding a command
                }
                
                // Look for tap coordinates commands
                val tapCoordinatesCommand = findTapCoordinatesCommand(line)
                if (tapCoordinatesCommand != null) {
                    commands.add(tapCoordinatesCommand)
                    commandFoundInLine = true
                    Log.d(TAG, "Found tap coordinates command in line: $line")
                    continue  // Move to next line after finding a command
                }
                
                // Look for take screenshot commands
                val takeScreenshotCommand = findTakeScreenshotCommand(line)
                if (takeScreenshotCommand != null) {
                    commands.add(takeScreenshotCommand)
                    commandFoundInLine = true
                    Log.d(TAG, "Found take screenshot command in line: $line")
                    continue  // Move to next line after finding a command
                }
            }
            
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
     * Find a click button command in the text
     * Returns the first valid match or null if none found
     */
    private fun findClickButtonCommand(text: String): Command.ClickButton? {
        for (pattern in CLICK_BUTTON_PATTERNS) {
            val match = pattern.find(text)
            if (match != null && match.groupValues.size > 1) {
                val buttonText = match.groupValues[1].trim()
                if (buttonText.isNotEmpty()) {
                    Log.d(TAG, "Found click button command with pattern ${pattern.pattern}: \"$buttonText\"")
                    return Command.ClickButton(buttonText)
                }
            }
        }
        return null
    }
    
    /**
     * Find a tap coordinates command in the text
     * Returns the first valid match or null if none found
     */
    private fun findTapCoordinatesCommand(text: String): Command.TapCoordinates? {
        for (pattern in TAP_COORDINATES_PATTERNS) {
            val match = pattern.find(text)
            if (match != null && match.groupValues.size > 2) {
                try {
                    val x = match.groupValues[1].trim().toFloat()
                    val y = match.groupValues[2].trim().toFloat()
                    Log.d(TAG, "Found tap coordinates command with pattern ${pattern.pattern}: ($x, $y)")
                    return Command.TapCoordinates(x, y)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing tap coordinates match: ${e.message}", e)
                }
            }
        }
        return null
    }
    
    /**
     * Find a take screenshot command in the text
     * Returns a command if found or null if none found
     */
    private fun findTakeScreenshotCommand(text: String): Command.TakeScreenshot? {
        for (pattern in TAKE_SCREENSHOT_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                Log.d(TAG, "Found take screenshot command with pattern ${pattern.pattern}")
                return Command.TakeScreenshot
            }
        }
        return null
    }
    
    /**
     * Debug method to test if a specific command would be recognized
     * Returns detailed information about the matching process
     */
    fun testCommandRecognition(commandText: String): String {
        val result = StringBuilder()
        result.append("Testing command recognition for: \"$commandText\"\n")
        
        // Test click button patterns
        result.append("\nTesting Click Button Patterns:\n")
        for (i in CLICK_BUTTON_PATTERNS.indices) {
            val pattern = CLICK_BUTTON_PATTERNS[i]
            val match = pattern.find(commandText)
            if (match != null && match.groupValues.size > 1) {
                val buttonText = match.groupValues[1].trim()
                result.append("  Pattern ${i+1}: Matched button text: \"$buttonText\"\n")
            } else if (match != null) {
                result.append("  Pattern ${i+1}: Matched but no capture group\n")
            }
        }
        
        // Test tap coordinates patterns
        result.append("\nTesting Tap Coordinates Patterns:\n")
        for (i in TAP_COORDINATES_PATTERNS.indices) {
            val pattern = TAP_COORDINATES_PATTERNS[i]
            val match = pattern.find(commandText)
            if (match != null && match.groupValues.size > 2) {
                val x = match.groupValues[1].trim()
                val y = match.groupValues[2].trim()
                result.append("  Pattern ${i+1}: Matched coordinates: x=$x, y=$y\n")
            } else if (match != null) {
                result.append("  Pattern ${i+1}: Matched but insufficient capture groups\n")
            }
        }
        
        // Test take screenshot patterns
        result.append("\nTesting Take Screenshot Patterns:\n")
        for (i in TAKE_SCREENSHOT_PATTERNS.indices) {
            val pattern = TAKE_SCREENSHOT_PATTERNS[i]
            val match = pattern.find(commandText)
            if (match != null) {
                result.append("  Pattern ${i+1}: Matched\n")
            }
        }
        
        // Parse commands using the normal method
        val commands = parseCommands(commandText)
        result.append("\nFinal Recognition Result: ${commands.size} commands found\n")
        commands.forEach { command ->
            when (command) {
                is Command.ClickButton -> result.append("  Command: ClickButton(\"${command.buttonText}\")\n")
                is Command.TapCoordinates -> result.append("  Command: TapCoordinates(${command.x}, ${command.y})\n")
                is Command.TakeScreenshot -> result.append("  Command: TakeScreenshot\n")
            }
        }
        
        return result.toString()
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
