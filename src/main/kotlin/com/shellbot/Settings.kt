package com.shellbot

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Paths
import java.util.Properties

data class TelegramSettings(
    val enabled: Boolean = true,
    val token: String = "",
    val idleNotifySeconds: Long = 30
)

data class SessionSettings(
    val telegram: TelegramSettings = TelegramSettings()
)

data class Settings(
    val sessions: Map<String, SessionSettings> = emptyMap()
) {
    fun getSessionTelegram(sessionId: String): TelegramSettings? {
        val session = sessions[sessionId] ?: return null
        return if (session.telegram.enabled && session.telegram.token.isNotBlank()) {
            session.telegram
        } else {
            null
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(Settings::class.java)
        private val CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".shellbot").toFile()
        private val SETTINGS_FILE = File(CONFIG_DIR, "settings.yaml")
        private val TOKEN_FILE = File(CONFIG_DIR, "telegram.token")
        private val CONFIG_FILE = File(CONFIG_DIR, "config.properties")

        fun load(): Settings {
            CONFIG_DIR.mkdirs()

            if (!SETTINGS_FILE.exists()) {
                migrate()
            }

            if (!SETTINGS_FILE.exists()) {
                return Settings()
            }

            return try {
                val yaml = Yaml()
                val raw = SETTINGS_FILE.inputStream().use { yaml.load<Map<String, Any>>(it) }
                    ?: return Settings()
                parseSettings(raw)
            } catch (e: Exception) {
                log.error("Failed to load settings.yaml", e)
                Settings()
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseSettings(raw: Map<String, Any>): Settings {
            val sessionsRaw = raw["sessions"] as? Map<String, Any> ?: return Settings()
            val sessions = mutableMapOf<String, SessionSettings>()

            for ((name, value) in sessionsRaw) {
                val sessionMap = value as? Map<String, Any> ?: continue
                val telegramMap = sessionMap["telegram"] as? Map<String, Any> ?: continue

                val enabled = telegramMap["enabled"] as? Boolean ?: true
                val token = telegramMap["token"]?.toString() ?: ""
                val idleNotifySeconds = when (val v = telegramMap["idleNotifySeconds"]) {
                    is Number -> v.toLong()
                    is String -> v.toLongOrNull() ?: 30L
                    else -> 30L
                }

                sessions[name] = SessionSettings(
                    telegram = TelegramSettings(
                        enabled = enabled,
                        token = token,
                        idleNotifySeconds = idleNotifySeconds
                    )
                )
            }

            return Settings(sessions)
        }

        fun promptSessionSetup(sessionId: String): Settings {
            val settings = load()
            if (settings.sessions.containsKey(sessionId)) {
                return settings
            }

            val reader = BufferedReader(InputStreamReader(System.`in`))

            print("No Telegram config for session '$sessionId'. Enable Telegram bot? [y/N] ")
            System.out.flush()
            val answer = reader.readLine()?.trim()?.lowercase() ?: ""

            if (answer != "y" && answer != "yes") {
                val newSessions = settings.sessions + (sessionId to SessionSettings(
                    telegram = TelegramSettings(enabled = false)
                ))
                val updated = Settings(newSessions)
                save(updated)
                return updated
            }

            print("Telegram bot API token: ")
            System.out.flush()
            val token = reader.readLine()?.trim() ?: ""

            if (token.isBlank()) {
                println("No token provided. Telegram disabled for this session. Will ask again next time.")
                return settings
            }

            val newSessions = settings.sessions + (sessionId to SessionSettings(
                telegram = TelegramSettings(enabled = true, token = token, idleNotifySeconds = 30)
            ))
            val updated = Settings(newSessions)
            save(updated)
            println("Saved Telegram config for session '$sessionId'.")
            return updated
        }

        private fun save(settings: Settings) {
            CONFIG_DIR.mkdirs()
            val options = DumperOptions().apply {
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                isPrettyFlow = true
            }
            val yaml = Yaml(options)
            val data = mapOf(
                "sessions" to settings.sessions.mapValues { (_, session) ->
                    mapOf(
                        "telegram" to mapOf(
                            "enabled" to session.telegram.enabled,
                            "token" to session.telegram.token,
                            "idleNotifySeconds" to session.telegram.idleNotifySeconds
                        )
                    )
                }
            )
            SETTINGS_FILE.writeText(yaml.dump(data))
        }

        private fun migrate() {
            val hasToken = TOKEN_FILE.exists() && TOKEN_FILE.readText().trim().isNotBlank()
            val hasConfig = CONFIG_FILE.exists()

            if (!hasToken && !hasConfig) return

            log.info("Migrating legacy config files to settings.yaml")

            val token = if (hasToken) TOKEN_FILE.readText().trim() else ""
            var idleNotifySeconds = 30L

            if (hasConfig) {
                try {
                    val props = Properties()
                    CONFIG_FILE.inputStream().use { props.load(it) }
                    val value = props.getProperty("idle.notify.seconds")
                    if (value != null) {
                        val parsed = value.trim().toLongOrNull()
                        if (parsed != null && parsed > 0) {
                            idleNotifySeconds = parsed
                        }
                    }
                } catch (_: Exception) {}
            }

            if (token.isBlank()) return

            val options = DumperOptions().apply {
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                isPrettyFlow = true
            }
            val yaml = Yaml(options)
            val data = mapOf(
                "sessions" to mapOf(
                    "shellbot" to mapOf(
                        "telegram" to mapOf(
                            "enabled" to true,
                            "token" to token,
                            "idleNotifySeconds" to idleNotifySeconds
                        )
                    )
                )
            )

            try {
                SETTINGS_FILE.writeText(yaml.dump(data))
                log.info("Created settings.yaml")

                if (hasToken) {
                    TOKEN_FILE.delete()
                    log.info("Deleted telegram.token")
                }
                if (hasConfig) {
                    CONFIG_FILE.delete()
                    log.info("Deleted config.properties")
                }
            } catch (e: Exception) {
                log.error("Failed to write settings.yaml during migration", e)
            }
        }
    }
}
