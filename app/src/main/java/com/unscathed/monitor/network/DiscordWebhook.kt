package com.unscathed.monitor.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

class DiscordAlert(
    val event: AlertEvent,
    val title: String,
    val description: String? = null,
    val color: Int,
    val fields: List<Pair<String, String>> = emptyList(),
    val screenshotJpeg: ByteArray? = null,
    /** Message content above the embed: "<@id>" pings, "@name" is plain text. */
    val mention: String? = null,
    val createdAtWallMs: Long = System.currentTimeMillis(),
) {
    fun withField(name: String, value: String) =
        DiscordAlert(event, title, description, color, fields + (name to value), screenshotJpeg, mention, createdAtWallMs)

    companion object Colors {
        const val RED = 0xE74C3C
        const val ORANGE = 0xE67E22
        const val YELLOW = 0xF1C40F
        const val GREEN = 0x2ECC71
        const val BLUE = 0x3498DB
        const val PURPLE = 0x9B59B6
        const val GRAY = 0x95A5A6
        const val GOLD = 0xF5B700
        const val MAGENTA = 0xD946EF
    }
}

sealed interface SendResult {
    data object Ok : SendResult
    data class RetryLater(val reason: String, val afterMs: Long) : SendResult
    data class Rejected(val reason: String) : SendResult
}

class DiscordWebhook(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json {
        explicitNulls = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun send(url: String, alert: DiscordAlert, username: String): SendResult = withContext(Dispatchers.IO) {
        val hasImage = alert.screenshotJpeg != null
        val mention = alert.mention?.takeIf { it.isNotBlank() }
        val pingIds = mention?.let { DiscordMention.parseUserId(it) }?.let(::listOf).orEmpty()
        val payload = WebhookPayload(
            username = username.take(80).ifBlank { null },
            content = mention,
            // Only ever ping the configured user, never @everyone from OCR'd text.
            allowedMentions = AllowedMentions(users = pingIds),
            embeds = listOf(
                Embed(
                    title = alert.title.take(256),
                    description = alert.description?.take(4000),
                    color = alert.color,
                    fields = alert.fields.take(25).map { (k, v) -> EmbedField(k.take(256), v.ifBlank { "—" }.take(1024), inline = true) },
                    image = if (hasImage) EmbedImage("attachment://$SCREENSHOT_NAME") else null,
                    timestamp = Instant.ofEpochMilli(alert.createdAtWallMs).toString(),
                    footer = EmbedFooter("Unscathed Monitor"),
                ),
            ),
        )
        val payloadJson = json.encodeToString(WebhookPayload.serializer(), payload)

        try {
            val body = alert.screenshotJpeg?.let { jpeg ->
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("payload_json", payloadJson)
                    .addFormDataPart("files[0]", SCREENSHOT_NAME, jpeg.toRequestBody("image/jpeg".toMediaType()))
                    .build()
            } ?: payloadJson.toRequestBody("application/json".toMediaType())

            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> SendResult.Ok
                    resp.code == 429 -> SendResult.RetryLater("Rate limited", retryAfterMs(resp))
                    resp.code >= 500 -> SendResult.RetryLater("Discord HTTP ${resp.code}", 10_000)
                    else -> SendResult.Rejected("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                }
            }
        } catch (e: IllegalArgumentException) {
            SendResult.Rejected("Invalid webhook URL")
        } catch (e: IOException) {
            SendResult.RetryLater(e.message ?: "Network error", 15_000)
        }
    }

    /**
     * Asks Discord about the webhook without posting anything, so a broken URL is caught before
     * an AFK session starts rather than when the first alert is lost. Returns null when the
     * webhook is fine, or a short reason when it is not.
     */
    suspend fun check(url: String): String? = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://")) return@withContext "URL does not start with https://"
        try {
            val request = Request.Builder().url(trimmed).get().build()
            client.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> null
                    resp.code == 401 || resp.code == 403 -> "Discord rejected the token (webhook deleted or wrong URL)"
                    resp.code == 404 -> "Discord says this webhook does not exist"
                    resp.code == 429 -> null // rate limited, but the webhook is real
                    else -> "Discord replied HTTP ${resp.code}"
                }
            }
        } catch (e: IllegalArgumentException) {
            "Not a valid URL"
        } catch (e: IOException) {
            "Could not reach Discord: ${e.message ?: "network error"}"
        }
    }

    private fun retryAfterMs(resp: Response): Long {
        val fromBody = runCatching {
            json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject["retry_after"]?.jsonPrimitive?.doubleOrNull
        }.getOrNull()
        val seconds = fromBody ?: resp.header("Retry-After")?.toDoubleOrNull() ?: 5.0
        return (seconds * 1000).toLong().coerceIn(1_000, 120_000)
    }

    @Serializable
    private data class WebhookPayload(
        val username: String? = null,
        val content: String? = null,
        val embeds: List<Embed>,
        @SerialName("allowed_mentions") val allowedMentions: AllowedMentions = AllowedMentions(),
    )

    @Serializable
    private data class AllowedMentions(
        val parse: List<String> = emptyList(),
        val users: List<String> = emptyList(),
    )

    @Serializable
    private data class Embed(
        val title: String,
        val description: String? = null,
        val color: Int,
        val fields: List<EmbedField> = emptyList(),
        val image: EmbedImage? = null,
        val timestamp: String? = null,
        val footer: EmbedFooter? = null,
    )

    @Serializable
    private data class EmbedField(val name: String, val value: String, val inline: Boolean = false)

    @Serializable
    private data class EmbedImage(val url: String)

    @Serializable
    private data class EmbedFooter(@SerialName("text") val text: String)

    private companion object {
        const val SCREENSHOT_NAME = "screenshot.jpg"
    }
}
