package com.google.ai.sample.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    @Test
    fun `test tapAtCoordinates with pixel values`() {
        val commandText = "tapAtCoordinates(100, 200)"
        val commands = CommandParser.parseCommands(commandText, clearBuffer = true)
        assertEquals(1, commands.size)
        assertTrue(commands[0] is Command.TapCoordinates)
        val tapCommand = commands[0] as Command.TapCoordinates
        assertEquals("100", tapCommand.x)
        assertEquals("200", tapCommand.y)
    }

    @Test
    fun `test tapAtCoordinates with percentage values`() {
        val commandText = "tapAtCoordinates(50%, 25%)"
        val commands = CommandParser.parseCommands(commandText, clearBuffer = true)
        assertEquals(1, commands.size)
        assertTrue(commands[0] is Command.TapCoordinates)
        val tapCommand = commands[0] as Command.TapCoordinates
        assertEquals("50%", tapCommand.x)
        assertEquals("25%", tapCommand.y)
    }

    @Test
    fun `test tapAtCoordinates with mixed percentage and pixel values`() {
        val commandText = "tapAtCoordinates(50%, 200)"
        CommandParser.clearBuffer() // Clear buffer before test
        val commands = CommandParser.parseCommands(commandText)
        assertEquals(1, commands.size)
        assertTrue(commands[0] is Command.TapCoordinates)
        val tapCommand = commands[0] as Command.TapCoordinates
        assertEquals("50%", tapCommand.x)
        assertEquals("200", tapCommand.y)
    }

    @Test
    fun `test tapAtCoordinates with decimal percentage values`() {
        val commandText = "tapAtCoordinates(10.5%, 80.2%)"
        CommandParser.clearBuffer() // Clear buffer before test
        val commands = CommandParser.parseCommands(commandText)
        assertEquals(1, commands.size)
        assertTrue(commands[0] is Command.TapCoordinates)
        val tapCommand = commands[0] as Command.TapCoordinates
        assertEquals("10.5%", tapCommand.x)
        assertEquals("80.2%", tapCommand.y)
    }

    @Test
    fun `test scrollDown with pixel values`() {
        val commandText = "scrollDown(50, 100, 100, 200)"
        val commands = CommandParser.parseCommands(commandText, clearBuffer = true)
        assertEquals(1, commands.size)
        assertTrue(commands[0] is Command.ScrollDownFromCoordinates)
        val scrollCommand = commands[0] as Command.ScrollDownFromCoordinates
        assertEquals("50", scrollCommand.x)
        assertEquals("100", scrollCommand.y)
        assertEquals("100", scrollCommand.distance) // Expect String
        assertEquals(200L, scrollCommand.duration)
    }

    @Test
    fun `test scrollDown with percentage x y and pixel distance`() {
        val commandText = "scrollDown(10%, 90%, 100, 200)"
        val commands = CommandParser.parseCommands(commandText, clearBuffer = true)
        assertEquals(1, commands.size)
        assertTrue(commands[0] is Command.ScrollDownFromCoordinates)
        val scrollCommand = commands[0] as Command.ScrollDownFromCoordinates
        assertEquals("10%", scrollCommand.x)
        assertEquals("90%", scrollCommand.y)
        assertEquals("100", scrollCommand.distance) // Expect String
        assertEquals(200L, scrollCommand.duration)
    }

    @Test
    fun `test scrollDown with percentage x y and percentage distance`() {
        val commandText = "scrollDown(10%, 20%, 30%, 500)"
        CommandParser.clearBuffer()
        val commands = CommandParser.parseCommands(commandText)
        assertEquals(1, commands.size)
        assertTrue(commands[0] is Command.ScrollDownFromCoordinates)
        val scrollCommand = commands[0] as Command.ScrollDownFromCoordinates
        assertEquals("10%", scrollCommand.x)
        assertEquals("20%", scrollCommand.y)
        assertEquals("30%", scrollCommand.distance) // Expect String
        assertEquals(500L, scrollCommand.duration)
    }

    @Test
    fun `test scrollUp with percentage x y and pixel distance`() {
        val commandText = "scrollUp(10.5%, 80.2%, 150, 250)"
        val commands = CommandParser.parseCommands(commandText, clearBuffer = true)
        assertEquals(1, commands.size)
        assertTrue(commands[0] is Command.ScrollUpFromCoordinates)
        val scrollCommand = commands[0] as Command.ScrollUpFromCoordinates
        assertEquals("10.5%", scrollCommand.x)
        assertEquals("80.2%", scrollCommand.y)
        assertEquals("150", scrollCommand.distance) // Expect String
        assertEquals(250L, scrollCommand.duration)
    }

    @Test
    fun `test scrollUp with percentage x y and percentage distance`() {
        val commandText = "scrollUp(10%, 20%, \"30.5%\", 500)" // Quotes around distance for clarity, regex handles it
        CommandParser.clearBuffer()
        val commands = CommandParser.parseCommands(commandText)
        assertEquals(1, commands.size)
        assertTrue(commands[0] is Command.ScrollUpFromCoordinates)
        val scrollCommand = commands[0] as Command.ScrollUpFromCoordinates
        assertEquals("10%", scrollCommand.x)
        assertEquals("20%", scrollCommand.y)
        assertEquals("30.5%", scrollCommand.distance) // Expect String
        assertEquals(500L, scrollCommand.duration)
    }

    @Test
    fun `test scrollLeft with percentage x y and pixel distance`() {
        val commandText = "scrollLeft(5%, 15%, 50, 100)"
        val commands = CommandParser.parseCommands(commandText, clearBuffer = true)
        assertEquals(1, commands.size)
        assertTrue(commands[0] is Command.ScrollLeftFromCoordinates)
        val scrollCommand = commands[0] as Command.ScrollLeftFromCoordinates
        assertEquals("5%", scrollCommand.x)
        assertEquals("15%", scrollCommand.y)
        assertEquals("50", scrollCommand.distance) // Expect String
        assertEquals(100L, scrollCommand.duration)
    }

    @Test
    fun `test scrollLeft with percentage x y and percentage distance`() {
        val commandText = "scrollLeft(5%, 10%, \"15.5%\", 300)"
        CommandParser.clearBuffer()
        val commands = CommandParser.parseCommands(commandText)
        assertEquals(1, commands.size)
        assertTrue(commands[0] is Command.ScrollLeftFromCoordinates)
        val scrollCommand = commands[0] as Command.ScrollLeftFromCoordinates
        assertEquals("5%", scrollCommand.x)
        assertEquals("10%", scrollCommand.y)
        assertEquals("15.5%", scrollCommand.distance) // Expect String
        assertEquals(300L, scrollCommand.duration)
    }

    @Test
    fun `test scrollRight with percentage x y and pixel distance`() {
        val commandText = "scrollRight(95%, 85%, 75, 150)"
        val commands = CommandParser.parseCommands(commandText, clearBuffer = true)
        assertEquals(1, commands.size)
        assertTrue(commands[0] is Command.ScrollRightFromCoordinates)
        val scrollCommand = commands[0] as Command.ScrollRightFromCoordinates
        assertEquals("95%", scrollCommand.x)
        assertEquals("85%", scrollCommand.y)
        assertEquals("75", scrollCommand.distance) // Expect String
        assertEquals(150L, scrollCommand.duration)
    }

    @Test
    fun `test scrollRight with percentage x y and percentage distance`() {
        val commandText = "scrollRight(90%, 80%, \"25%\", 400)"
        CommandParser.clearBuffer()
        val commands = CommandParser.parseCommands(commandText)
        assertEquals(1, commands.size)
        assertTrue(commands[0] is Command.ScrollRightFromCoordinates)
        val scrollCommand = commands[0] as Command.ScrollRightFromCoordinates
        assertEquals("90%", scrollCommand.x)
        assertEquals("80%", scrollCommand.y)
        assertEquals("25%", scrollCommand.distance) // Expect String
        assertEquals(400L, scrollCommand.duration)
    }

    // Test cases for natural language commands
    @Test
    fun `test tap at coordinates with percentage values natural language`() {
        val commandText = "tap at coordinates (50.5%, 25.2%)"
        CommandParser.clearBuffer()
        val commands = CommandParser.parseCommands(commandText)
        assertEquals(1, commands.size)
        assertTrue(commands[0] is Command.TapCoordinates)
        val tapCommand = commands[0] as Command.TapCoordinates
        assertEquals("50.5%", tapCommand.x)
        assertEquals("25.2%", tapCommand.y)
    }

    @Test
    fun `test tap on 20 percent and 30 percent`() {
        val commandText = "tap on 20%, 30%"
        CommandParser.clearBuffer()
        val commands = CommandParser.parseCommands(commandText)
        assertEquals(1, commands.size)
        assertTrue(commands[0] is Command.TapCoordinates)
        val tapCommand = commands[0] as Command.TapCoordinates
        assertEquals("20%", tapCommand.x)
        assertEquals("30%", tapCommand.y)
    }

    @Test
    fun `test tap at 20% and 30%`() {
        val commandText = "tap at 20% and 30%"
        CommandParser.clearBuffer()
        val commands = CommandParser.parseCommands(commandText)
        assertEquals(1, commands.size)
        assertTrue(commands[0] is Command.TapCoordinates)
        val tapCommand = commands[0] as Command.TapCoordinates
        assertEquals("20%", tapCommand.x)
        assertEquals("30%", tapCommand.y)
    }
}
