package com.shellbot.plugin

/**
 * Plugin interface for session-aware behavior.
 *
 * Implementations are discovered via Java SPI (ServiceLoader).
 * When a plugin matches the running command, TelegramBot delegates
 * notification and output-filtering logic to it.
 */
interface SessionPlugin {
    val name: String
    fun matches(command: String): Boolean
    fun checkForNotifications(currentOutput: String, idleSeconds: Long = 0): List<String>
    fun filterOutput(rawOutput: String): List<String>
    fun onUserInput() {}
    fun processImage(filePath: String): String? = null
    fun processAudio(filePath: String): String? = null

    /**
     * Returns a short human-readable description of the active model, if the
     * running session exposes one (e.g. "Build · DeepSeek V4 IB").
     * Return null when the plugin cannot determine the model.
     */
    fun getModelInfo(currentOutput: String): String? = null

    /**
     * Returns a short human-readable description of the context / token usage,
     * if the running session exposes one (e.g. "76,678 tokens · 0% used").
     * Return null when the plugin cannot determine the context usage.
     */
    fun getContextInfo(currentOutput: String): String? = null

    /**
     * Returns the running session's title, if the running session exposes one
     * (e.g. opencode's session title shown above the context sidebar).
     * Return null when the plugin cannot determine the session title.
     */
    fun getSessionTitle(currentOutput: String): String? = null

    /**
     * Returns the text of a pending permission request (the question/command the
     * session is asking about), or null if there is no active permission request.
     */
    fun getPermissionText(currentOutput: String): String? = null

    /**
     * Returns the answer options the session currently offers for a pending
     * permission request (e.g. ["Allow once", "Reject"]). Pressing one forwards
     * the option text back to the session. Empty when no request is pending.
     */
    fun getPermissionOptions(currentOutput: String): List<String> = emptyList()

    companion object {
        const val NOTIFICATION_IDLE = "Claude is idle — waiting for input."
        const val NOTIFICATION_PERMISSION = "Claude needs permission — check the terminal."
    }
}
