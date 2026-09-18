package com.shellbot

import com.shellbot.plugin.SessionPlugin
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
 *   - Telegram bot            — configured via ~/.shellbot/settings.yaml
 *
 * tmux owns the PTY, so the child process (claude, etc.) gets a real terminal.
 */
class TmuxSession(
    private val command: String,
    private val sessionId: String = "shellbot",
    private val settings: Settings = Settings()
) {
    private val log = LoggerFactory.getLogger(TmuxSession::class.java)

    companion object {
        private val CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".shellbot").toFile()

        /**
         * Creates a detached tmux session named [sessionName] running [command],
         * applying ShellBot's standard tmux options (remain-on-exit, mouse,
         * scrollback, top status bar). Returns true on success.
         *
         * Used both for the initial session (see [createAndRun]) and to restart a
         * session from Telegram (/sb_restart). The ";" argument is tmux's command
         * separator — all commands are processed in a single tmux event loop
         * iteration, before any "child exited" event.
         */
        fun startDetached(sessionName: String, command: String): Boolean {
            return try {
                val p = ProcessBuilder(
                    "tmux", "new-session", "-d", "-s", sessionName, command,
                    ";", "set-option", "-t", sessionName, "remain-on-exit", "on",
                    ";", "set-option", "-t", sessionName, "mouse", "on",
                    ";", "set-option", "-t", sessionName, "history-limit", "5000",
                    ";", "set-option", "-t", sessionName, "status-position", "top",
                    ";", "set-option", "-t", sessionName, "status-interval", "1",
                    ";", "set-option", "-t", sessionName, "status-left-length", "30"
                )
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                p.waitFor() == 0
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Restarts the command running in [sessionName]'s pane in place, keeping
         * the session (and therefore the ShellBot process attached to it) alive.
         *
         * `respawn-pane -k` kills the current command and starts [command] again
         * in the same pane. The `pane-died` hook that would otherwise kill the
         * session on exit must be removed first, otherwise the kill half of the
         * respawn fires the hook while the pane is momentarily dead and the whole
         * session is torn down. Returns true on success.
         */
        fun respawnPane(sessionName: String, command: String): Boolean {
            return try {
                // Drop any exit hook so respawning does not kill the session.
                ProcessBuilder("tmux", "set-hook", "-u", "-t", sessionName, "pane-died")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor()

                val p = ProcessBuilder("tmux", "respawn-pane", "-k", "-t", sessionName, command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                p.waitFor() == 0
            } catch (_: Exception) {
                false
            }
        }
    }

    private val SESSION_NAME = sessionId
    private val SESSION_TARGET = "=$sessionId"  // '=' prefix forces exact tmux match
    private val INPUT_FILE = File(CONFIG_DIR, "input-$sessionId.txt")
    private val OUTPUT_FILE = File(CONFIG_DIR, "output-$sessionId.txt")

    fun run(): Int {
        // Validate session name (tmux session names must match regex: [a-zA-Z0-9_-]+)
        if (!SESSION_NAME.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
            log.error(
                "Invalid session name: '{}'. Session names can only contain letters, numbers, underscores and hyphens.",
                SESSION_NAME
            )
            return 1
        }

        CONFIG_DIR.mkdirs()
        INPUT_FILE.writeText("")
        OUTPUT_FILE.writeText("")

        // If a session with this name already exists, attach to and monitor it
        // instead of creating a new one. The user's process with its own tmux is
        // left untouched (not killed, no command started).
        if (isTmuxSessionAlive()) {
            log.info("Attaching to existing tmux session '{}'", SESSION_NAME)
            return attachExisting()
        }

        return createAndRun()
    }

    /**
     * Attaches to an existing tmux session and monitors it via the side-channels
     * and Telegram bot. Does not create or kill the session, and does not start
     * a command. Returns the attach exit code.
     */
    private fun attachExisting(): Int {
        startMonitors(SessionPluginLoader.findPlugin(command)?.also {
            log.info("Plugin activated: {}", it.name)
        })

        val exitCode = attachToSession()
        // Do not kill the session — it belongs to an already-running process.
        return exitCode
    }

    /**
     * Creates a new detached tmux session, runs [command] inside it, and monitors
     * it. When the command exits, the session is killed. Returns the attach exit code.
     */
    private fun createAndRun(): Int {
        // Kill any leftover session from a previous run
        exec("tmux", "kill-session", "-t", SESSION_TARGET)

        // Start detached tmux session running the command with the standard options.
        if (!startDetached(SESSION_NAME, command)) {
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
            exec("tmux", "kill-session", "-t", SESSION_TARGET)
            return 0
        }

        startMonitors(SessionPluginLoader.findPlugin(command)?.also {
            log.info("Plugin activated: {}", it.name)
        })

        // No pane-died hook is installed: the session (and this ShellBot process)
        // stays alive when the command exits, so the pane can be restarted later
        // with /sb_restart via `tmux respawn-pane`. Use remain-on-exit to keep the
        // pane around in the "dead" state until then.

        val exitCode = attachToSession()

        // Detach (not exit) — the session keeps running for the side-channels and
        // Telegram bot. Clean up only when the session has actually gone away.
        if (!isTmuxSessionAlive()) {
            exec("tmux", "kill-session", "-t", SESSION_TARGET)
        }
        return exitCode
    }

    /** Attach to the tmux session — blocking call with full terminal control. */
    private fun attachToSession(): Int {
        val attachPb = ProcessBuilder("tmux", "attach", "-t", SESSION_TARGET)
        attachPb.inheritIO()
        val attachProcess = attachPb.start()
        return attachProcess.waitFor()
    }

    /** Starts the input-watcher, output-capture and Telegram bot daemons. */
    private fun startMonitors(plugin: SessionPlugin?) {
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
                                exec("tmux", "send-keys", "-t", SESSION_TARGET, "-l", line)
                                exec("tmux", "send-keys", "-t", SESSION_TARGET, "Enter")
                            }
                        }
                    }
                }
            }
        }

        // Background thread: tmux capture-pane → output.txt (with plugin filtering)
        startDaemon("output-capture") {
            while (isTmuxSessionAlive()) {
                Thread.sleep(500)
                try {
                    val pb = ProcessBuilder("tmux", "capture-pane", "-t", SESSION_TARGET, "-p")
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
                } catch (_: Exception) {
                }
            }
        }

        // Background thread: Telegram bot (configured via settings.yaml)
        val telegramSettings = settings.getSessionTelegram(sessionId)
        if (telegramSettings != null) {
            log.info("Starting Telegram bot for session '{}'", sessionId)
            startDaemon("telegram-bot") {
                val bot = TelegramBot(
                    telegramSettings.token,
                    tmuxSessionName = SESSION_NAME,
                    plugin = plugin,
                    idleNotifySeconds = telegramSettings.idleNotifySeconds,
                    sessionCommand = command
                )
                bot.run()
            }
        } else {
            log.info("Telegram integration not configured for session '{}'", sessionId)
        }
    }

    private fun isTmuxSessionAlive(): Boolean {
        return exec("tmux", "has-session", "-t", SESSION_TARGET) == 0
    }

    /** Returns true if the pane's process is still running (not "dead"). */
    private fun isTmuxPaneAlive(): Boolean {
        return try {
            val pb = ProcessBuilder("tmux", "display-message", "-t", SESSION_TARGET, "-p", "#{pane_dead}")
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
            val pb = ProcessBuilder("tmux", "capture-pane", "-t", SESSION_TARGET, "-p", "-S", "-")
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
            try {
                block()
            } catch (_: Exception) {
            }
        }, name)
        thread.isDaemon = true
        thread.start()
    }

}
