package com.shellbot

import kotlinx.cli.*
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * Main entry point for ShellBot.
 *
 * Usage: shellbot -c "command"
 *
 * Runs inside a tmux session with side-channels:
 *   - ~/.shellbot/input.txt / output.txt for file-based I/O
 *   - Telegram bot if configured in ~/.shellbot/settings.yaml
 * If tmux is not installed, attempts to install it; exits with an error if unavailable.
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

        val sessionId by parser.option(
            ArgType.String,
            shortName = "s",
            fullName = "session",
            description = "Session ID to use (default: 'shellbot')"
        ).default("shellbot")

        try {
            parser.parse(args)

            if (verbose) {
                log.info("ShellBot starting, command: {}", command)
            }

            val settings = Settings.promptSessionSetup(sessionId)

            ensureTmux()

            val exitCode = TmuxSession(command, sessionId, settings).run()

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

    /**
     * Ensures tmux is installed. Returns if tmux is available (either already
     * installed or successfully installed). If tmux is missing and installation fails,
     * prints a note to the console and exits with an error.
     */
    private fun ensureTmux() {
        if (isTmuxAvailable()) {
            return
        }

        log.warn("tmux is not installed, attempting to install it...")

        val installCmds = listOf(
            listOf("brew", "install", "tmux"),
            listOf("apt-get", "install", "-y", "tmux"),
            listOf("yum", "install", "-y", "tmux")
        )

        for (cmd in installCmds) {
            try {
                val p = ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
                if (p.waitFor() == 0 && isTmuxAvailable()) {
                    log.info("tmux installed successfully via ${cmd[0]}")
                    return
                }
            } catch (_: Exception) {
                // try next package manager
            }
        }

        log.error("Failed to install tmux")
        System.err.println("tmux is missing and needs to be installed.")
        System.err.println("ShellBot relies on tmux to wrap sessions and run the Telegram bot.")
        System.err.println("Please install tmux manually, e.g. 'brew install tmux'.")
        exitProcess(1)
    }
}
