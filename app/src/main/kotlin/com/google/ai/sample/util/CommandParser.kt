package com.google.ai.sample.util

import android.util.Log

/**
 * Command parser for extracting commands from AI responses
 */
object CommandParser {
    private const val TAG = "CommandParser"

    // Enum to represent different command types
    private enum class CommandTypeEnum {
        CLICK_BUTTON, TAP_COORDINATES, TAKE_SCREENSHOT, PRESS_HOME, PRESS_BACK,
        SHOW_RECENT_APPS, SCROLL_DOWN, SCROLL_UP, SCROLL_LEFT, SCROLL_RIGHT,
        SCROLL_DOWN_FROM_COORDINATES, SCROLL_UP_FROM_COORDINATES,
        SCROLL_LEFT_FROM_COORDINATES, SCROLL_RIGHT_FROM_COORDINATES,
        OPEN_APP, WRITE_TEXT, USE_HIGH_REASONING_MODEL, USE_LOW_REASONING_MODEL,
        PRESS_ENTER_KEY
    }

    // Data class to hold pattern information
    private data class PatternInfo(
        val id: String, // For debugging
        val regex: Regex,
        val commandBuilder: (MatchResult) -> Command,
        val commandType: CommandTypeEnum // Used for single-instance command check
    )

    // Master list of all patterns
    private val ALL_PATTERNS: List<PatternInfo> = listOf(
        // Enter key patterns
        PatternInfo("enterKey1", Regex("(?i)\\benter\\(\\)"), { Command.PressEnterKey }, CommandTypeEnum.PRESS_ENTER_KEY),

        // Model selection patterns
        PatternInfo("highReasoning1", Regex("(?i)\\bhighReasoningModel\\(\\)"), { Command.UseHighReasoningModel }, CommandTypeEnum.USE_HIGH_REASONING_MODEL),
        PatternInfo("highReasoning2", Regex("(?i)\\buseHighReasoningModel\\(\\)"), { Command.UseHighReasoningModel }, CommandTypeEnum.USE_HIGH_REASONING_MODEL),
        PatternInfo("highReasoning3", Regex("(?i)\\bswitchToHighReasoningModel\\(\\)"), { Command.UseHighReasoningModel }, CommandTypeEnum.USE_HIGH_REASONING_MODEL),
        PatternInfo("highReasoning4", Regex("(?i)\\b(?:use|switch to|enable|activate|verwende|wechsle zu|aktiviere) (?:the )?(?:high|advanced|better|improved|höhere|verbesserte|bessere) (?:reasoning|thinking|intelligence|denk|intelligenz) model\\b"), { Command.UseHighReasoningModel }, CommandTypeEnum.USE_HIGH_REASONING_MODEL),
        PatternInfo("highReasoning5", Regex("(?i)\\b(?:use|switch to|enable|activate|verwende|wechsle zu|aktiviere) (?:the )?gemini(?:\\-|\\s)?2\\.5(?:\\-|\\s)?pro\\b"), { Command.UseHighReasoningModel }, CommandTypeEnum.USE_HIGH_REASONING_MODEL),
        PatternInfo("lowReasoning1", Regex("(?i)\\blowReasoningModel\\(\\)"), { Command.UseLowReasoningModel }, CommandTypeEnum.USE_LOW_REASONING_MODEL),
        PatternInfo("lowReasoning2", Regex("(?i)\\buseLowReasoningModel\\(\\)"), { Command.UseLowReasoningModel }, CommandTypeEnum.USE_LOW_REASONING_MODEL),
        PatternInfo("lowReasoning3", Regex("(?i)\\bswitchToLowReasoningModel\\(\\)"), { Command.UseLowReasoningModel }, CommandTypeEnum.USE_LOW_REASONING_MODEL),
        PatternInfo("lowReasoning4", Regex("(?i)\\b(?:use|switch to|enable|activate|verwende|wechsle zu|aktiviere) (?:the )?(?:low|basic|simple|standard|niedrige|einfache|standard) (?:reasoning|thinking|intelligence|denk|intelligenz) model\\b"), { Command.UseLowReasoningModel }, CommandTypeEnum.USE_LOW_REASONING_MODEL),
        PatternInfo("lowReasoning5", Regex("(?i)\\b(?:use|switch to|enable|activate|verwende|wechsle zu|aktiviere) (?:the )?gemini(?:\\-|\\s)?2\\.0(?:\\-|\\s)?flash\\b"), { Command.UseLowReasoningModel }, CommandTypeEnum.USE_LOW_REASONING_MODEL),

        // Write text patterns
        PatternInfo("writeText1", Regex("(?i)\\bwriteText\\([\"']([^\"']+)[\"']\\)"), { match -> Command.WriteText(match.groupValues[1]) }, CommandTypeEnum.WRITE_TEXT),
        PatternInfo("writeText2", Regex("(?i)\\benterText\\([\"']([^\"']+)[\"']\\)"), { match -> Command.WriteText(match.groupValues[1]) }, CommandTypeEnum.WRITE_TEXT),
        PatternInfo("writeText3", Regex("(?i)\\btypeText\\([\"']([^\"']+)[\"']\\)"), { match -> Command.WriteText(match.groupValues[1]) }, CommandTypeEnum.WRITE_TEXT),
        PatternInfo("writeText4", Regex("(?i)\\b(?:write|enter|type|input|schreibe|gib ein|tippe) (?:the )?(?:text|text string|string|text value|value|text content|content|text input|input)? [\"']([^\"']+)[\"']"), { match -> Command.WriteText(match.groupValues[1]) }, CommandTypeEnum.WRITE_TEXT),
        PatternInfo("writeText5", Regex("(?i)\\b(?:write|enter|type|input|schreibe|gib ein|tippe) [\"']([^\"']+)[\"'] (?:into|in|to|auf|in das|ins) (?:the )?(?:text field|input field|field|text box|input box|box|text input|input|textfeld|eingabefeld|feld|textbox|eingabebox|box|texteingabe|eingabe)"), { match -> Command.WriteText(match.groupValues[1]) }, CommandTypeEnum.WRITE_TEXT),
        PatternInfo("writeText6", Regex("(?i)\\b(?:write|enter|type|input|schreibe|gib ein|tippe) (?:the )?(?:text|text string|string|text value|value|text content|content|text input|input)? \"([^\"]+)\""), { match -> Command.WriteText(match.groupValues[1]) }, CommandTypeEnum.WRITE_TEXT),
        PatternInfo("writeText7", Regex("(?i)\\b(?:write|enter|type|input|schreibe|gib ein|tippe) \"([^\"]+)\" (?:into|in|to|auf|in das|ins) (?:the )?(?:text field|input field|field|text box|input box|box|text input|input|textfeld|eingabefeld|feld|textbox|eingabebox|box|texteingabe|eingabe)"), { match -> Command.WriteText(match.groupValues[1]) }, CommandTypeEnum.WRITE_TEXT),

        // Click button patterns
        PatternInfo("clickBtn1", Regex("(?i)\\b(?:click|tap|press|klick|tippe auf|drücke|klicke auf|drücke auf) (?:on )?(?:the )?(?:button|knopf|schaltfläche|button labeled|knopf mit text|schaltfläche mit text)? [\"']([^\"']+)[\"']"), { match -> Command.ClickButton(match.groupValues[1]) }, CommandTypeEnum.CLICK_BUTTON),
        PatternInfo("clickBtn2", Regex("(?i)\\b(?:click|tap|press|klick|tippe auf|drücke|klicke auf|drücke auf) (?:on )?(?:the )?[\"']([^\"']+)[\"'] (?:button|knopf|schaltfläche)?"), { match -> Command.ClickButton(match.groupValues[1]) }, CommandTypeEnum.CLICK_BUTTON),
        PatternInfo("clickBtn3", Regex("(?i)\\b(?:click|tap|press|klick|tippe auf|drücke|klicke auf|drücke auf) (?:on )?(?:the )?(?:button|knopf|schaltfläche) ([\\w\\s\\-]+)\\b"), { match -> Command.ClickButton(match.groupValues[1]) }, CommandTypeEnum.CLICK_BUTTON),
        PatternInfo("clickBtn4", Regex("(?i)\\b(?:click|tap|press|klick|tippe auf|drücke|klicke auf|drücke auf) (?:on )?(?:the )?(?:button|knopf|schaltfläche) labeled ([\\w\\s\\-]+)\\b"), { match -> Command.ClickButton(match.groupValues[1]) }, CommandTypeEnum.CLICK_BUTTON),
        PatternInfo("clickBtn5", Regex("(?i)\\b(?:click|tap|press|klick|tippe auf|drücke|klicke auf|drücke auf) ([\\w\\s\\-]+) (?:button|knopf|schaltfläche)\\b"), { match -> Command.ClickButton(match.groupValues[1]) }, CommandTypeEnum.CLICK_BUTTON),
        PatternInfo("clickBtn6", Regex("(?i)\\bclickOnButton\\([\"']([^\"']+)[\"']\\)"), { match -> Command.ClickButton(match.groupValues[1]) }, CommandTypeEnum.CLICK_BUTTON),
        PatternInfo("clickBtn7", Regex("(?i)\\btapOnButton\\([\"']([^\"']+)[\"']\\)"), { match -> Command.ClickButton(match.groupValues[1]) }, CommandTypeEnum.CLICK_BUTTON),
        PatternInfo("clickBtn8", Regex("(?i)\\bpressButton\\([\"']([^\"']+)[\"']\\)"), { match -> Command.ClickButton(match.groupValues[1]) }, CommandTypeEnum.CLICK_BUTTON),

        // Tap coordinates patterns
        PatternInfo("tapCoords1", Regex("(?i)\\b(?:tap|click|press|tippe|klicke|tippe auf|klicke auf) (?:at|on|auf) (?:coordinates?|koordinaten|position|stelle|punkt)[:\\s]\\s*\\(?\\s*([\\d\\.%]+)\\s*,\\s*([\\d\\.%]+)\\s*\\)?"), { match -> Command.TapCoordinates(match.groupValues[1], match.groupValues[2]) }, CommandTypeEnum.TAP_COORDINATES),
        PatternInfo("tapCoords2", Regex("(?i)\\b(?:tap|click|press|tippe|klicke|tippe auf|klicke auf) (?:at|on|auf) \\(?\\s*([\\d\\.%]+)\\s*,\\s*([\\d\\.%]+)\\s*\\)?"), { match -> Command.TapCoordinates(match.groupValues[1], match.groupValues[2]) }, CommandTypeEnum.TAP_COORDINATES),
        PatternInfo("tapCoords3", Regex("(?i)\\btapAtCoordinates\\(\\s*([\\d\\.%]+)\\s*,\\s*([\\d\\.%]+)\\s*\\)"), { match -> Command.TapCoordinates(match.groupValues[1], match.groupValues[2]) }, CommandTypeEnum.TAP_COORDINATES),
        PatternInfo("tapCoords4", Regex("(?i)\\bclickAtPosition\\(\\s*([\\d\\.%]+)\\s*,\\s*([\\d\\.%]+)\\s*\\)"), { match -> Command.TapCoordinates(match.groupValues[1], match.groupValues[2]) }, CommandTypeEnum.TAP_COORDINATES),
        PatternInfo("tapCoords5", Regex("(?i)\\btapAt\\(\\s*([\\d\\.%]+)\\s*,\\s*([\\d\\.%]+)\\s*\\)"), { match -> Command.TapCoordinates(match.groupValues[1], match.groupValues[2]) }, CommandTypeEnum.TAP_COORDINATES),

        // Screenshot patterns
        PatternInfo("screenshot1", Regex("(?i)\\b(?:take|capture|make|nimm|erstelle|mache|nehme|erzeuge) (?:a |ein(?:e)? )?(?:screenshot|bildschirmfoto|bildschirmaufnahme|bildschirmabbild)"), { Command.TakeScreenshot }, CommandTypeEnum.TAKE_SCREENSHOT),
        PatternInfo("screenshot2", Regex("(?i)\\btakeScreenshot\\(\\)"), { Command.TakeScreenshot }, CommandTypeEnum.TAKE_SCREENSHOT),
        PatternInfo("screenshot3", Regex("(?i)\\bcaptureScreen\\(\\)"), { Command.TakeScreenshot }, CommandTypeEnum.TAKE_SCREENSHOT),

        // Home button patterns
        PatternInfo("home1", Regex("(?i)\\bhome\\(\\)"), { Command.PressHomeButton }, CommandTypeEnum.PRESS_HOME),
        PatternInfo("home2", Regex("(?i)\\bpressHome\\(\\)"), { Command.PressHomeButton }, CommandTypeEnum.PRESS_HOME),
        PatternInfo("home3", Regex("(?i)\\bgoHome\\(\\)"), { Command.PressHomeButton }, CommandTypeEnum.PRESS_HOME),
        PatternInfo("home4", Regex("(?i)\\b(?:press|click|tap|go to|navigate to|return to|drücke|klicke|tippe auf|gehe zu|navigiere zu|kehre zurück zu) (?:the )?home(?: button| screen)?\\b"), { Command.PressHomeButton }, CommandTypeEnum.PRESS_HOME),
        PatternInfo("home5", Regex("(?i)\\b(?:zurück zum|zurück zur) (?:home|startseite|hauptbildschirm)\\b"), { Command.PressHomeButton }, CommandTypeEnum.PRESS_HOME),

        // Back button patterns
        PatternInfo("back1", Regex("(?i)\\bback\\(\\)"), { Command.PressBackButton }, CommandTypeEnum.PRESS_BACK),
        PatternInfo("back2", Regex("(?i)\\bpressBack\\(\\)"), { Command.PressBackButton }, CommandTypeEnum.PRESS_BACK),
        PatternInfo("back3", Regex("(?i)\\bgoBack\\(\\)"), { Command.PressBackButton }, CommandTypeEnum.PRESS_BACK),
        PatternInfo("back4", Regex("(?i)\\b(?:press|click|tap|go|navigate|return|drücke|klicke|tippe auf|gehe|navigiere|kehre) (?:the )?back(?: button)?\\b"), { Command.PressBackButton }, CommandTypeEnum.PRESS_BACK),
        PatternInfo("back5", Regex("(?i)\\b(?:zurück|zurückgehen)\\b"), { Command.PressBackButton }, CommandTypeEnum.PRESS_BACK),

        // Recent apps patterns
        PatternInfo("recentApps1", Regex("(?i)\\brecentApps\\(\\)"), { Command.ShowRecentApps }, CommandTypeEnum.SHOW_RECENT_APPS),
        PatternInfo("recentApps2", Regex("(?i)\\bshowRecentApps\\(\\)"), { Command.ShowRecentApps }, CommandTypeEnum.SHOW_RECENT_APPS),
        PatternInfo("recentApps3", Regex("(?i)\\bopenRecentApps\\(\\)"), { Command.ShowRecentApps }, CommandTypeEnum.SHOW_RECENT_APPS),
        PatternInfo("recentApps4", Regex("(?i)\\b(?:show|open|display|view|zeige|öffne|anzeigen) (?:the )?recent(?: apps| applications| tasks)?\\b"), { Command.ShowRecentApps }, CommandTypeEnum.SHOW_RECENT_APPS),
        PatternInfo("recentApps5", Regex("(?i)\\b(?:letzte apps|letzte anwendungen|app übersicht|app-übersicht|übersicht)\\b"), { Command.ShowRecentApps }, CommandTypeEnum.SHOW_RECENT_APPS),

        // Scroll patterns (simple)
        PatternInfo("scrollDown1", Regex("(?i)\\bscrollDown\\(\\)"), { Command.ScrollDown }, CommandTypeEnum.SCROLL_DOWN),
        PatternInfo("scrollDown2", Regex("(?i)\\bscrollDownPage\\(\\)"), { Command.ScrollDown }, CommandTypeEnum.SCROLL_DOWN),
        PatternInfo("scrollDown3", Regex("(?i)\\bpageDown\\(\\)"), { Command.ScrollDown }, CommandTypeEnum.SCROLL_DOWN),
        PatternInfo("scrollDown4", Regex("(?i)\\b(?:scroll|swipe|move|nach unten|runter) (?:down|nach unten|runter)\\b"), { Command.ScrollDown }, CommandTypeEnum.SCROLL_DOWN),
        PatternInfo("scrollDown5", Regex("(?i)\\b(?:nach unten scrollen|runter scrollen|nach unten wischen|runter wischen)\\b"), { Command.ScrollDown }, CommandTypeEnum.SCROLL_DOWN),
        PatternInfo("scrollUp1", Regex("(?i)\\bscrollUp\\(\\)"), { Command.ScrollUp }, CommandTypeEnum.SCROLL_UP),
        PatternInfo("scrollUp2", Regex("(?i)\\bscrollUpPage\\(\\)"), { Command.ScrollUp }, CommandTypeEnum.SCROLL_UP),
        PatternInfo("scrollUp3", Regex("(?i)\\bpageUp\\(\\)"), { Command.ScrollUp }, CommandTypeEnum.SCROLL_UP),
        PatternInfo("scrollUp4", Regex("(?i)\\b(?:scroll|swipe|move|nach oben|hoch) (?:up|nach oben|hoch)\\b"), { Command.ScrollUp }, CommandTypeEnum.SCROLL_UP),
        PatternInfo("scrollUp5", Regex("(?i)\\b(?:nach oben scrollen|hoch scrollen|nach oben wischen|hoch wischen)\\b"), { Command.ScrollUp }, CommandTypeEnum.SCROLL_UP),
        PatternInfo("scrollLeft1", Regex("(?i)\\bscrollLeft\\(\\)"), { Command.ScrollLeft }, CommandTypeEnum.SCROLL_LEFT),
        PatternInfo("scrollLeft2", Regex("(?i)\\bscrollLeftPage\\(\\)"), { Command.ScrollLeft }, CommandTypeEnum.SCROLL_LEFT),
        PatternInfo("scrollLeft3", Regex("(?i)\\bpageLeft\\(\\)"), { Command.ScrollLeft }, CommandTypeEnum.SCROLL_LEFT),
        PatternInfo("scrollLeft4", Regex("(?i)\\b(?:scroll|swipe|move|nach links) (?:left|nach links)\\b"), { Command.ScrollLeft }, CommandTypeEnum.SCROLL_LEFT),
        PatternInfo("scrollLeft5", Regex("(?i)\\b(?:nach links scrollen|links scrollen|nach links wischen|links wischen)\\b"), { Command.ScrollLeft }, CommandTypeEnum.SCROLL_LEFT),
        PatternInfo("scrollRight1", Regex("(?i)\\bscrollRight\\(\\)"), { Command.ScrollRight }, CommandTypeEnum.SCROLL_RIGHT),
        PatternInfo("scrollRight2", Regex("(?i)\\bscrollRightPage\\(\\)"), { Command.ScrollRight }, CommandTypeEnum.SCROLL_RIGHT),
        PatternInfo("scrollRight3", Regex("(?i)\\bpageRight\\(\\)"), { Command.ScrollRight }, CommandTypeEnum.SCROLL_RIGHT),
        PatternInfo("scrollRight4", Regex("(?i)\\b(?:scroll|swipe|move|nach rechts) (?:right|nach rechts)\\b"), { Command.ScrollRight }, CommandTypeEnum.SCROLL_RIGHT),
        PatternInfo("scrollRight5", Regex("(?i)\\b(?:nach rechts scrollen|rechts scrollen|nach rechts wischen|rechts wischen)\\b"), { Command.ScrollRight }, CommandTypeEnum.SCROLL_RIGHT),

        // Scroll from coordinates patterns
        PatternInfo("scrollDownCoords", Regex("(?i)\\bscrollDown\\s*\\(\\s*([\\d\\.%]+)\\s*,\\s*([\\d\\.%]+)\\s*,\\s*([\\d\\.%]+)\\s*,\\s*(\\d+)\\s*\\)"),
            { match -> Command.ScrollDownFromCoordinates(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4].toLong()) }, CommandTypeEnum.SCROLL_DOWN_FROM_COORDINATES),
        PatternInfo("scrollUpCoords", Regex("(?i)\\bscrollUp\\s*\\(\\s*([\\d\\.%]+)\\s*,\\s*([\\d\\.%]+)\\s*,\\s*([\\d\\.%]+)\\s*,\\s*(\\d+)\\s*\\)"),
            { match -> Command.ScrollUpFromCoordinates(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4].toLong()) }, CommandTypeEnum.SCROLL_UP_FROM_COORDINATES),
        PatternInfo("scrollLeftCoords", Regex("(?i)\\bscrollLeft\\s*\\(\\s*([\\d\\.%]+)\\s*,\\s*([\\d\\.%]+)\\s*,\\s*([\\d\\.%]+)\\s*,\\s*(\\d+)\\s*\\)"),
            { match -> Command.ScrollLeftFromCoordinates(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4].toLong()) }, CommandTypeEnum.SCROLL_LEFT_FROM_COORDINATES),
        PatternInfo("scrollRightCoords", Regex("(?i)\\bscrollRight\\s*\\(\\s*([\\d\\.%]+)\\s*,\\s*([\\d\\.%]+)\\s*,\\s*([\\d\\.%]+)\\s*,\\s*(\\d+)\\s*\\)"),
            { match -> Command.ScrollRightFromCoordinates(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4].toLong()) }, CommandTypeEnum.SCROLL_RIGHT_FROM_COORDINATES),

        // Open app patterns
        PatternInfo("openApp1", Regex("(?i)\\bopenApp\\([\"']([^\"']+)[\"']\\)"), { match -> Command.OpenApp(match.groupValues[1]) }, CommandTypeEnum.OPEN_APP),
        PatternInfo("openApp2", Regex("(?i)\\blaunchApp\\([\"']([^\"']+)[\"']\\)"), { match -> Command.OpenApp(match.groupValues[1]) }, CommandTypeEnum.OPEN_APP),
        PatternInfo("openApp3", Regex("(?i)\\bstartApp\\([\"']([^\"']+)[\"']\\)"), { match -> Command.OpenApp(match.groupValues[1]) }, CommandTypeEnum.OPEN_APP),
        PatternInfo("openApp4", Regex("(?i)\\b(?:open|launch|start|öffne|starte) (?:the )?(?:app|application|anwendung) [\"']([^\"']+)[\"']"), { match -> Command.OpenApp(match.groupValues[1]) }, CommandTypeEnum.OPEN_APP),
        PatternInfo("openApp5", Regex("(?i)\\b(?:öffne|starte) [\"']([^\"']+)[\"']"), { match -> Command.OpenApp(match.groupValues[1]) }, CommandTypeEnum.OPEN_APP)
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
    private fun processTextInternal(text: String): List<Command> {
        data class ProcessedMatch(val startIndex: Int, val endIndex: Int, val command: Command, val type: CommandTypeEnum)
        val foundRawMatches = mutableListOf<ProcessedMatch>()
        val finalCommands = mutableListOf<Command>()
        val addedSingleInstanceCommands = mutableSetOf<CommandTypeEnum>()

        for (patternInfo in ALL_PATTERNS) {
            try {
                patternInfo.regex.findAll(text).forEach { matchResult ->
                    try {
                        val command = patternInfo.commandBuilder(matchResult)
                        // Store the commandType from the patternInfo that generated this command
                        foundRawMatches.add(ProcessedMatch(matchResult.range.first, matchResult.range.last, command, patternInfo.commandType))
                        Log.d(TAG, "Found raw match: Start=${matchResult.range.first}, End=${matchResult.range.last}, Command=${command}, Type=${patternInfo.commandType}, Pattern=${patternInfo.id}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error building command for pattern ${patternInfo.id} with match ${matchResult.value}: ${e.message}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error finding matches for pattern ${patternInfo.id}: ${e.message}", e)
            }
        }

        // Sort matches by start index
        foundRawMatches.sortBy { it.startIndex }
        Log.d(TAG, "Sorted raw matches (${foundRawMatches.size}): $foundRawMatches")

        var currentPosition = 0
        for (processedMatch in foundRawMatches) {
            val (startIndex, endIndex, command, commandTypeFromMatch) = processedMatch // Destructure
            if (startIndex >= currentPosition) {
                var canAdd = true
                // Use commandTypeFromMatch directly here
                val isSingleInstanceType = when (commandTypeFromMatch) {
                    CommandTypeEnum.TAKE_SCREENSHOT -> true // Only TakeScreenshot is single-instance
                    else -> false
                }
                if (isSingleInstanceType) {
                    if (addedSingleInstanceCommands.contains(commandTypeFromMatch)) {
                        canAdd = false
                        Log.d(TAG, "Skipping duplicate single-instance command: $command (Type: $commandTypeFromMatch)")
                    } else {
                        addedSingleInstanceCommands.add(commandTypeFromMatch)
                    }
                }

                if (canAdd) {
                    // Simplified duplicate check: if it's not a single instance type, allow it.
                    // More sophisticated duplicate checks for parameterized commands can be added here if needed.
                    // For now, only single-instance types are strictly controlled for duplication.
                    // The overlap filter (startIndex >= currentPosition) already prevents identical commands
                    // from the exact same text span.
                    finalCommands.add(command)
                    currentPosition = endIndex + 1
                    Log.d(TAG, "Added command: $command. New currentPosition: $currentPosition")
                }
            } else {
                Log.d(TAG, "Skipping overlapping command: $command (startIndex $startIndex < currentPosition $currentPosition)")
            }
        }
        Log.d(TAG, "Final commands list (${finalCommands.size}): $finalCommands")
        return finalCommands
    }

    /**
     * Process text to find commands
     */
    private fun processText(text: String, commands: MutableList<Command>) {
        val extractedCommands = processTextInternal(text)
        commands.addAll(extractedCommands)
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
    data class TapCoordinates(val x: String, val y: String) : Command()

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
    data class ScrollDownFromCoordinates(val x: String, val y: String, val distance: String, val duration: Long) : Command()

    /**
     * Command to scroll up from specific coordinates with custom distance and duration
     */
    data class ScrollUpFromCoordinates(val x: String, val y: String, val distance: String, val duration: Long) : Command()

    /**
     * Command to scroll left from specific coordinates with custom distance and duration
     */
    data class ScrollLeftFromCoordinates(val x: String, val y: String, val distance: String, val duration: Long) : Command()

    /**
     * Command to scroll right from specific coordinates with custom distance and duration
     */
    data class ScrollRightFromCoordinates(val x: String, val y: String, val distance: String, val duration: Long) : Command()

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
