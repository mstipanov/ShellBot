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
        val text: String?,
        val photo: List<PhotoSize>? = null,
        val document: Document? = null,
        val audio: Audio? = null,
        val voice: Voice? = null
    )

    data class PhotoSize(
        val fileId: String,
        val fileUniqueId: String,
        val width: Int,
        val height: Int,
        val fileSize: Int? = null
    )

    data class Document(
        val fileId: String,
        val fileUniqueId: String,
        val fileName: String? = null,
        val mimeType: String? = null,
        val fileSize: Int? = null
    )

    data class Audio(
        val fileId: String,
        val fileUniqueId: String,
        val duration: Int? = null,
        val performer: String? = null,
        val title: String? = null,
        val fileName: String? = null,
        val mimeType: String? = null,
        val fileSize: Int? = null
    )

    data class Voice(
        val fileId: String,
        val fileUniqueId: String,
        val duration: Int? = null,
        val mimeType: String? = null,
        val fileSize: Int? = null
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

            // Parse photo attachments
            val photoArray = message.optJSONArray("photo")
            val photos = if (photoArray != null) {
                (0 until photoArray.length()).map { j ->
                    val photo = photoArray.getJSONObject(j)
                    PhotoSize(
                        fileId = photo.getString("file_id"),
                        fileUniqueId = photo.getString("file_unique_id"),
                        width = photo.getInt("width"),
                        height = photo.getInt("height"),
                        fileSize = photo.optInt("file_size").takeIf { it > 0 }
                    )
                }
            } else null

            // Parse document attachments
            val documentObj = message.optJSONObject("document")
            val document = if (documentObj != null) {
                Document(
                    fileId = documentObj.getString("file_id"),
                    fileUniqueId = documentObj.getString("file_unique_id"),
                    fileName = documentObj.optString("file_name", null).takeIf { it.isNotEmpty() },
                    mimeType = documentObj.optString("mime_type", null).takeIf { it.isNotEmpty() },
                    fileSize = documentObj.optInt("file_size").takeIf { it > 0 }
                )
            } else null

            // Parse audio attachments
            val audioObj = message.optJSONObject("audio")
            val audio = if (audioObj != null) {
                Audio(
                    fileId = audioObj.getString("file_id"),
                    fileUniqueId = audioObj.getString("file_unique_id"),
                    duration = audioObj.optInt("duration").takeIf { it > 0 },
                    performer = audioObj.optString("performer", null).takeIf { it.isNotEmpty() },
                    title = audioObj.optString("title", null).takeIf { it.isNotEmpty() },
                    fileName = audioObj.optString("file_name", null).takeIf { it.isNotEmpty() },
                    mimeType = audioObj.optString("mime_type", null).takeIf { it.isNotEmpty() },
                    fileSize = audioObj.optInt("file_size").takeIf { it > 0 }
                )
            } else null

            // Parse voice attachments (voice notes)
            val voiceObj = message.optJSONObject("voice")
            val voice = if (voiceObj != null) {
                Voice(
                    fileId = voiceObj.getString("file_id"),
                    fileUniqueId = voiceObj.getString("file_unique_id"),
                    duration = voiceObj.optInt("duration").takeIf { it > 0 },
                    mimeType = voiceObj.optString("mime_type", null).takeIf { it.isNotEmpty() },
                    fileSize = voiceObj.optInt("file_size").takeIf { it > 0 }
                )
            } else null

            Update(updateId, chatId, text, photos, document, audio, voice)
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

    fun deleteMessage(chatId: Long, messageId: Long): Boolean {
        val body = JSONObject()
        body.put("chat_id", chatId)
        body.put("message_id", messageId)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/deleteMessage"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val json = JSONObject(response.body())
            val ok = json.getBoolean("ok")
            if (ok) {
                log.debug("[deleteMessage] chatId={}, messageId={}", chatId, messageId)
            } else {
                log.warn("[deleteMessage] API returned ok=false: {}", response.body())
            }
            ok
        } catch (e: Exception) {
            log.error("[deleteMessage] exception", e)
            false
        }
    }

    /**
     * Download a file from Telegram by file ID.
     * Returns the file contents as ByteArray, or null if download fails.
     */
    fun downloadFile(fileId: String): ByteArray? {
        // First get the file path using getFile API
        val getFileBody = JSONObject()
        getFileBody.put("file_id", fileId)

        val getFileRequest = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/getFile"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(getFileBody.toString()))
            .build()

        return try {
            val response = client.send(getFileRequest, HttpResponse.BodyHandlers.ofString())
            val json = JSONObject(response.body())
            if (!json.getBoolean("ok")) {
                log.warn("[downloadFile] getFile API returned ok=false: {}", response.body())
                return null
            }

            val result = json.getJSONObject("result")
            val filePath = result.getString("file_path")

            // Now download the actual file
            val fileUrl = "https://api.telegram.org/file/bot$token/$filePath"
            val fileRequest = HttpRequest.newBuilder()
                .uri(URI.create(fileUrl))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build()

            val fileResponse = client.send(fileRequest, HttpResponse.BodyHandlers.ofByteArray())
            fileResponse.body()
        } catch (e: Exception) {
            log.error("[downloadFile] exception", e)
            null
        }
    }
}
