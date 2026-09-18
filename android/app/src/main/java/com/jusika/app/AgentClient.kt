package com.jusika.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** No keys, broker access, redirects, or automatic POST retries in the mobile client. */
class AgentClient(private val baseUrl: String) {
    @Volatile private var cancelled = false
    @Volatile private var active: HttpURLConnection? = null
    fun close() {
        cancelled = true
        active?.disconnect()
    }
    fun currentPrice(session: UUID, command: String): String {
        check(!cancelled)
        require(VoicePolicy.priceCommand(command) == command)
        val connection = URL("$baseUrl/api/agent/sessions/$session/messages")
            .openConnection() as HttpURLConnection
        active = connection
        try {
            check(!cancelled)
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("X-Jusika-Request-Id", UUID.randomUUID().toString())
            connection.outputStream.use { it.write(JSONObject().put("text", command).toString().toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode == 200) { "Agent unavailable" }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    check(output.size() + read <= 65_536)
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            check(bytes.size <= 65_536)
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            check(body.getString("session_id") == session.toString())
            // A current-price query must not leave any financial approval pending.
            check(!body.getBoolean("requires_confirmation"))
            check(body.getString("status") in setOf("COMPLETED", "NEEDS_INPUT", "ERROR"))
            return body.getString("message").also { check(it.isNotBlank() && it.length <= 4_000) }
        } finally {
            connection.disconnect()
            active = null
        }
    }
}
