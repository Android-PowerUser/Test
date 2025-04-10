package com.google.ai.sample.util

import android.util.Log
import java.util.regex.Pattern

/**
 * Utility class for parsing commands from AI responses
 */
object CommandParser {
    private const val TAG = "CommandParser"
    
    // Command patterns
    private val CLICK_BUTTON_PATTERN = Pattern.compile("clickOnButton\\(\\s*\"([^\"]+)\"\\s*\\)")
    private val TAP_COORDINATES_PATTERN = Pattern.compile("tapAtCoordinates\\(\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*\\)")
    
    /**
     * Parse commands from AI response text
     * @param text The AI response text to parse
     * @return List of parsed commands
     */
    fun parseCommands(text: String): List<Command> {
        val commands = mutableListOf<Command>()
        
        // Find clickOnButton commands
        val clickMatcher = CLICK_BUTTON_PATTERN.matcher(text)
        while (clickMatcher.find()) {
            val buttonText = clickMatcher.group(1)
            if (buttonText != null) {
                Log.d(TAG, "Found clickOnButton command: $buttonText")
                commands.add(Command.ClickButton(buttonText))
            }
        }
        
        // Find tapAtCoordinates commands
        val tapMatcher = TAP_COORDINATES_PATTERN.matcher(text)
        while (tapMatcher.find()) {
            try {
                val x = tapMatcher.group(1)?.toFloat()
                val y = tapMatcher.group(2)?.toFloat()
                if (x != null && y != null) {
                    Log.d(TAG, "Found tapAtCoordinates command: $x, $y")
                    commands.add(Command.TapCoordinates(x, y))
                }
            } catch (e: NumberFormatException) {
                Log.e(TAG, "Error parsing coordinates: ${e.message}")
            }
        }
        
        return commands
    }
}

/**
 * Sealed class representing different types of commands
 */
sealed class Command {
    /**
     * Command to click a button with specific text
     */
    data class ClickButton(val buttonText: String) : Command()
    
    /**
     * Command to tap at specific coordinates
     */
    data class TapCoordinates(val x: Float, val y: Float) : Command()
}
