package com.shellbot

import com.shellbot.telegram.TelegramBot
import kotlinx.cli.*
import kotlin.system.exitProcess

/**
 * Main entry point for ShellBot PTY.
 *
 * Usage:
 *   Local mode:    shellbot -c "command"
 *   Telegram mode: shellbot --telegram
 */
object ShellBotMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val parser = ArgParser("shellbot")
        val command by parser.option(
            ArgType.String,
            shortName = "c",
            fullName = "command",
            description = "The command to execute"
        )

        val verbose by parser.option(
            ArgType.Boolean,
            shortName = "v",
            fullName = "verbose",
            description = "Enable verbose output"
        ).default(false)

        val env by parser.option(
            ArgType.String,
            shortName = "e",
            fullName = "env",
            description = "Set environment variable (format: KEY=VALUE)"
        ).multiple()

        val workingDir by parser.option(
            ArgType.String,
            shortName = "d",
            fullName = "working-dir",
            description = "Working directory for the command"
        ).default(".")

        val telegram by parser.option(
            ArgType.Boolean,
            fullName = "telegram",
            description = "Run as a Telegram bot (requires TELEGRAM_BOT_TOKEN env var)"
        ).default(false)

        try {
            parser.parse(args)

            if (telegram) {
                val token = System.getenv("TELEGRAM_BOT_TOKEN")
                if (token.isNullOrBlank()) {
                    System.err.println("Error: TELEGRAM_BOT_TOKEN environment variable is required for --telegram mode")
                    exitProcess(1)
                }
                val bot = TelegramBot(token)
                bot.run()
                return
            }

            // Local mode requires -c
            if (command == null) {
                System.err.println("Error: -c/--command is required in local mode")
                System.err.println("Usage: shellbot -c \"command\" [-v] [-e KEY=VALUE]... [-d working_dir]")
                System.err.println("       shellbot --telegram")
                exitProcess(1)
            }

            if (verbose) {
                println("ShellBot PTY - Kotlin Version")
                println("Command: $command")
                if (env.isNotEmpty()) {
                    println("Environment variables:")
                    env.forEach { println("  $it") }
                }
                println("Working directory: $workingDir")
                println()
            }

            // Use tmux if available (enables file-based side-channel I/O),
            // otherwise fall back to plain inheritIO.
            val exitCode = if (isTmuxAvailable()) {
                TmuxSession(command!!).run()
            } else {
                ShellBot(command!!).run()
            }

            if (verbose) {
                println("\nShellBot exited with code: $exitCode")
            }

            exitProcess(exitCode)

        } catch (e: IllegalArgumentException) {
            System.err.println("Error: ${e.message}")
            System.err.println("Usage: shellbot -c \"command\" [-v] [-e KEY=VALUE]... [-d working_dir]")
            System.err.println("       shellbot --telegram")
            exitProcess(1)
        } catch (e: Exception) {
            System.err.println("Unexpected error: ${e.message}")
            e.printStackTrace()
            exitProcess(1)
        }
    }

    private fun isTmuxAvailable(): Boolean {
        return try {
            val p = ProcessBuilder("tmux", "-V")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}
