package com.shellbot.telegram

import com.shellbot.plugin.SessionPlugin
import org.slf4j.LoggerFactory
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
    private val tmuxSessionName: String? = null,
    private val plugin: SessionPlugin? = null
) {
    private val api = TelegramApi(token)
    private val idleNotifySeconds: Long = loadIdleNotifySeconds()
    private var session: ProcessSession? = null
    private var offset = 0L
    @Volatile
    private var ownerChatId: Long? = null
    @Volatile
    private var lastSentContent: String? = null
    @Volatile
    private var lastSentMessageId: Long? = null
    @Volatile
    private var idleNotificationSent = false

    private val isTmuxMode get() = tmuxSessionName != null

    companion object {
        private val log = LoggerFactory.getLogger(TelegramBot::class.java)
        private val CONFIG_DIR: Path = Paths.get(System.getProperty("user.home"), ".shellbot")
        private val OWNER_FILE: Path = CONFIG_DIR.resolve("owner.txt")
        private val CONFIG_FILE: Path = CONFIG_DIR.resolve("config.properties")

        private const val DEFAULT_IDLE_NOTIFY_SECONDS = 10L

        private fun loadIdleNotifySeconds(): Long {
            try {
                val file = CONFIG_FILE.toFile()
                if (file.exists()) {
                    val props = java.util.Properties()
                    file.inputStream().use { props.load(it) }
                    val value = props.getProperty("idle.notify.seconds")
                    if (value != null) {
                        val parsed = value.trim().toLongOrNull()
                        if (parsed != null && parsed > 0) {
                            log.info("Idle notify timeout: {}s (from config)", parsed)
                            return parsed
                        }
                    }
                }
            } catch (_: Exception) {}
            log.info("Idle notify timeout: {}s (default)", DEFAULT_IDLE_NOTIFY_SECONDS)
            return DEFAULT_IDLE_NOTIFY_SECONDS
        }
    }

    private fun loadOwner() {
        try {
            val file = OWNER_FILE.toFile()
            if (file.exists()) {
                val id = file.readText().trim().toLongOrNull()
                if (id != null) {
                    ownerChatId = id
                    log.info("Loaded owner chat ID: {}", id)
                }
            }
        } catch (_: Exception) {}
    }

    private fun saveOwner(chatId: Long) {
        try {
            CONFIG_DIR.toFile().mkdirs()
            OWNER_FILE.toFile().writeText(chatId.toString())
        } catch (e: Exception) {
            log.warn("Could not save owner file", e)
        }
    }

    fun run() {
        loadOwner()
        log.info("Telegram bot started. Polling for updates...")
        if (ownerChatId == null) {
            log.info("Waiting for first user to claim the bot with /start...")
        }

        if (plugin != null && isTmuxMode) {
            startMonitorDaemon()
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
                log.error("Polling error", e)
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
            text.startsWith("/sb_run ") -> handleRun(chatId, text.removePrefix("/sb_run ").trim())
            text == "/sb_output" || text == "/sb_o" -> handleOutput(chatId)
            text == "/sb_kill" -> handleKill(chatId)
            text == "/sb_enter" || text == "/sb_e" -> handleEnter(chatId)
            text == "/sb_help" -> handleHelp(chatId)
            else -> handleInput(chatId, text)
        }
    }

    private val helpText = """
        |Commands:
        |/sb_run <cmd> — start a process (standalone mode)
        |/sb_output or /sb_o — last lines of output
        |/sb_enter or /sb_e — send Enter key
        |/sb_kill — kill/interrupt process (Ctrl-C)
        |/sb_help — show this help
        |
        |Any other text is forwarded as input.
    """.trimMargin()

    private fun handleStart(chatId: Long) {
        if (ownerChatId == null) {
            ownerChatId = chatId
            saveOwner(chatId)
            log.info("Bot claimed by chat ID: {}", chatId)
            val mode = if (isTmuxMode) "tmux session" else "standalone"
            api.sendMessage(chatId, "Bot claimed ($mode mode).\n\n$helpText")
        } else if (chatId == ownerChatId) {
            api.sendMessage(chatId, helpText)
        } else {
            api.sendMessage(chatId, "Unauthorized. This bot is locked to another user.")
        }
    }

    private fun handleHelp(chatId: Long) {
        api.sendMessage(chatId, helpText)
    }

    // --- Input ---

    private fun handleEnter(chatId: Long) {
        if (isTmuxMode) {
            if (!isTmuxAlive()) {
                api.sendMessage(chatId, "Tmux session is not running.")
                return
            }
            tmuxSendEnter()
            lastSentContent = null
            lastSentMessageId = null
            idleNotificationSent = false
            plugin?.onUserInput()
        } else {
            val s = session
            if (s == null || !s.isAlive()) {
                api.sendMessage(chatId, "No running process. Use /run <command> first.")
                return
            }
            s.sendInput("")
            lastSentContent = null
            lastSentMessageId = null
            idleNotificationSent = false
        }
    }

    private fun handleInput(chatId: Long, text: String) {
        if (isTmuxMode) {
            if (!isTmuxAlive()) {
                api.sendMessage(chatId, "Tmux session is not running.")
                return
            }
            tmuxSendKeys(text)
            tmuxSendEnter()
            lastSentContent = null
            lastSentMessageId = null
            idleNotificationSent = false
            plugin?.onUserInput()
        } else {
            val s = session
            if (s == null || !s.isAlive()) {
                api.sendMessage(chatId, "No running process. Use /run <command> first.")
                return
            }
            s.sendInput(text)
            lastSentContent = null
            lastSentMessageId = null
            idleNotificationSent = false
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
            val lines = if (plugin != null) {
                plugin.filterOutput(output)
            } else {
                output.lines()
                    .map { it.trimEnd() }
                    .dropLastWhile { it.isBlank() }
                    .takeLast(10)
            }
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

    // --- Plugin monitor ---

    private fun startMonitorDaemon() {
        val thread = Thread({
            try {
                var lastChangeTime = System.currentTimeMillis()

                while (isTmuxAlive()) {
                    Thread.sleep(2000)
                    val owner = ownerChatId ?: continue
                    try {
                        val output = tmuxCapturePane()
                        if (output.isBlank()) continue
                        val lines = plugin!!.filterOutput(output)
                        if (lines.isEmpty()) continue
                        val content = lines.joinToString("\n")
                        if (content != lastSentContent) {
                            lastChangeTime = System.currentTimeMillis()
                            idleNotificationSent = false

                            val existingId = lastSentMessageId
                            if (existingId != null) {
                                val edited = api.editMessageText(owner, existingId, content)
                                if (!edited) {
                                    val newId = api.sendMessage(owner, content)
                                    if (newId != null) lastSentMessageId = newId
                                }
                            } else {
                                val newId = api.sendMessage(owner, content)
                                if (newId != null) lastSentMessageId = newId
                            }
                            lastSentContent = content
                        } else if (!idleNotificationSent) {
                            // Output unchanged — check if idle long enough to notify
                            val idleMs = System.currentTimeMillis() - lastChangeTime
                            if (idleMs >= idleNotifySeconds * 1000L) {
                                val notifications = plugin.checkForNotifications(output)
                                for (msg in notifications) {
                                    api.sendMessage(owner, msg)
                                }
                                if (notifications.isNotEmpty()) {
                                    idleNotificationSent = true
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }, "plugin-monitor")
        thread.isDaemon = true
        thread.start()
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
