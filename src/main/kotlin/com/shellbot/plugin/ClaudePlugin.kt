package com.shellbot.plugin

import java.util.concurrent.atomic.AtomicReference

/**
 * Plugin for Claude Code sessions.
 *
 * Tracks Claude's state (WORKING / IDLE / PERMISSION_REQUIRED) by inspecting
 * the ANSI-stripped tmux pane output and emits Telegram notifications on
 * state transitions. Also strips UI chrome (box-drawing, status bar, ANSI
 * codes) from /o output.
 */
class ClaudePlugin : SessionPlugin {

    override val name = "ClaudePlugin"

    private enum class ClaudeState { UNKNOWN, WORKING, IDLE, PERMISSION_REQUIRED }

    private val state = AtomicReference(ClaudeState.UNKNOWN)
    private var idleSinceTime: Long = 0
    private var lastNotificationTime: Long = 0

    override fun matches(command: String): Boolean {
        val cmd = command.trim().lowercase()
        return cmd.startsWith("claude")
    }

    override fun onUserInput() {
        state.set(ClaudeState.WORKING)
    }

    override fun processImage(filePath: String): String? {
        // For testing: just send a simple command that Claude will see
        // This should appear in Claude's terminal
        return "Process this image $filePath"
    }

    override fun processAudio(filePath: String): String? {
        // For testing: just send a simple command that Claude will see
        // This should appear in Claude's terminal
        return "Process this audio message $filePath"
    }

    override fun checkForNotifications(currentOutput: String, idleSeconds: Long): List<String> {
        val stripped = stripAnsi(currentOutput)
        val lines = stripped.lines()
            .map { it.trimEnd() }
            .dropLastWhile { it.isBlank() }

        if (lines.isEmpty()) return emptyList()

        val newState = detectState(lines)
        val previous = state.getAndSet(newState)

        // Track when we entered idle state
        if (newState == ClaudeState.IDLE && previous != ClaudeState.IDLE) {
            idleSinceTime = System.currentTimeMillis()
        }

        // If idleSeconds provided, check time-based condition
        if (idleSeconds > 0) {
            // Permission notifications are always immediate
            if (newState == ClaudeState.PERMISSION_REQUIRED) {
                return listOf(SessionPlugin.NOTIFICATION_PERMISSION)
            }

            if (newState == ClaudeState.IDLE) {
                val now = System.currentTimeMillis()
                val timeInIdleState = (now - idleSinceTime) / 1000

                // Only notify if in idle state long enough AND haven't recently notified
                if (timeInIdleState >= idleSeconds && (now - lastNotificationTime) > (idleSeconds * 1000)) {
                    lastNotificationTime = now
                    return listOf(SessionPlugin.NOTIFICATION_IDLE)
                }
            }
            // When idleSeconds > 0, only time-based notifications for IDLE, immediate for PERMISSION
            return emptyList()
        }

        // Original state transition logic (for backward compatibility when idleSeconds = 0)
        if (newState == previous) return emptyList()

        return when (newState) {
            ClaudeState.IDLE -> {
                lastNotificationTime = System.currentTimeMillis()
                listOf(SessionPlugin.NOTIFICATION_IDLE)
            }
            ClaudeState.PERMISSION_REQUIRED -> listOf(SessionPlugin.NOTIFICATION_PERMISSION)
            else -> emptyList()
        }
    }

    override fun filterOutput(rawOutput: String): List<String> {
        val stripped = stripAnsi(rawOutput)
        return stripped.lines()
            .map { it.trimEnd() }
            .dropLastWhile { it.isBlank() }
            .dropLast(4)
            .takeLast(10)
   }

    // ---- State detection ----

    private fun detectState(lines: List<String>): ClaudeState {
        val lastMeaningful = lines.lastOrNull { it.isNotBlank() } ?: return ClaudeState.WORKING
        // Idle check first: if the prompt is showing, Claude is waiting for input
        if (isInputPrompt(lastMeaningful)) return ClaudeState.IDLE
        // Permission check: only look at the bottom few lines for active permission UI
        if (hasPermissionRequest(lines)) return ClaudeState.PERMISSION_REQUIRED
        return ClaudeState.WORKING
    }

    private fun hasPermissionRequest(lines: List<String>): Boolean {
        // Only check the last 5 lines — the active permission prompt sits at the bottom.
        // Avoid generic tool-call names (Bash(, Edit(, …) which appear in normal output.
        val tail = lines.takeLast(5)
        return tail.any { line ->
            line.contains("Allow") ||
            line.contains("Do you want to proceed") ||
            line.contains("deny", ignoreCase = true) && line.contains("allow", ignoreCase = true)
        }
    }

    private fun isInputPrompt(line: String): Boolean {
        // Match lines that consist ONLY of a prompt character (with optional whitespace)
        // This catches "❯" or ">" alone
        if (line.matches(Regex("^\\s*[>❯⏵❱▶►⟩»›\\$]\\s*$"))) {
            return true
        }

        // Also match lines that START with a prompt character followed by text
        // This catches "❯ upload the sketch and test the stream"
        return line.matches(Regex("^\\s*[>❯⏵❱▶►⟩»›\\$]\\s+.*"))
    }

    // ---- Utilities ----
    private fun stripAnsi(text: String): String {
        return text
            .replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")       // CSI sequences
            .replace(Regex("\u001B\\][^\u0007]*\u0007"), "")       // OSC sequences
            .replace(Regex("\u001B\\([A-Z]"), "")                  // Charset selectors
            .replace(Regex("\u001B[=>]"), "")                      // Keypad mode
            .replace(Regex("\u001B\\[[0-9;]*[Hf]"), "")            // Cursor positioning
            .replace(Regex("[\u000E\u000F]"), "")                  // SO/SI (shift out/in)
            .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]"), "") // Other C0 control chars (keep \t \n \r)
            .replace(Regex("[\u0080-\u009F]"), "")                 // C1 control chars
    }
}
