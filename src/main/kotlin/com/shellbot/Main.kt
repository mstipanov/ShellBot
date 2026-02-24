package com.shellbot

import kotlinx.cli.*
import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(ShellBotMain::class.java)

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
                log.info("ShellBot starting, command: {}", command)
            }

            val exitCode = if (isTmuxAvailable()) {
                TmuxSession(command).run()
            } else {
                ShellBot(command).run()
            }

            if (verbose) {
                log.info("Process exited with code: {}", exitCode)
            }

            exitProcess(exitCode)

        } catch (e: IllegalArgumentException) {
            log.error("Error: {}", e.message)
            log.error("Usage: shellbot -c \"command\"")
            exitProcess(1)
        } catch (e: Exception) {
            log.error("Unexpected error", e)
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
