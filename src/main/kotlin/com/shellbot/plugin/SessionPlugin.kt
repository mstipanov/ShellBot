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

    companion object {
        const val NOTIFICATION_IDLE = "Claude is idle — waiting for input."
        const val NOTIFICATION_PERMISSION = "Claude needs permission — check the terminal."
    }
}
