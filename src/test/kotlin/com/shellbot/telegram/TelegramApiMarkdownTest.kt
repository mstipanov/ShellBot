package com.shellbot.telegram

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TelegramApiMarkdownTest {

    @Test
    fun escapesReservedCharacters() {
        val api = TelegramApi("TEST_TOKEN")

        // Periods, exclamation, underscores, brackets, parens, plus, equals, etc.
        val escaped = api.escapeMarkdownV2("All tests pass. 15 run, 0 failures! a_b[c].md (*x*) 1+2=3")
        assertEquals("All tests pass\\. 15 run, 0 failures\\! a\\_b\\[c\\]\\.md \\(\\*x\\*\\) 1\\+2\\=3", escaped)
    }

    @Test
    fun leavesNonReservedCharactersAlone() {
        val api = TelegramApi("TEST_TOKEN")

        // $, letters, digits, spaces are not reserved and should not be escaped.
        val escaped = api.escapeMarkdownV2("plain text with $ and 123")
        assertEquals("plain text with \$ and 123", escaped)
        assertFalse(escaped.contains("\\\$"))
    }

    @Test
    fun wrapsCodeBlockWithoutEscaping() {
        val api = TelegramApi("TEST_TOKEN")

        val content = "$ mvn test\n[WARNING] Tests run: 5, Failures: 0, Skipped: 1"
        val wrapped = api.prepareMarkdown(content, inCodeBlock = true)
        assertEquals("```\n\$ mvn test\n[WARNING] Tests run: 5, Failures: 0, Skipped: 1\n```", wrapped)
    }

    @Test
    fun escapesWhenNotInCodeBlock() {
        val api = TelegramApi("TEST_TOKEN")
        val plain = api.prepareMarkdown("Process killed.", inCodeBlock = false)
        assertEquals("Process killed\\.", plain)
    }

    @Test
    fun escapesBacktickAndPipes() {
        val api = TelegramApi("TEST_TOKEN")
        val escaped = api.escapeMarkdownV2("cmd `code` | pipe > out")
        assertEquals("cmd \\`code\\` \\| pipe \\> out", escaped)
    }
}
