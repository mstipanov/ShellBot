package com.shellbot

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShellBotTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun testEchoCommand() {
        val shellBot = ShellBot("echo 'Hello, ShellBot!'")
        val exitCode = shellBot.run()
        assertEquals(0, exitCode, "Echo command should exit with code 0")
    }

    @Test
    fun testExitCode() {
        val shellBot = ShellBot("exit 42")
        val exitCode = shellBot.run()
        assertEquals(42, exitCode, "Should return specified exit code")
    }

    @Test
    fun testFileCreation() {
        val testFile = File(tempDir.toFile(), "test.txt")
        val shellBot = ShellBot("touch ${testFile.absolutePath}")
        val exitCode = shellBot.run()

        assertEquals(0, exitCode, "Touch command should succeed")
        assertTrue(testFile.exists(), "Test file should be created")
    }

    @Test
    fun testPythonScript() {
        val pythonScript = File(tempDir.toFile(), "test.py").apply {
            writeText("""
                import sys
                print("Python test")
                sys.exit(0)
            """.trimIndent())
        }

        val shellBot = ShellBot("python3 ${pythonScript.absolutePath}")
        val exitCode = shellBot.run()
        assertEquals(0, exitCode, "Python script should exit with code 0")
    }

    @Test
    fun testInvalidCommand() {
        val shellBot = ShellBot("nonexistentcommand12345")
        val exitCode = shellBot.run()
        assertTrue(exitCode != 0, "Invalid command should return non-zero exit code")
    }
}