package com.shellbot

import com.shellbot.terminal.TerminalManager
import java.io.*

/**
 * Advanced ShellBot with JLine terminal support.
 * Provides better terminal handling and raw mode support.
 */
class AdvancedShellBot(private val command: String) {
    private var process: Process? = null
    private var running = true
    private val terminalManager = TerminalManager()

    /**
     * Run the command with advanced terminal support.
     */
    fun run(): Int {
        return if (terminalManager.initialize()) {
            runWithTerminal()
        } else {
            // Fall back to basic implementation
            println("Warning: Using basic terminal mode")
            BasicShellBot(command).run()
        }
    }

    /**
     * Run with JLine terminal support.
     */
    private fun runWithTerminal(): Int {
        try {
            println("ShellBot: Running '$command' with advanced terminal support")

            // Set up process
            val processBuilder = ProcessBuilder("bash", "-c", command)
            val env = processBuilder.environment()
            env["TERM"] = "xterm-256color"
            env["COLUMNS"] = terminalManager.getWidth().toString()
            env["LINES"] = terminalManager.getHeight().toString()

            process = processBuilder.start()
            return forwardWithTerminal()

        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            return 1
        } finally {
            terminalManager.restore()
            cleanup()
        }
    }

    /**
     * Forward I/O using terminal manager.
     */
    private fun forwardWithTerminal(): Int {
        val process = this.process ?: return 1
        val processStdin = process.outputStream
        val processStdout = process.inputStream
        val processStderr = process.errorStream

        // Thread for process stdout -> terminal
        val stdoutThread = Thread {
            try {
                val buffer = ByteArray(1024)
                while (running) {
                    val bytesRead = processStdout.read(buffer)
                    when {
                        bytesRead > 0 -> {
                            // Write to terminal
                            terminalManager.write(buffer.sliceArray(0 until bytesRead))
                        }
                        bytesRead == -1 -> {
                            running = false
                            break
                        }
                    }
                }
            } catch (e: IOException) {
                running = false
            }
        }

        // Thread for process stderr -> terminal error
        val stderrThread = Thread {
            try {
                val buffer = ByteArray(1024)
                while (running) {
                    val bytesRead = processStderr.read(buffer)
                    if (bytesRead > 0) {
                        // Also write stderr to terminal
                        terminalManager.write(buffer.sliceArray(0 until bytesRead))
                    } else if (bytesRead == -1) {
                        break
                    }
                }
            } catch (e: IOException) {
                // Ignore
            }
        }

        stdoutThread.start()
        stderrThread.start()

        // Main thread: terminal input -> process stdin
        try {
            val inputBuffer = ByteArray(1024)

            while (running && process.isAlive) {
                // Read from terminal
                val bytesRead = terminalManager.readBytes(inputBuffer)
                if (bytesRead > 0) {
                    // Send to process
                    processStdin.write(inputBuffer, 0, bytesRead)
                    processStdin.flush()

                    // Check for control characters
                    if (bytesRead == 1) {
                        when (inputBuffer[0].toInt()) {
                            3 -> { // Ctrl+C
                                println("\n^C")
                                running = false
                                process.destroy()
                            }
                            4 -> { // Ctrl+D
                                running = false
                                processStdin.close()
                            }
                        }
                    }
                }

                // Check process status
                if (!process.isAlive) {
                    running = false
                    break
                }

                // Small sleep to prevent busy waiting
                Thread.sleep(5)
            }

        } catch (e: Exception) {
            running = false
        }

        // Wait for threads
        try {
            stdoutThread.join(1000)
            stderrThread.join(1000)
        } catch (e: InterruptedException) {
            // Ignore
        }

        // Get exit code
        return try {
            process.waitFor()
        } catch (e: InterruptedException) {
            1
        }
    }

    /**
     * Clean up resources.
     */
    private fun cleanup() {
        process?.let { p ->
            if (p.isAlive) {
                p.destroy()
                try {
                    p.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (p.isAlive) {
                        p.destroyForcibly()
                    }
                } catch (e: InterruptedException) {
                    // Ignore
                }
            }
        }
        process = null
    }

    /**
     * Stop the ShellBot.
     */
    fun stop() {
        running = false
        terminalManager.restore()
        cleanup()
    }
}

/**
 * Basic ShellBot fallback implementation.
 */
private class BasicShellBot(private val command: String) {
    fun run(): Int {
        return ShellBot(command).run()
    }
}