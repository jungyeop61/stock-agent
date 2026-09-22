package com.jusika.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

class AgentAccessException(val statusCode: Int) : Exception("Agent request rejected")

/** No broker/provider keys, redirects, or application-level POST retries in the mobile client. */
class AgentClient(private val baseUrl: String, private val mobileToken: String = "") {
    init {
        require(mobileToken.isEmpty() || Regex("[a-zA-Z0-9_-]{32,256}").matches(mobileToken))
        AgentEndpoint.validate(baseUrl, BuildConfig.DEBUG)
        require(mobileToken.isNotEmpty() || java.net.URI(baseUrl).host in
            setOf("127.0.0.1", "localhost", "10.0.2.2"))
    }
    @Volatile private var cancelled = false
    @Volatile private var active: HttpURLConnection? = null
    fun close() {
        cancelled = true
        runCatching { active?.disconnect() }
    }
    fun transcribe(wav: ByteArray): String {
        check(!cancelled)
        require(wav.size in 44..1_000_044)
        require(String(wav, 0, 4, Charsets.US_ASCII) == "RIFF")
        require(String(wav, 8, 4, Charsets.US_ASCII) == "WAVE")
        val connection = URL("$baseUrl/api/agent/transcriptions").openConnection() as HttpURLConnection
        active = connection
        try {
            check(!cancelled)
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "audio/wav")
            connection.setRequestProperty("X-Jusika-Request-Id", UUID.randomUUID().toString())
            if (mobileToken.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $mobileToken")
            connection.setFixedLengthStreamingMode(wav.size)
            connection.outputStream.use { it.write(wav) }
            val code = connection.responseCode
            if (code in setOf(401, 403, 429)) throw AgentAccessException(code)
            check(code == 200) { "Transcription unavailable" }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val value = reader.readText()
                check(value.length <= 4_096)
                value
            }
            return JSONObject(response).getString("text").trim().also {
                check(it.isNotEmpty() && it.length <= 500)
            }
        } finally {
            connection.disconnect()
            active = null
        }
    }
    fun send(request: VoiceRequest): AgentTurn {
        check(!cancelled)
        require(request.text.isNotBlank() && request.text.length <= 500)
        if (request.confirmationPreviewId != null) require(request.text in setOf("승인", "취소"))
        val connection = URL("$baseUrl/api/agent/sessions/${request.sessionId}/voice-messages")
            .openConnection() as HttpURLConnection
        active = connection
        try {
            check(!cancelled)
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 90_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("X-Jusika-Request-Id", request.turnId.toString())
            if (mobileToken.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $mobileToken")
            val body = JSONObject().put("text", request.text)
            request.confirmationPreviewId?.let { body.put("confirmation_preview_id", it) }
            // Fixed-length streaming avoids transparent replay of a streamed POST body.
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            if (code in setOf(401, 403, 429)) throw AgentAccessException(code)
            check(code == 200) { "Agent unavailable" }
            val response = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    check(output.size() + read <= 1_048_576)
                    output.write(buffer, 0, read)
                }
                String(output.toByteArray(), Charsets.UTF_8)
            }
            return decode(request.sessionId, response)
        } finally {
            connection.disconnect()
            active = null
        }
    }

    internal fun decode(session: UUID, raw: String): AgentTurn {
        val body = JSONObject(raw)
        check(body.getString("session_id") == session.toString())
        val status = AgentStatus.valueOf(body.getString("status"))
        val confirmation = body.get("requires_confirmation")
        check(confirmation is Boolean)
        check(confirmation == (status == AgentStatus.WAITING_CONFIRMATION))
        val message = body.getString("message")
        check(message.isNotBlank() && message.length <= 32_000)
        val previewId = if (!body.isNull("preview_id")) body.getString("preview_id") else null
        var expiresAt: Instant? = null
        if (confirmation) {
            check(previewId != null && previewId.isNotBlank() && previewId.length <= 256)
            val data = body.getJSONObject("data")
            if (!data.isNull("expiresAt")) {
                check(data.getString("previewId") == previewId)
                expiresAt = Instant.parse(data.getString("expiresAt"))
            } else {
                // Execution recovery uses an execution identifier rather than a price preview.
                check(data.getString("executionId") == previewId)
                check(data.getString("executionKind") in setOf("ORDER", "AMOUNT_ORDER"))
            }
        }
        return AgentTurn(session, status, message, confirmation, previewId, expiresAt)
    }
}
