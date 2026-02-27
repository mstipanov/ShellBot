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
    private var lastIdleMessageId: Long? = null  // Separate ID for idle messages
    @Volatile
    private var idleNotificationSent = false
    @Volatile
    private var generalIdleNotificationSent = false

    private val isTmuxMode get() = tmuxSessionName != null

    companion object {
        private val log = LoggerFactory.getLogger(TelegramBot::class.java)
        private val CONFIG_DIR: Path = Paths.get(System.getProperty("user.home"), ".shellbot")
        private val OWNER_FILE: Path = CONFIG_DIR.resolve("owner.txt")
        private val CONFIG_FILE: Path = CONFIG_DIR.resolve("config.properties")
        private val LAST_MESSAGE_LOG: Path = CONFIG_DIR.resolve("last_telegram_message.txt")

        // Get attachments directory dynamically based on current working directory
        private fun getAttachmentsDir(): Path {
            val cwd = Paths.get("").toAbsolutePath()
            return cwd.resolve(".shellbot").resolve("attachments")
        }

        private const val DEFAULT_IDLE_NOTIFY_SECONDS = 30L

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

        private fun logLastTelegramMessage(chatId: Long, message: String) {
            try {
                CONFIG_DIR.toFile().mkdirs()
                val timestamp = java.time.Instant.now().toString()
                val logEntry = """
                    |=== Telegram Message Sent ===
                    |Time: $timestamp
                    |Chat ID: $chatId
                    |Message length: ${message.length} chars
                    |Message preview: ${message.take(100)}${if (message.length > 100) "..." else ""}
                    |Full message:
                    |$message
                    |
                    """.trimMargin()
                LAST_MESSAGE_LOG.toFile().writeText(logEntry)
                log.debug("Logged last Telegram message to: {}", LAST_MESSAGE_LOG)
            } catch (e: Exception) {
                log.warn("Failed to log last Telegram message", e)
            }
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

        if (isTmuxMode) {
            startMonitorDaemon()
        }

        while (true) {
            try {
                val updates = api.getUpdates(offset)
                for (update in updates) {
                    offset = update.updateId + 1
                    handleUpdate(update)
                }
            } catch (e: Exception) {
                log.error("Polling error", e)
                Thread.sleep(3000)
            }
        }
    }

    private fun handleUpdate(update: TelegramApi.Update) {
        val chatId = update.chatId

        // Debug: log what we received
        log.debug("Received update: chatId={}, text={}, photo={}, document={}, audio={}, voice={}",
            chatId, update.text != null, update.photo != null, update.document != null, update.audio != null, update.voice != null)

        // Handle attachments first if present
        if (update.photo != null || update.document != null || update.audio != null || update.voice != null) {
            log.info("Handling attachment for chatId: {}", chatId)
            handleAttachment(chatId, update)
        }

        // Then handle text if present
        val text = update.text
        if (text != null) {
            handleMessage(chatId, text)
        } else if (update.photo == null && update.document == null && update.audio == null && update.voice == null) {
            // If no text and no attachments, ignore the update
            return
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

    private fun handleAttachment(chatId: Long, update: TelegramApi.Update) {
        log.info("Handling attachment for chatId: {}, photo={}, document={}, audio={}, voice={}",
            chatId, update.photo != null, update.document != null, update.audio != null, update.voice != null)

        // Check authorization
        if (ownerChatId == null) {
            log.warn("Bot not claimed yet")
            api.sendMessage(chatId, "Bot not claimed yet. Send /start first.")
            return
        }
        if (chatId != ownerChatId) {
            log.warn("Unauthorized access attempt from chatId: {}", chatId)
            api.sendMessage(chatId, "Unauthorized. This bot is locked to another user.")
            return
        }

        // Create attachments directory if it doesn't exist
        val attachmentsDir = getAttachmentsDir()
        try {
            attachmentsDir.toFile().mkdirs()
        } catch (e: Exception) {
            log.error("Failed to create attachments directory", e)
            api.sendMessage(chatId, "Failed to create attachments directory: ${e.message}")
            return
        }

        // Handle photos
        if (update.photo != null && update.photo.isNotEmpty()) {
            // Telegram sends multiple sizes, get the largest one (usually last)
            val largestPhoto = update.photo.last()
            val fileData = api.downloadFile(largestPhoto.fileId)
            if (fileData != null) {
                val timestamp = System.currentTimeMillis()
                val fileName = "photo_${timestamp}_${largestPhoto.fileUniqueId}.jpg"
                val filePath = attachmentsDir.resolve(fileName)
                try {
                    filePath.toFile().writeBytes(fileData)
                    log.info("Saved image: {}", filePath)

                    // Check if we have a plugin that can process audio
                    val command = plugin?.processImage(filePath.toFile().absolutePath)
                    if (command == null) {
                        log.warn("No plugin available to process images")
                        api.sendMessage(chatId, "No plugin available to process images.")
                        return
                    }
                    log.info("Image processing command from plugin: {}", command)
                    if (isTmuxMode) {
                        if (!isTmuxAlive()) {
                            log.warn("Tmux session is not running")
                            api.sendMessage(chatId, "Tmux session is not running. Cannot process image.")
                            return
                        }
                        log.info("Sending image command to tmux: {}", command)
                        tmuxSendKeys(command)
                        tmuxSendEnter()
                        log.info("Image command sent to tmux")
                    } else {
                        val s = session
                        if (s == null || !s.isAlive()) {
                            log.warn("No running process")
                            api.sendMessage(chatId, "No running process. Cannot process image.")
                            return
                        }
                        log.info("Sending image command to process: {}", command)
                        s.sendInput(command)
                        log.info("Image command sent to process")
                    }
                } catch (e: Exception) {
                    log.error("Failed to save photo", e)
                    api.sendMessage(chatId, "Failed to save photo: ${e.message}")
                }
            } else {
                api.sendMessage(chatId, "Failed to download photo")
            }
        }

        // Handle documents
        if (update.document != null) {
            val document = update.document
            val fileData = api.downloadFile(document.fileId)
            if (fileData != null) {
                val timestamp = System.currentTimeMillis()
                val fileName = document.fileName ?: "document_${timestamp}_${document.fileUniqueId}"
                val filePath = attachmentsDir.resolve(fileName)
                try {
                    filePath.toFile().writeBytes(fileData)
                    api.sendMessage(chatId, "File saved: $fileName")
                    log.info("Saved document: {}", filePath)

                    // Check if it's an audio file and process it
                    if (isAudioFile(fileName, document.mimeType)) {
                        processAudioFile(chatId, filePath.toFile().absolutePath)
                    }
                } catch (e: Exception) {
                    log.error("Failed to save document", e)
                    api.sendMessage(chatId, "Failed to save file: ${e.message}")
                }
            } else {
                api.sendMessage(chatId, "Failed to download file")
            }
        }

        // Handle audio files
        if (update.audio != null) {
            log.info("Processing audio attachment")
            val audio = update.audio
            log.debug("Audio details: fileId={}, fileName={}, mimeType={}, fileSize={}",
                audio.fileId, audio.fileName, audio.mimeType, audio.fileSize)

            val fileData = api.downloadFile(audio.fileId)
            if (fileData != null) {
                log.debug("Downloaded audio file, size: {} bytes", fileData.size)
                val timestamp = System.currentTimeMillis()
                val fileName = audio.fileName ?: "audio_${timestamp}_${audio.fileUniqueId}.mp3"
                val filePath = attachmentsDir.resolve(fileName)
                try {
                    filePath.toFile().writeBytes(fileData)
                    api.sendMessage(chatId, "Audio saved: $fileName")
                    log.info("Saved audio: {}", filePath)

                    // Process the audio file
                    processAudioFile(chatId, filePath.toFile().absolutePath)
                } catch (e: Exception) {
                    log.error("Failed to save audio", e)
                    api.sendMessage(chatId, "Failed to save audio: ${e.message}")
                }
            } else {
                log.error("Failed to download audio file")
                api.sendMessage(chatId, "Failed to download audio")
            }
        }

        // Handle voice messages (voice notes)
        if (update.voice != null) {
            log.info("Processing voice attachment")
            val voice = update.voice
            log.debug("Voice details: fileId={}, duration={}, mimeType={}, fileSize={}",
                voice.fileId, voice.duration, voice.mimeType, voice.fileSize)

            val fileData = api.downloadFile(voice.fileId)
            if (fileData != null) {
                log.debug("Downloaded voice file, size: {} bytes", fileData.size)
                val timestamp = System.currentTimeMillis()
                val fileName = "voice_${timestamp}_${voice.fileUniqueId}.ogg"  // Voice notes are usually OGG
                val filePath = attachmentsDir.resolve(fileName)
                try {
                    filePath.toFile().writeBytes(fileData)
                    log.info("Saved voice: {}", filePath)

                    // Process the voice file as audio
                    processAudioFile(chatId, filePath.toFile().absolutePath)
                } catch (e: Exception) {
                    log.error("Failed to save voice", e)
                    api.sendMessage(chatId, "Failed to save voice: ${e.message}")
                }
            } else {
                log.error("Failed to download voice file")
                api.sendMessage(chatId, "Failed to download voice")
            }
        }
    }

    private fun isAudioFile(fileName: String, mimeType: String?): Boolean {
        val lowerName = fileName.lowercase()
        val audioExtensions = setOf(".mp3", ".wav", ".ogg", ".m4a", ".aac", ".flac", ".opus", ".webm")
        val audioMimeTypes = setOf("audio/", "audio/mpeg", "audio/wav", "audio/ogg", "audio/mp4", "audio/aac", "audio/flac", "audio/webm")

        // Check by file extension
        if (audioExtensions.any { lowerName.endsWith(it) }) {
            return true
        }

        // Check by MIME type
        if (mimeType != null && audioMimeTypes.any { mimeType.startsWith(it) }) {
            return true
        }

        return false
    }

    private fun processAudioFile(chatId: Long, filePath: String) {
        log.info("Processing audio file: {}", filePath)

        // Check if we have a plugin that can process audio
        val command = plugin?.processAudio(filePath)
        if (command == null) {
            log.warn("No plugin available to process audio files")
            api.sendMessage(chatId, "No plugin available to process audio files.")
            return
        }

        log.info("Audio processing command from plugin: {}", command)

        if (isTmuxMode) {
            if (!isTmuxAlive()) {
                log.warn("Tmux session is not running")
                api.sendMessage(chatId, "Tmux session is not running. Cannot process audio.")
                return
            }
            log.info("Sending audio command to tmux: {}", command)
            tmuxSendKeys(command)
            tmuxSendEnter()
            log.info("Audio command sent to tmux")
        } else {
            val s = session
            if (s == null || !s.isAlive()) {
                log.warn("No running process")
                api.sendMessage(chatId, "No running process. Cannot process audio.")
                return
            }
            log.info("Sending audio command to process: {}", command)
            s.sendInput(command)
            log.info("Audio command sent to process")
        }
    }

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
            generalIdleNotificationSent = false
            // Delete idle message when user sends input
            val owner = ownerChatId
            val previousIdleMessageId = lastIdleMessageId
            if (owner != null && previousIdleMessageId != null) {
                api.deleteMessage(owner, previousIdleMessageId)
                lastIdleMessageId = null
            }
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
            generalIdleNotificationSent = false
            // Delete idle message when user sends input
            val owner = ownerChatId
            val previousIdleMessageId = lastIdleMessageId
            if (owner != null && previousIdleMessageId != null) {
                api.deleteMessage(owner, previousIdleMessageId)
                lastIdleMessageId = null
            }
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
            generalIdleNotificationSent = false
            // Delete idle message when user sends input
            val owner = ownerChatId
            val previousIdleMessageId = lastIdleMessageId
            if (owner != null && previousIdleMessageId != null) {
                api.deleteMessage(owner, previousIdleMessageId)
                lastIdleMessageId = null
            }
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
            generalIdleNotificationSent = false
            // Delete idle message when user sends input
            val owner = ownerChatId
            val previousIdleMessageId = lastIdleMessageId
            if (owner != null && previousIdleMessageId != null) {
                api.deleteMessage(owner, previousIdleMessageId)
                lastIdleMessageId = null
            }
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

    // --- Inactivity monitor ---

    private fun startMonitorDaemon() {
        val thread = Thread({
            try {
                var lastChangeTime = System.currentTimeMillis()
                var lastLogTime = System.currentTimeMillis()
                var lastContent: String? = null

                while (isTmuxAlive()) {
                    Thread.sleep(2000)
                    try {
                        val output = tmuxCapturePane()
                        if (output.isBlank()) continue

                        val lines = if (plugin != null) {
                            plugin.filterOutput(output)
                        } else {
                            output.lines()
                                .map { it.trimEnd() }
                                .dropLastWhile { it.isBlank() }
                                .takeLast(10)
                        }

                        if (lines.isEmpty()) continue
                        val content = lines.joinToString("\n")

                        if (content != lastContent) {
                            lastChangeTime = System.currentTimeMillis()
                            lastLogTime = System.currentTimeMillis()
                            idleNotificationSent = false
                            generalIdleNotificationSent = false
                            // Delete previous idle message when new content arrives
                            val idleMessageOwner = ownerChatId
                            val previousIdleMessageId = lastIdleMessageId
                            if (idleMessageOwner != null && previousIdleMessageId != null) {
                                api.deleteMessage(idleMessageOwner, previousIdleMessageId)
                                lastIdleMessageId = null
                            }
                            lastContent = content

                            // Only send to Telegram if we have an owner
                            val owner = ownerChatId ?: continue
                            val existingId = lastSentMessageId
                            if (existingId != null) {
                                val edited = api.editMessageText(owner, existingId, content)
                                if (!edited) {
                                    val newId = api.sendMessage(owner, content)
                                    logLastTelegramMessage(owner, content)
                                    if (newId != null) lastSentMessageId = newId
                                }
                            } else {
                                val newId = api.sendMessage(owner, content)
                                logLastTelegramMessage(owner, content)
                                if (newId != null) lastSentMessageId = newId
                            }
                            lastSentContent = content
                        } else {
                            // Output unchanged — check if idle long enough
                            val idleMs = System.currentTimeMillis() - lastChangeTime
                            if (idleMs >= idleNotifySeconds * 1000L) {
                                // Always log to application log after 30s of inactivity
                                val timeSinceLastLog = System.currentTimeMillis() - lastLogTime
                                if (timeSinceLastLog >= idleNotifySeconds * 1000L) {
                                    TelegramBot.log.info("Session inactive: no new output to send to Telegram")
                                    // Send Telegram notification about inactivity (only once per inactivity period)
                                    val owner = ownerChatId
                                    if (owner != null && !generalIdleNotificationSent) {
                                        // Delete previous idle message if exists
                                        val previousIdleMessageId = lastIdleMessageId
                                        if (previousIdleMessageId != null) {
                                            // Try to delete the old idle message
                                            api.deleteMessage(owner, previousIdleMessageId)
                                        }

                                        // Send new idle message (don't update lastSentMessageId/content)
                                        val messageId = api.sendMessage(owner, "Session inactive: input needed!")
                                        logLastTelegramMessage(owner, "Session inactive: input needed!")
                                        if (messageId != null) {
                                            lastIdleMessageId = messageId
                                        }
                                        generalIdleNotificationSent = true
                                    }
                                    lastLogTime = System.currentTimeMillis()
                                }

                                // Only send plugin-specific Telegram notifications if plugin exists
                                if (!idleNotificationSent && plugin != null) {
                                    val notifications = plugin.checkForNotifications(output, idleNotifySeconds)
                                    for (msg in notifications) {
                                        val owner = ownerChatId ?: continue
                                        api.sendMessage(owner, msg)
                                        logLastTelegramMessage(owner, msg)
                                    }
                                    if (notifications.isNotEmpty()) {
                                        idleNotificationSent = true
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }, "inactivity-monitor")
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
