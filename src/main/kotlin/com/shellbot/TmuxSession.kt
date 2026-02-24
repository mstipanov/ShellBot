package com.shellbot

import java.io.File
import java.nio.file.Paths

/**
 * Runs a command inside a tmux session with full terminal access.
 *
 * Background threads provide file-based side-channel I/O:
 *   ~/.shellbot/output.txt — last 10 lines of visible pane output (updated every 500ms)
 *   ~/.shellbot/input.txt  — write text here to inject it as keyboard input
 *
 * tmux owns the PTY, so the child process (claude, etc.) gets a real terminal.
 * We never touch the process's I/O directly.
 */
class TmuxSession(private val command: String) {

    companion object {
        private const val SESSION_NAME = "shellbot"
        private val CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".shellbot").toFile()
        private val INPUT_FILE = File(CONFIG_DIR, "input.txt")
        private val OUTPUT_FILE = File(CONFIG_DIR, "output.txt")
    }

    fun run(): Int {
        CONFIG_DIR.mkdirs()
        INPUT_FILE.writeText("")
        OUTPUT_FILE.writeText("")

        // Kill any leftover session from a previous run
        exec("tmux", "kill-session", "-t", SESSION_NAME)

        // Start detached tmux session running the command
        val startResult = exec("tmux", "new-session", "-d", "-s", SESSION_NAME, command)
        if (startResult != 0) {
            System.err.println("Failed to create tmux session (is tmux installed?)")
            return 1
        }

        // Background thread: watch input.txt → tmux send-keys
        val inputWatcher = Thread({
            try {
                while (isTmuxSessionAlive()) {
                    Thread.sleep(200)
                    if (INPUT_FILE.exists() && INPUT_FILE.length() > 0) {
                        val text = INPUT_FILE.readText()
                        if (text.isNotEmpty()) {
                            INPUT_FILE.writeText("")
                            // Send each line separately
                            for (line in text.lines()) {
                                if (line.isNotEmpty()) {
                                    exec("tmux", "send-keys", "-t", SESSION_NAME, "-l", line)
                                    exec("tmux", "send-keys", "-t", SESSION_NAME, "Enter")
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }, "input-watcher")
        inputWatcher.isDaemon = true
        inputWatcher.start()

        // Background thread: tmux capture-pane → output.txt
        val outputCapture = Thread({
            try {
                while (isTmuxSessionAlive()) {
                    Thread.sleep(500)
                    try {
                        val pb = ProcessBuilder("tmux", "capture-pane", "-t", SESSION_NAME, "-p")
                        pb.redirectErrorStream(true)
                        val p = pb.start()
                        val output = p.inputStream.bufferedReader().readText()
                        p.waitFor()

                        // Take last 10 non-blank lines
                        val lines = output.lines()
                            .map { it.trimEnd() }
                            .dropLastWhile { it.isBlank() }
                            .takeLast(10)

                        if (lines.isNotEmpty()) {
                            OUTPUT_FILE.writeText(lines.joinToString("\n") + "\n")
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }, "output-capture")
        outputCapture.isDaemon = true
        outputCapture.start()

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
}
