package com.shellbot.telegram

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Telegram bot with long-polling.
 *
 * Two modes:
 *   1. Tmux mode (tmuxSessionName != null): uses tmux send-keys / capture-pane
 *      to interact with an existing tmux session. Used alongside local terminal.
 *   2. Standalone mode (tmuxSessionName == null): manages its own ProcessSession
 *      via /run, /kill commands. Used with --telegram flag.
 *
 * Commands:
 *   /start          — claim this bot (first user only)
 *   /run <command>  — start a new process (standalone mode only)
 *   /output or /o   — show last 10 lines of output
 *   /kill           — kill the running process / send Ctrl-C
 *   (any other text) — forwarded as input
 *
 * Only the first user to send /start is authorized.
 * Owner chat ID is persisted to ~/.shellbot/owner.txt.
 */
class TelegramBot(
    private val token: String,
    private val tmuxSessionName: String? = null
) {
    private val api = TelegramApi(token)
    private var session: ProcessSession? = null
    private var offset = 0L
    private var ownerChatId: Long? = null

    private val isTmuxMode get() = tmuxSessionName != null

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
        } catch (_: Exception) {}
    }

    private fun saveOwner(chatId: Long) {
        try {
            CONFIG_DIR.toFile().mkdirs()
            OWNER_FILE.toFile().writeText(chatId.toString())
        } catch (e: Exception) {
            System.err.println("Warning: could not save owner file: ${e.message}")
        }
    }

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
            text.startsWith("/") -> {
                val cmds = if (isTmuxMode) "/output (/o), /kill" else "/run, /output (/o), /kill"
                api.sendMessage(chatId, "Unknown command. Use $cmds.")
            }
            else -> handleInput(chatId, text)
        }
    }

    private fun handleStart(chatId: Long) {
        if (ownerChatId == null) {
            ownerChatId = chatId
            saveOwner(chatId)
            println("Bot claimed by chat ID: $chatId")
            val mode = if (isTmuxMode) "tmux session" else "standalone"
            api.sendMessage(chatId, "Bot claimed ($mode mode).\n\nCommands:\n/output or /o — last 10 lines\n/kill — kill/interrupt process\nAny other text — send as input")
        } else if (chatId == ownerChatId) {
            api.sendMessage(chatId, "You are already the owner.")
        } else {
            api.sendMessage(chatId, "Unauthorized. This bot is locked to another user.")
        }
    }

    // --- Input ---

    private fun handleInput(chatId: Long, text: String) {
        if (isTmuxMode) {
            if (!isTmuxAlive()) {
                api.sendMessage(chatId, "Tmux session is not running.")
                return
            }
            tmuxSendKeys(text)
            tmuxSendEnter()
        } else {
            val s = session
            if (s == null || !s.isAlive()) {
                api.sendMessage(chatId, "No running process. Use /run <command> first.")
                return
            }
            s.sendInput(text)
        }
    }

    // --- Output ---

    private fun handleOutput(chatId: Long) {
        if (isTmuxMode) {
            if (!isTmuxAlive()) {
                api.sendMessage(chatId, "Tmux session is not running.")
                return
            }
            val output = tmuxCapturePane()
            val lines = output.lines()
                .map { it.trimEnd() }
                .dropLastWhile { it.isBlank() }
                .takeLast(10)
            if (lines.isEmpty()) {
                api.sendMessage(chatId, "(no visible output)")
            } else {
                api.sendMessage(chatId, lines.joinToString("\n"))
            }
        } else {
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
    }

    // --- Kill ---

    private fun handleKill(chatId: Long) {
        if (isTmuxMode) {
            if (!isTmuxAlive()) {
                api.sendMessage(chatId, "Tmux session is not running.")
                return
            }
            // Send Ctrl-C to interrupt the running process
            tmuxExec("send-keys", "-t", tmuxSessionName!!, "C-c")
            api.sendMessage(chatId, "Sent Ctrl-C.")
        } else {
            val s = session
            if (s == null || !s.isAlive()) {
                api.sendMessage(chatId, "No running process to kill.")
                return
            }
            s.kill()
            api.sendMessage(chatId, "Process killed.")
        }
    }

    // --- Run (standalone only) ---

    private fun handleRun(chatId: Long, command: String) {
        if (isTmuxMode) {
            api.sendMessage(chatId, "Process is managed locally. Use text input or /kill.")
            return
        }
        if (command.isBlank()) {
            api.sendMessage(chatId, "Usage: /run <command>")
            return
        }
        session?.let { if (it.isAlive()) it.kill() }
        try {
            session = ProcessSession(command)
            api.sendMessage(chatId, "Started: $command")
        } catch (e: Exception) {
            api.sendMessage(chatId, "Failed to start process: ${e.message}")
        }
    }

    // --- Tmux helpers ---

    private fun isTmuxAlive(): Boolean {
        return tmuxExec("has-session", "-t", tmuxSessionName!!) == 0
    }

    private fun tmuxSendKeys(text: String) {
        tmuxExec("send-keys", "-t", tmuxSessionName!!, "-l", text)
    }

    private fun tmuxSendEnter() {
        tmuxExec("send-keys", "-t", tmuxSessionName!!, "Enter")
    }

    private fun tmuxCapturePane(): String {
        return try {
            val pb = ProcessBuilder("tmux", "capture-pane", "-t", tmuxSessionName!!, "-p")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText()
            p.waitFor()
            output
        } catch (_: Exception) {
            ""
        }
    }

    private fun tmuxExec(vararg args: String): Int {
        return try {
            val cmd = arrayOf("tmux") + args
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
