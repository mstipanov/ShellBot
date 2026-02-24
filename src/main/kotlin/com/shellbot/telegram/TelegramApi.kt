package com.shellbot.telegram

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Thin HTTP wrapper for the Telegram Bot API.
 */
class TelegramApi(private val token: String) {
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

    fun sendMessage(chatId: Long, text: String) {
        val body = JSONObject()
        body.put("chat_id", chatId)
        body.put("text", text)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/sendMessage"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        client.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
