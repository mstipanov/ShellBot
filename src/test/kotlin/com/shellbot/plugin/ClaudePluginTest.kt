package com.shellbot.plugin

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClaudePluginTest {

    @Test
    fun testCheckForNotificationsImmediateWithZeroIdle() {
        val plugin = ClaudePlugin()

        // Test with idleSeconds = 0 (backward compatibility)
        // Should return notification immediately on state transition to IDLE
        val outputWithPrompt = "some output\n>"
        val notifications = plugin.checkForNotifications(outputWithPrompt, 0)

        assertEquals(1, notifications.size)
        assertEquals(SessionPlugin.NOTIFICATION_IDLE, notifications.first())
    }

    @Test
    fun testCheckForNotificationsTimeBased() {
        val plugin = ClaudePlugin()

        // First call: transition to IDLE state
        val outputWithPrompt = "some output\n>"
        val notifications1 = plugin.checkForNotifications(outputWithPrompt, 30)

        // Should not notify immediately with idleSeconds=30
        assertEquals(0, notifications1.size)

        // Second call: still in IDLE state but not enough time has passed
        // Mock the idleSinceTime by calling checkForNotifications again
        // (in real scenario, time would pass)
        val notifications2 = plugin.checkForNotifications(outputWithPrompt, 30)

        // Should still not notify (time hasn't passed)
        assertEquals(0, notifications2.size)
    }

    @Test
    fun testPermissionNotificationImmediate() {
        val plugin = ClaudePlugin()

        // Permission notifications should still be immediate regardless of idleSeconds
        val outputWithPermission = "some output\nAllow Claude to run Bash?"
        val notifications = plugin.checkForNotifications(outputWithPermission, 30)

        assertEquals(1, notifications.size)
        assertEquals(SessionPlugin.NOTIFICATION_PERMISSION, notifications.first())
    }

    @Test
    fun testStateDetection() {
        val plugin = ClaudePlugin()

        // Test idle state detection
        val idleOutput = "some output\n>"
        val notifications1 = plugin.checkForNotifications(idleOutput, 0)
        assertEquals(1, notifications1.size)

        // Test working state
        val workingOutput = "some output\nstill working"
        val notifications2 = plugin.checkForNotifications(workingOutput, 0)
        assertEquals(0, notifications2.size)

        // Test permission state
        val permissionOutput = "Allow Claude to run Bash?"
        val notifications3 = plugin.checkForNotifications(permissionOutput, 0)
        assertEquals(1, notifications3.size)
        assertEquals(SessionPlugin.NOTIFICATION_PERMISSION, notifications3.first())
    }

    @Test
    @org.junit.jupiter.api.Disabled("TODO: Fix prompt detection regex")
    fun testMultiplePromptCharacters() {
        val plugin = ClaudePlugin()

        val prompts = listOf(">", "$", "❯", "⏵", "❱", "▶", "►", "⟩", "»", "›")

        for (prompt in prompts) {
            val output = "output\n$prompt"
            val notifications = plugin.checkForNotifications(output, 0)
            assertEquals(1, notifications.size, "Failed for prompt: '$prompt'")
            assertEquals(SessionPlugin.NOTIFICATION_IDLE, notifications.first())
        }
    }
}