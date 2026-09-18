package com.jusika.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

/** No keys, broker access, redirects, or application-level POST retries in the mobile client. */
class AgentClient(private val baseUrl: String) {
    @Volatile private var cancelled = false
    @Volatile private var active: HttpURLConnection? = null
    fun close() {
        cancelled = true
        runCatching { active?.disconnect() }
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
            val body = JSONObject().put("text", request.text)
            request.confirmationPreviewId?.let { body.put("confirmation_preview_id", it) }
            // Fixed-length streaming avoids transparent replay of a streamed POST body.
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            check(connection.responseCode == 200) { "Agent unavailable" }
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
