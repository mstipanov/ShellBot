package com.shellbot.telegram

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Telegram bot with long-polling that manages a single process session.
 *
 * Commands:
 *   /start          — claim this bot (first user only)
 *   /run <command>  — start a new process (kills existing one)
 *   /output or /o   — show last 10 lines of output
 *   /kill           — kill the running process
 *   (any other text) — forwarded as stdin to the running process
 *
 * Only the first user to send /start is authorized to use the bot.
 * The owner chat ID is persisted to ~/.shellbot/owner.txt.
 */
class TelegramBot(private val token: String) {
    private val api = TelegramApi(token)
    @Volatile var session: ProcessSession? = null
    private var offset = 0L
    private var ownerChatId: Long? = null

    companion object {
        private val CONFIG_DIR: Path = Paths.get(System.getProperty("user.home"), ".shellbot")
        private val OWNER_FILE: Path = CONFIG_DIR.resolve("owner.txt")
    }

    private fun loadOwner() {
        try {
            val file = OWNER_FILE.toFile()
            if (file.exists()) {
                val id = file.readText().trim().toLongOrNull()
                if (id != null) {
                    ownerChatId = id
                    println("Loaded owner chat ID: $id")
                }
            }
        } catch (_: Exception) {
            // Ignore — treat as no saved owner
        }
    }

    private fun saveOwner(chatId: Long) {
        try {
            CONFIG_DIR.toFile().mkdirs()
            OWNER_FILE.toFile().writeText(chatId.toString())
        } catch (e: Exception) {
            System.err.println("Warning: could not save owner file: ${e.message}")
        }
    }

    /**
     * Blocking long-polling loop. Call from the main thread or a dedicated thread.
     */
    fun run() {
        loadOwner()
        println("Telegram bot started. Polling for updates...")
        if (ownerChatId == null) {
            println("Waiting for first user to claim the bot with /start...")
        }
        while (true) {
            try {
                val updates = api.getUpdates(offset)
                for (update in updates) {
                    offset = update.updateId + 1
                    val text = update.text ?: continue
                    handleMessage(update.chatId, text)
                }
            } catch (e: Exception) {
                System.err.println("Polling error: ${e.message}")
                Thread.sleep(3000)
            }
        }
    }

    private fun handleMessage(chatId: Long, text: String) {
        if (text == "/start") {
            handleStart(chatId)
            return
        }

        // All other commands require authorization
        if (ownerChatId == null) {
            api.sendMessage(chatId, "Bot not claimed yet. Send /start first.")
            return
        }
        if (chatId != ownerChatId) {
            api.sendMessage(chatId, "Unauthorized. This bot is locked to another user.")
            return
        }

        when {
            text.startsWith("/run ") -> handleRun(chatId, text.removePrefix("/run ").trim())
            text == "/output" || text == "/o" -> handleOutput(chatId)
            text == "/kill" -> handleKill(chatId)
            text.startsWith("/") -> api.sendMessage(chatId, "Unknown command. Use /run, /output (/o), or /kill.")
            else -> handleInput(chatId, text)
        }
    }

    private fun handleStart(chatId: Long) {
        if (ownerChatId == null) {
            ownerChatId = chatId
            saveOwner(chatId)
            println("Bot claimed by chat ID: $chatId")
            api.sendMessage(chatId, "Bot claimed. You are the owner.\n\nCommands:\n/run <command> — start a process\n/output or /o — last 10 lines of output\n/kill — kill the process\nAny other text — send as input to the process")
        } else if (chatId == ownerChatId) {
            api.sendMessage(chatId, "You are already the owner.")
        } else {
            api.sendMessage(chatId, "Unauthorized. This bot is locked to another user.")
        }
    }

    private fun handleRun(chatId: Long, command: String) {
        if (command.isBlank()) {
            api.sendMessage(chatId, "Usage: /run <command>")
            return
        }

        session?.let {
            if (it.isAlive()) it.kill()
        }

        try {
            session = ProcessSession(command)
            api.sendMessage(chatId, "Started: $command")
        } catch (e: Exception) {
            api.sendMessage(chatId, "Failed to start process: ${e.message}")
        }
    }

    private fun handleOutput(chatId: Long) {
        val s = session
        if (s == null) {
            api.sendMessage(chatId, "No process running. Use /run <command> first.")
            return
        }

        val lines = s.getLastLines(10)
        if (lines.isEmpty()) {
            val status = if (s.isAlive()) "running, no output yet" else "exited, no output"
            api.sendMessage(chatId, "($status)")
        } else {
            val prefix = if (!s.isAlive()) "[exited]\n" else ""
            api.sendMessage(chatId, prefix + lines.joinToString("\n"))
        }
    }

    private fun handleKill(chatId: Long) {
        val s = session
        if (s == null || !s.isAlive()) {
            api.sendMessage(chatId, "No running process to kill.")
            return
        }
        s.kill()
        api.sendMessage(chatId, "Process killed.")
    }

    private fun handleInput(chatId: Long, text: String) {
        val s = session
        if (s == null || !s.isAlive()) {
            api.sendMessage(chatId, "No running process. Use /run <command> first.")
            return
        }
        s.sendInput(text)
    }
}
