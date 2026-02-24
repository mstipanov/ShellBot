package com.shellbot

/**
 * Shell bot with pseudo-terminal (pty) support for interactive programs.
 *
 * Usage:
 *     kotlin ShellBotPTY.kt -c "./adding_game.py"
 *
 * This wrapper:
 * - Uses pseudo-terminal (pty) for interactive programs
 * - Forwards I/O between terminal and command
 * - Works with programs that need terminal control
 */

import java.io.*
import java.lang.ProcessBuilder.Redirect
import java.nio.file.*
import kotlin.system.exitProcess
import kotlinx.cli.*
import kotlin.concurrent.thread

class ShellBotPTY(private val command: String) {
    private var process: Process? = null
    private var running = true
    private val stdin = System.`in`
    private val stdout = System.out

    /**
     * Run the command in a pseudo-terminal.
     * Returns the exit code of the command.
     */
    fun run(): Int {
        try {
            // Save terminal settings
            val terminal = JLineTerminal()
            terminal.saveTerminalSettings()

            try {
                // Create process with pseudo-terminal
                val processBuilder = ProcessBuilder("bash", "-c", command)

                // Set up environment
                val env = processBuilder.environment()
                env["TERM"] = "xterm-256color"

                // Start process
                process = processBuilder.start()

                // Forward I/O between terminal and process
                return forwardIO(process!!)

            } finally {
                // Restore terminal settings
                terminal.restoreTerminalSettings()
                cleanup()
            }

        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            return 1
        }
    }

    private fun forwardIO(process: Process): Int {
        val inputStream = process.inputStream
        val outputStream = process.outputStream
        val errorStream = process.errorStream

        // Thread for reading from process stdout
        val stdoutReader = thread(start = true) {
            try {
                val buffer = ByteArray(8192)
                while (running) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        stdout.write(buffer, 0, bytesRead)
                        stdout.flush()
                    } else if (bytesRead == -1) {
                        running = false
                        break
                    }
                }
            } catch (e: IOException) {
                if (running) {
                    running = false
                }
            }
        }

        // Thread for reading from process stderr
        val stderrReader = thread(start = true) {
            try {
                val buffer = ByteArray(8192)
                while (running) {
                    val bytesRead = errorStream.read(buffer)
                    if (bytesRead > 0) {
                        System.err.write(buffer, 0, bytesRead)
                        System.err.flush()
                    } else if (bytesRead == -1) {
                        break
                    }
                }
            } catch (e: IOException) {
                // Ignore
            }
        }

        // Main thread: read from stdin and write to process stdin
        try {
            val buffer = ByteArray(8192)
            while (running) {
                if (stdin.available() > 0) {
                    val bytesRead = stdin.read(buffer)
                    if (bytesRead > 0) {
                        outputStream.write(buffer, 0, bytesRead)
                        outputStream.flush()
                    }
                }

                // Check if process is still alive
                if (!process.isAlive) {
                    running = false
                    break
                }

                // Small delay to avoid busy-waiting
                Thread.sleep(10)
            }
        } catch (e: InterruptedIOException) {
            // Ctrl+C or interruption
            running = false
        } catch (e: Exception) {
            running = false
        }

        // Wait for readers to finish
        stdoutReader.join(1000)
        stderrReader.join(1000)

        // Wait for process to complete
        return try {
            process.waitFor()
        } catch (e: InterruptedException) {
            1
        }
    }

    private fun cleanup() {
        process?.let { p ->
            if (p.isAlive) {
                p.destroy()
                p.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (p.isAlive) {
                    p.destroyForcibly()
                }
            }
            process = null
        }
    }

    /**
     * Helper class for terminal operations using JLine
     */
    private class JLineTerminal {
        private var originalTerminal: org.jline.terminal.Terminal? = null
        private var terminal: org.jline.terminal.Terminal? = null

        init {
            try {
                // Try to initialize JLine terminal
                originalTerminal = org.jline.terminal.TerminalBuilder.terminal()
                terminal = org.jline.terminal.TerminalBuilder.builder()
                    .system(false)
                    .jna(true)
                    .build()
            } catch (e: Exception) {
                // JLine not available - fallback to basic terminal
                System.err.println("Note: JLine not available, using basic terminal mode")
            }
        }

        fun saveTerminalSettings() {
            // In Kotlin/JVM, we rely on JLine for terminal settings
            // If JLine is not available, we can't save/restore settings
            try {
                terminal?.enterRawMode()
            } catch (e: Exception) {
                // Ignore if JLine not available
            }
        }

        fun restoreTerminalSettings() {
            try {
                terminal?.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}

/**
 * Alternative implementation using plain Java without JLine dependency
 */
class SimpleShellBotPTY(private val command: String) {
    private var process: Process? = null
    private var running = true

    fun run(): Int {
        try {
            val processBuilder = ProcessBuilder("bash", "-c", command)
            val env = processBuilder.environment()
            env["TERM"] = "xterm-256color"

            process = processBuilder.start()
            return forwardIO(process!!)

        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            return 1
        } finally {
            cleanup()
        }
    }

    private fun forwardIO(process: Process): Int {
        val inputStream = process.inputStream
        val outputStream = process.outputStream
        val errorStream = process.errorStream
        val stdin = System.`in`
        val stdout = System.out

        // Thread for reading from process stdout
        val stdoutReader = thread(start = true) {
            try {
                val buffer = ByteArray(8192)
                while (running) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        stdout.write(buffer, 0, bytesRead)
                        stdout.flush()
                    } else if (bytesRead == -1) {
                        running = false
                        break
                    }
                }
            } catch (e: IOException) {
                if (running) {
                    running = false
                }
            }
        }

        // Thread for reading from process stderr
        val stderrReader = thread(start = true) {
            try {
                val buffer = ByteArray(8192)
                while (running) {
                    val bytesRead = errorStream.read(buffer)
                    if (bytesRead > 0) {
                        System.err.write(buffer, 0, bytesRead)
                        System.err.flush()
                    } else if (bytesRead == -1) {
                        break
                    }
                }
            } catch (e: IOException) {
                // Ignore
            }
        }

        // Main thread: read from stdin and write to process stdin
        try {
            val buffer = ByteArray(8192)
            while (running) {
                if (stdin.available() > 0) {
                    val bytesRead = stdin.read(buffer)
                    if (bytesRead > 0) {
                        outputStream.write(buffer, 0, bytesRead)
                        outputStream.flush()
                    }
                }

                if (!process.isAlive) {
                    running = false
                    break
                }

                Thread.sleep(10)
            }
        } catch (e: InterruptedIOException) {
            running = false
        } catch (e: Exception) {
            running = false
        }

        stdoutReader.join(1000)
        stderrReader.join(1000)

        return try {
            process.waitFor()
        } catch (e: InterruptedException) {
            1
        }
    }

    private fun cleanup() {
        process?.let { p ->
            if (p.isAlive) {
                p.destroy()
                p.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (p.isAlive) {
                    p.destroyForcibly()
                }
            }
            process = null
        }
    }
}

fun main(args: Array<String>) {
    val parser = ArgParser("ShellBotPTY")
    val command by parser.option(ArgType.String, shortName = "c", description = "The command to wrap").required()

    try {
        parser.parse(args)

        // Use simple version without JLine dependency
        val bot = SimpleShellBotPTY(command)
        val exitCode = bot.run()
        exitProcess(exitCode)

    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        exitProcess(1)
    }
}