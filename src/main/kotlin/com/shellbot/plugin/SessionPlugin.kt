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

    companion object {
        const val NOTIFICATION_IDLE = "Claude is idle — waiting for input."
        const val NOTIFICATION_PERMISSION = "Claude needs permission — check the terminal."
    }
}
