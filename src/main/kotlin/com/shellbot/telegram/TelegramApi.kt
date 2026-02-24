package com.shellbot.telegram

import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Thin HTTP wrapper for the Telegram Bot API.
 */
class TelegramApi(private val token: String) {
    private val log = LoggerFactory.getLogger(TelegramApi::class.java)
    private val baseUrl = "https://api.telegram.org/bot$token"
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    data class Update(
        val updateId: Long,
        val chatId: Long,
        val text: String?
    )

    fun getUpdates(offset: Long, timeout: Int = 30): List<Update> {
        val url = "$baseUrl/getUpdates?offset=$offset&timeout=$timeout"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeout + 10L))
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val json = JSONObject(response.body())

        if (!json.getBoolean("ok")) return emptyList()

        val result = json.getJSONArray("result")
        return (0 until result.length()).mapNotNull { i ->
            val update = result.getJSONObject(i)
            val updateId = update.getLong("update_id")
            val message = update.optJSONObject("message") ?: return@mapNotNull null
            val chat = message.getJSONObject("chat")
            val chatId = chat.getLong("id")
            val text = message.optString("text", null)
            Update(updateId, chatId, text)
        }
    }

    fun sendMessage(chatId: Long, text: String): Long? {
        val body = JSONObject()
        body.put("chat_id", chatId)
        body.put("text", text)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/sendMessage"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return try {
            val json = JSONObject(response.body())
            if (json.getBoolean("ok")) {
                val messageId = json.getJSONObject("result").getLong("message_id")
                log.debug("[sendMessage] chatId={}, textLength={}, messageId={}", chatId, text.length, messageId)
                messageId
            } else {
                log.warn("[sendMessage] API returned ok=false: {}", response.body())
                null
            }
        } catch (e: Exception) {
            log.error("[sendMessage] exception parsing response: {}", response.body(), e)
            null
        }
    }

    fun editMessageText(chatId: Long, messageId: Long, text: String): Boolean {
        val body = JSONObject()
        body.put("chat_id", chatId)
        body.put("message_id", messageId)
        body.put("text", text)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/editMessageText"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val json = JSONObject(response.body())
            val ok = json.getBoolean("ok")
            if (ok) {
                log.debug("[editMessageText] chatId={}, messageId={}, textLength={}", chatId, messageId, text.length)
            } else {
                log.warn("[editMessageText] API returned ok=false: {}", response.body())
            }
            ok
        } catch (e: Exception) {
            log.error("[editMessageText] exception", e)
            false
        }
    }
}
