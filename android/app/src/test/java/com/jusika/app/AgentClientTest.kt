package com.jusika.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class AgentClientTest {
    private val session = UUID.randomUUID()
    private val client = AgentClient("http://127.0.0.1")
    private fun payload(status: String = "COMPLETED", confirmation: Boolean = false) = JSONObject()
        .put("session_id", session.toString()).put("status", status)
        .put("message", "조회 또는 주문 결과입니다.").put("requires_confirmation", confirmation)
    private fun preview() = payload("WAITING_CONFIRMATION", true)
        .put("preview_id", "preview-1")
        .put("data", JSONObject().put("previewId", "preview-1").put("expiresAt", "2026-09-18T09:02:00+00:00"))

    @Test fun parsesAllTurnStatesAndNeverDropsConfirmationTerms() {
        for (status in listOf("COMPLETED", "NEEDS_INPUT", "CANCELLED", "ERROR")) {
            assertEquals(AgentStatus.valueOf(status), client.decode(session, payload(status).toString()).status)
        }
        val turn = client.decode(session, preview().toString())
        assertTrue(turn.requiresConfirmation)
        assertEquals("preview-1", turn.previewId)
        assertEquals(Instant.parse("2026-09-18T09:02:00Z"), turn.expiresAt)
    }
    @Test fun recoveryConfirmationUsesExecutionId() {
        val body = payload("WAITING_CONFIRMATION", true).put("preview_id", "execution-1")
            .put("data", JSONObject().put("executionId", "execution-1").put("executionKind", "AMOUNT_ORDER"))
        assertEquals("execution-1", client.decode(session, body.toString()).previewId)
    }
    @Test fun inconsistentFlagsIdentifiersAndDatesFailClosed() {
        listOf(
            payload("WAITING_CONFIRMATION", false), payload("COMPLETED", true),
            preview().put("preview_id", "other-preview"),
            preview().put("session_id", UUID.randomUUID().toString()),
            preview().put("data", JSONObject().put("previewId", "preview-1").put("expiresAt", "not-a-date")),
        ).forEach { body -> assertThrows(Exception::class.java) { client.decode(session, body.toString()) } }
    }
    @Test fun sendsVoiceContractWithBoundConsentAndStableRequestId() {
        MockVoiceServer(200, payload().toString()).use { server ->
            val request = VoiceRequest(session, "승인", "preview-1")
            val response = AgentClient(server.url).send(request)
            assertEquals(AgentStatus.COMPLETED, response.status)
            assertEquals("POST /api/agent/sessions/$session/voice-messages HTTP/1.1", server.requestLine)
            assertEquals(request.turnId.toString(), server.headers["x-jusika-request-id"])
            assertEquals("preview-1", JSONObject(server.body).getString("confirmation_preview_id"))
            assertEquals("승인", JSONObject(server.body).getString("text"))
            assertEquals(1, server.calls.get())
        }
    }
    @Test fun errorsAndRedirectsAreNotRetried() {
        for (code in listOf(500, 302)) {
            MockVoiceServer(code, "").use { server ->
                val api = AgentClient(server.url)
                assertThrows(Exception::class.java) { api.send(VoiceRequest(session, "삼성전자 5주 사줘")) }
                assertEquals(1, server.calls.get())
            }
        }
    }
    @Test fun closedClientDoesNotSendAnyTurn() {
        client.close()
        assertThrows(IllegalStateException::class.java) { client.send(VoiceRequest(session, "승인", "preview-1")) }
    }
}

/** Minimal local HTTP fixture uses only java.base, also available on Android's test classpath. */
private class MockVoiceServer(code: Int, response: String) : java.io.Closeable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${socket.localPort}"
    val calls = AtomicInteger()
    @Volatile var body = ""
    @Volatile var requestLine = ""
    @Volatile var headers: Map<String, String> = emptyMap()
    private val worker = Thread {
        while (!socket.isClosed) {
            try {
                socket.accept().use { connection ->
                    connection.soTimeout = 2_000
                    calls.incrementAndGet()
                    val input = connection.getInputStream()
                    fun line(): String {
                        val bytes = java.io.ByteArrayOutputStream()
                        while (true) {
                            val value = input.read()
                            check(value >= 0 && bytes.size() <= 8_192)
                            if (value == 10) break
                            if (value != 13) bytes.write(value)
                        }
                        return bytes.toString("US-ASCII")
                    }
                    requestLine = line()
                    val received = mutableMapOf<String, String>()
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        received[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                    }
                    headers = received
                    val bytes = ByteArray(received.getValue("content-length").toInt())
                    var read = 0
                    while (read < bytes.size) {
                        val count = input.read(bytes, read, bytes.size - read)
                        check(count > 0); read += count
                    }
                    body = String(bytes, Charsets.UTF_8)
                    val result = response.toByteArray(Charsets.UTF_8)
                    connection.getOutputStream().apply {
                        write(("HTTP/1.1 $code Test\r\nContent-Length: ${result.size}\r\n" +
                            "Location: /unexpected\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        write(result); flush()
                    }
                }
            } catch (_: SocketTimeoutException) {
                continue
            } catch (error: Exception) {
                if (!socket.isClosed) throw error
            }
        }
    }.apply { isDaemon = true }
    init { socket.soTimeout = 200; worker.start() }
    override fun close() { socket.close(); worker.join(2_000) }
}
