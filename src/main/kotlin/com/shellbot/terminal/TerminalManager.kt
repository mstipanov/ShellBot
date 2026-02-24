package com.shellbot.terminal

import org.jline.terminal.TerminalBuilder
import org.jline.terminal.Terminal
import org.jline.utils.NonBlockingReader
import java.io.*

/**
 * Terminal manager using JLine for better terminal support.
 * Provides raw mode, character reading, and terminal size handling.
 */
class TerminalManager {
    private var terminal: Terminal? = null
    private var reader: NonBlockingReader? = null
    private var originalTerminal: Terminal? = null

    /**
     * Initialize the terminal in raw mode.
     */
    fun initialize(): Boolean {
        return try {
            // Save original terminal if possible
            originalTerminal = TerminalBuilder.terminal()

            // Create terminal with raw mode support
            terminal = TerminalBuilder.builder()
                .system(true)
                .jna(true)
                .dumb(false)
                .build()

            // Create non-blocking reader
            reader = terminal?.reader()

            true
        } catch (e: Exception) {
            System.err.println("Failed to initialize JLine terminal: ${e.message}")
            false
        }
    }

    /**
     * Read a character from terminal with timeout.
     * @return Character code, or -1 if no input available
     */
    fun readChar(timeout: Int = 10): Int {
        return try {
            reader?.read(timeout.toLong()) ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Read available bytes from terminal.
     */
    fun readBytes(buffer: ByteArray): Int {
        return try {
            var totalRead = 0
            val charBuffer = CharArray(buffer.size)
            val charsRead = reader?.read(charBuffer, 0, charBuffer.size) ?: 0

            for (i in 0 until charsRead) {
                buffer[i] = charBuffer[i].code.toByte()
                totalRead++
            }

            totalRead
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Write bytes to terminal output.
     */
    fun write(bytes: ByteArray) {
        try {
            terminal?.output()?.write(bytes)
            terminal?.output()?.flush()
        } catch (e: Exception) {
            // Ignore write errors
        }
    }

    /**
     * Get terminal width.
     */
    fun getWidth(): Int {
        return terminal?.width ?: 80
    }

    /**
     * Get terminal height.
     */
    fun getHeight(): Int {
        return terminal?.height ?: 24
    }

    /**
     * Restore terminal to original state.
     */
    fun restore() {
        try {
            reader?.close()
            terminal?.close()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
        reader = null
        terminal = null
    }

    /**
     * Check if terminal is properly initialized.
     */
    fun isInitialized(): Boolean {
        return terminal != null && reader != null
    }
}