package com.shellbot

import com.shellbot.plugin.SessionPluginLoader
import com.shellbot.telegram.TelegramBot
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Paths

/**
 * Runs a command inside a tmux session with full terminal access.
 *
 * Side-channels (all optional, run as daemon threads):
 *   - ~/.shellbot/output.txt — last 10 lines of visible pane output
 *   - ~/.shellbot/input.txt  — write text here to inject it as keyboard input
 *   - Telegram bot            — if a token is found, provides remote /output and input
 *
 * tmux owns the PTY, so the child process (claude, etc.) gets a real terminal.
 */
class TmuxSession(private val command: String, private val noTelegram: Boolean = false, private val sessionId: String = "shellbot") {
    private val log = LoggerFactory.getLogger(TmuxSession::class.java)

    companion object {
        private val CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".shellbot").toFile()
        private val TOKEN_FILE = File(CONFIG_DIR, "telegram.token")
    }

    private val SESSION_NAME = sessionId
    private val INPUT_FILE = File(CONFIG_DIR, "input-$sessionId.txt")
    private val OUTPUT_FILE = File(CONFIG_DIR, "output-$sessionId.txt")

    fun run(): Int {
        // Validate session name (tmux session names must match regex: [a-zA-Z0-9_-]+)
        if (!SESSION_NAME.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
            log.error("Invalid session name: '{}'. Session names can only contain letters, numbers, underscores and hyphens.", SESSION_NAME)
            return 1
        }

        CONFIG_DIR.mkdirs()
        INPUT_FILE.writeText("")
        OUTPUT_FILE.writeText("")

        // Kill any leftover session from a previous run
        exec("tmux", "kill-session", "-t", SESSION_NAME)

        // Start detached tmux session running the command, atomically setting
        // "remain-on-exit" so the pane survives even if the command exits instantly.
        // The ";" argument is tmux's command separator — both commands are processed
        // in a single tmux event loop iteration, before any "child exited" event.
        val startResult = exec(
            "tmux", "new-session", "-d", "-s", SESSION_NAME, command,
            ";", "set-option", "-t", SESSION_NAME, "remain-on-exit", "on",
            ";", "set-option", "-t", SESSION_NAME, "mouse", "on",
            ";", "set-option", "-t", SESSION_NAME, "history-limit", "5000",
            ";", "set-option", "-t", SESSION_NAME, "status-position", "top",
            ";", "set-option", "-t", SESSION_NAME, "status-interval", "1"
        )
        if (startResult != 0) {
            log.error("Failed to create tmux session (is tmux installed?)")
            return 1
        }

        // Wait briefly to check if the command exits almost immediately.
        // remain-on-exit keeps the session alive so we can still capture output.
        Thread.sleep(200)

        // If the pane is already dead, capture its output, print it, and exit.
        if (!isTmuxPaneAlive()) {
            val output = capturePane()
            if (output.isNotBlank()) {
                println(output)
            }
            exec("tmux", "kill-session", "-t", SESSION_NAME)
            return 0
        }

        // Background thread: watch input.txt → tmux send-keys
        startDaemon("input-watcher") {
            while (isTmuxSessionAlive()) {
                Thread.sleep(200)
                if (INPUT_FILE.exists() && INPUT_FILE.length() > 0) {
                    val text = INPUT_FILE.readText()
                    if (text.isNotEmpty()) {
                        INPUT_FILE.writeText("")
                        for (line in text.lines()) {
                            if (line.isNotEmpty()) {
                                exec("tmux", "send-keys", "-t", SESSION_NAME, "-l", line)
                                exec("tmux", "send-keys", "-t", SESSION_NAME, "Enter")
                            }
                        }
                    }
                }
            }
        }

        // Detect plugin for the command being run
        val plugin = SessionPluginLoader.findPlugin(command)
        if (plugin != null) {
            log.info("Plugin activated: {}", plugin.name)
        }

        // Background thread: tmux capture-pane → output.txt (with plugin filtering)
        startDaemon("output-capture") {
            while (isTmuxSessionAlive()) {
                Thread.sleep(500)
                try {
                    val pb = ProcessBuilder("tmux", "capture-pane", "-t", SESSION_NAME, "-p")
                    pb.redirectErrorStream(true)
                    val p = pb.start()
                    val output = p.inputStream.bufferedReader().readText()
                    p.waitFor()

                    val lines = if (plugin != null) {
                        plugin.filterOutput(output)
                    } else {
                        output.lines()
                            .map { it.trimEnd() }
                            .dropLastWhile { it.isBlank() }
                            .takeLast(10)
                    }

                    if (lines.isNotEmpty()) {
                        OUTPUT_FILE.writeText(lines.joinToString("\n") + "\n")
                    }
                } catch (_: Exception) {}
            }
        }

        // Background thread: Telegram bot (if token is configured and not disabled by flag)
        val token = loadTelegramToken()
        if (token != null && !noTelegram) {
            startDaemon("telegram-bot") {
                val bot = TelegramBot(token, tmuxSessionName = SESSION_NAME, plugin = plugin)
                bot.run()
            }
        } else if (noTelegram) {
            log.info("Telegram integration disabled by command-line flag")
        }

        // When the pane's command exits, auto-kill the session so attach returns cleanly.
        exec("tmux", "set-hook", "-t", SESSION_NAME, "pane-died", "kill-session")

        // Attach to the tmux session — this is the blocking call.
        // inheritIO() gives the user full terminal control.
        val attachPb = ProcessBuilder("tmux", "attach", "-t", SESSION_NAME)
        attachPb.inheritIO()
        val attachProcess = attachPb.start()
        val exitCode = attachProcess.waitFor()

        // Clean up
        exec("tmux", "kill-session", "-t", SESSION_NAME)
        return exitCode
    }

    private fun isTmuxSessionAlive(): Boolean {
        return exec("tmux", "has-session", "-t", SESSION_NAME) == 0
    }

    /** Returns true if the pane's process is still running (not "dead"). */
    private fun isTmuxPaneAlive(): Boolean {
        return try {
            val pb = ProcessBuilder("tmux", "display-message", "-t", SESSION_NAME, "-p", "#{pane_dead}")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            output != "1"
        } catch (_: Exception) {
            false
        }
    }

    /** Captures the full pane content including scrollback, filtering out tmux's "Pane is dead" line. */
    private fun capturePane(): String {
        return try {
            val pb = ProcessBuilder("tmux", "capture-pane", "-t", SESSION_NAME, "-p", "-S", "-")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText()
            p.waitFor()
            output.lines()
                .filter { !it.startsWith("Pane is dead") }
                .joinToString("\n")
                .trimEnd()
        } catch (_: Exception) {
            ""
        }
    }

    private fun exec(vararg cmd: String): Int {
        return try {
            val p = ProcessBuilder(*cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            p.waitFor()
        } catch (_: Exception) {
            -1
        }
    }

    private fun startDaemon(name: String, block: () -> Unit) {
        val thread = Thread({
            try { block() } catch (_: Exception) {}
        }, name)
        thread.isDaemon = true
        thread.start()
    }

    private fun loadTelegramToken(): String? {
        val envToken = System.getenv("TELEGRAM_BOT_TOKEN")
        if (!envToken.isNullOrBlank()) return envToken.trim()

        try {
            if (TOKEN_FILE.exists()) {
                val token = TOKEN_FILE.readText().trim()
                if (token.isNotBlank()) return token
            }
        } catch (_: Exception) {}

        return null
    }
}
