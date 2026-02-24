package com.shellbot

import kotlinx.cli.*
import kotlin.system.exitProcess

/**
 * Main entry point for ShellBot.
 *
 * Usage: shellbot -c "command"
 *
 * If tmux is available, runs inside a tmux session with side-channels:
 *   - ~/.shellbot/input.txt / output.txt for file-based I/O
 *   - Telegram bot if ~/.shellbot/telegram.token or TELEGRAM_BOT_TOKEN is set
 * Otherwise falls back to plain inheritIO.
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
        ).required()

        val verbose by parser.option(
            ArgType.Boolean,
            shortName = "v",
            fullName = "verbose",
            description = "Enable verbose output"
        ).default(false)

        try {
            parser.parse(args)

            if (verbose) {
                println("ShellBot")
                println("Command: $command")
            }

            val exitCode = if (isTmuxAvailable()) {
                TmuxSession(command).run()
            } else {
                ShellBot(command).run()
            }

            if (verbose) {
                println("\nProcess exited with code: $exitCode")
            }

            exitProcess(exitCode)

        } catch (e: IllegalArgumentException) {
            System.err.println("Error: ${e.message}")
            System.err.println("Usage: shellbot -c \"command\"")
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
