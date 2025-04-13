package com.google.ai.sample.util

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
     * Command to press the Home button
     */
    object PressHome : Command()
    
    /**
     * Command to press the Back button
     */
    object PressBack : Command()
    
    /**
     * Command to show recent apps
     */
    object ShowRecentApps : Command()
    
    /**
     * Command to pull down the status bar (notifications)
     */
    object PullStatusBarDown : Command()
    
    /**
     * Command to pull down the status bar twice (quick settings)
     */
    object PullStatusBarDownTwice : Command()
    
    /**
     * Command to push the status bar up (close notifications/quick settings)
     */
    object PushStatusBarUp : Command()
    
    /**
     * Command to scroll up
     */
    object ScrollUp : Command()
    
    /**
     * Command to scroll down
     */
    object ScrollDown : Command()
    
    /**
     * Command to scroll left
     */
    object ScrollLeft : Command()
    
    /**
     * Command to scroll right
     */
    object ScrollRight : Command()
    
    /**
     * Command to open a specific app
     */
    data class OpenApp(val appName: String) : Command()
}
