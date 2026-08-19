package com.shellbot.plugin

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class OpencodePluginTest {

    @Test
    fun testMatchesOpencodeCommand() {
        val plugin = OpencodePlugin()
        assertEquals(true, plugin.matches("opencode"))
        assertEquals(true, plugin.matches("opencode --continue"))
        assertEquals(false, plugin.matches("claude"))
        assertEquals(false, plugin.matches("npx claude code"))
    }

    @Test
    fun testCheckForNotificationsImmediateWithZeroIdle() {
        val plugin = OpencodePlugin()
        val outputWithPrompt = "some output\n>"
        val notifications = plugin.checkForNotifications(outputWithPrompt, 0)
        assertEquals(1, notifications.size)
        assertEquals(SessionPlugin.NOTIFICATION_IDLE, notifications.first())
    }

    @Test
    fun testPermissionNotificationImmediate() {
        val plugin = OpencodePlugin()
        val outputWithPermission = "some output\nAllow opencode to run Bash?"
        val notifications = plugin.checkForNotifications(outputWithPermission, 30)
        assertEquals(1, notifications.size)
        assertEquals(SessionPlugin.NOTIFICATION_PERMISSION, notifications.first())
    }

    @Test
    fun testStateDetection() {
        val plugin = OpencodePlugin()

        val idleOutput = "some output\n>"
        assertEquals(1, plugin.checkForNotifications(idleOutput, 0).size)

        val workingOutput = "some output\nstill working"
        assertEquals(0, plugin.checkForNotifications(workingOutput, 0).size)

        val permissionOutput = "Allow opencode to run Bash?"
        val notifications = plugin.checkForNotifications(permissionOutput, 0)
        assertEquals(1, notifications.size)
        assertEquals(SessionPlugin.NOTIFICATION_PERMISSION, notifications.first())
    }

    @Test
    fun testFilterOutputIsolatesOutputSection() {
        val plugin = OpencodePlugin()

        // Simulate a realistic opencode pane (209 cols): main output left,
        // right-hand context sidebar at ~col 169, and bottom chrome.
        val raw = "" +
            "     Main agent output line one" + " ".repeat(155) + "Context\n" +
            "     Main agent output line two" + " ".repeat(153) + "76,678 tokens\n" +
            " ".repeat(167) + "0% used\n" +
            "     \$ tmux capture-pane -t shellbot -p" + " ".repeat(160) + "MCP\n" +
            " ".repeat(160) + "• jetbrains Connected\n" +
            "     ▣  Build · DeepSeek V4 IB\n" +
            "     ┃\n" +
            "     ╹▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀\n" +
            "        esc interrupt                      ctrl+p commands              OpenCode 1.18.18\n"

        val lines = plugin.filterOutput(raw)
        val joined = lines.joinToString("\n")

        // Sidebar and bottom chrome must be stripped away.
        assertEquals(false, joined.contains("Context"))
        assertEquals(false, joined.contains("tokens"))
        assertEquals(false, joined.contains("Build · DeepSeek"))
        assertEquals(false, joined.contains("esc interrupt"))
        assertEquals(false, joined.contains("ctrl+p commands"))
        assertEquals(false, joined.contains("OpenCode 1.18.18"))

        // The main output section must be preserved.
        assertEquals(true, joined.contains("Main agent output line one"))
        assertEquals(true, joined.contains("Main agent output line two"))
        assertEquals(true, joined.contains("tmux capture-pane"))
    }

    @Test
    fun testShortResponseKeepsNewestAndStripsChrome() {
        val plugin = OpencodePlugin()
        val sidebar = " ".repeat(160)

        // Short reply: interleaved model lines + input-box padding + sidebar.
        // Note: "┃"-prefixed rows carrying content (tool output) are preserved;
        // only bare input-box padding ("┃" alone) and chrome are removed.
        val raw = "" +
            "Some older assistant text" + sidebar + "Context\n" +
            "     ▣  Build · DeepSeek V4 IB · 10.1s" + sidebar + "108,022 tokens\n" +
            "     ┃\n" +
            "     ┃  \$ mvn test\n" +
            "     ┃\n" +
            "     Newest answer line\n" +
            "     ▣  Build · DeepSeek V4 IB · 3.9s\n" +
            "     ┃\n" +
            "     ╹▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀\n"

        val lines = plugin.filterOutput(raw)
        val joined = lines.joinToString("\n")

        // The newest answer and older content must both be present.
        assertEquals(true, joined.contains("Newest answer line"))
        assertEquals(true, joined.contains("Some older assistant text"))
        // Tool-output content carried in a "┃"-prefixed row is preserved, without the glyph.
        assertEquals(true, joined.contains("mvn test"))
        // Chrome must be gone: model lines, divider, bare padding, sidebar, the ┃ border glyph.
        assertEquals(false, joined.contains("▣"))
        assertEquals(false, joined.contains("╹"))
        assertEquals(false, joined.contains("┃"))
        assertEquals(false, joined.contains("Context"))
        assertEquals(false, joined.contains("tokens"))
    }
}
