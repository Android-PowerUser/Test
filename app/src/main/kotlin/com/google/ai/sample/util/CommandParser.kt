package com.google.ai.sample.util

import android.util.Log

/**
 * Command parser for extracting commands from AI responses
 */
object CommandParser {
    private const val TAG = "CommandParser"
    
    // Regex patterns for different command formats
    private val CLICK_BUTTON_PATTERN = Regex("(?i)\\b(?:click|tap|press|klick|tippe auf|drücke) (?:on )?(?:the )?(?:button|knopf|schaltfläche)(?: with text| labeled| mit text| mit der beschriftung)? [\"']([^\"']+)[\"']")
    private val CLICK_BUTTON_PATTERN_ALT = Regex("(?i)\\b(?:click|tap|press|klick|tippe auf|drücke) (?:on )?(?:the )?[\"']([^\"']+)[\"'] (?:button|knopf|schaltfläche)")
    private val TAP_COORDINATES_PATTERN = Regex("(?i)\\b(?:tap|click|press|tippe|klicke) (?:at|on|auf) coordinates?[:\\s]\\s*\\(?\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)?")
    private val TAP_COORDINATES_PATTERN_ALT = Regex("(?i)\\b(?:tap|click|press|tippe|klicke) (?:at|on|auf) position[:\\s]\\s*\\(?\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)?")
    private val TAKE_SCREENSHOT_PATTERN = Regex("(?i)\\b(?:take|capture|make|nimm|erstelle) (?:a )?screenshot")
    
    /**
     * Parse commands from the given text
     * 
     * @param text The text to parse for commands
     * @return A list of commands found in the text
     */
    fun parseCommands(text: String): List<Command> {
        val commands = mutableListOf<Command>()
        
        try {
            // Look for click button commands
            findClickButtonCommands(text, commands)
            
            // Look for tap coordinates commands
            findTapCoordinatesCommands(text, commands)
            
            // Look for take screenshot commands
            findTakeScreenshotCommands(text, commands)
            
            Log.d(TAG, "Found ${commands.size} commands in text")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing commands: ${e.message}")
        }
        
        return commands
    }
    
    /**
     * Find click button commands in the text
     */
    private fun findClickButtonCommands(text: String, commands: MutableList<Command>) {
        // Find matches for the first pattern
        val matches = CLICK_BUTTON_PATTERN.findAll(text)
        for (match in matches) {
            val buttonText = match.groupValues[1].trim()
            if (buttonText.isNotEmpty()) {
                Log.d(TAG, "Found click button command: $buttonText")
                commands.add(Command.ClickButton(buttonText))
            }
        }
        
        // Find matches for the alternative pattern
        val altMatches = CLICK_BUTTON_PATTERN_ALT.findAll(text)
        for (match in altMatches) {
            val buttonText = match.groupValues[1].trim()
            if (buttonText.isNotEmpty()) {
                Log.d(TAG, "Found click button command (alt pattern): $buttonText")
                commands.add(Command.ClickButton(buttonText))
            }
        }
    }
    
    /**
     * Find tap coordinates commands in the text
     */
    private fun findTapCoordinatesCommands(text: String, commands: MutableList<Command>) {
        // Find matches for the first pattern
        val matches = TAP_COORDINATES_PATTERN.findAll(text)
        for (match in matches) {
            try {
                val x = match.groupValues[1].trim().toFloat()
                val y = match.groupValues[2].trim().toFloat()
                Log.d(TAG, "Found tap coordinates command: ($x, $y)")
                commands.add(Command.TapCoordinates(x, y))
            } catch (e: NumberFormatException) {
                Log.e(TAG, "Error parsing coordinates: ${e.message}")
            }
        }
        
        // Find matches for the alternative pattern
        val altMatches = TAP_COORDINATES_PATTERN_ALT.findAll(text)
        for (match in altMatches) {
            try {
                val x = match.groupValues[1].trim().toFloat()
                val y = match.groupValues[2].trim().toFloat()
                Log.d(TAG, "Found tap coordinates command (alt pattern): ($x, $y)")
                commands.add(Command.TapCoordinates(x, y))
            } catch (e: NumberFormatException) {
                Log.e(TAG, "Error parsing coordinates: ${e.message}")
            }
        }
    }
    
    /**
     * Find take screenshot commands in the text
     */
    private fun findTakeScreenshotCommands(text: String, commands: MutableList<Command>) {
        val matches = TAKE_SCREENSHOT_PATTERN.findAll(text)
        for (match in matches) {
            Log.d(TAG, "Found take screenshot command")
            commands.add(Command.TakeScreenshot)
            // Only add one screenshot command even if multiple matches are found
            break
        }
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
