package com.jusika.app

import java.time.Instant
import java.util.UUID

sealed class VoiceDecision {
    data class Send(val request: VoiceRequest) : VoiceDecision()
    data class Prompt(val message: String) : VoiceDecision()
    data object Ignore : VoiceDecision()
    data object Expired : VoiceDecision()
}

/** Main-thread conversation gate, separately testable without a microphone or Android runtime. */
class VoiceSession(
    private val elapsedMs: () -> Long,
    private val wallNow: () -> Instant,
) {
    private data class Pending(val id: String, val deadlineMs: Long)
    var sessionId: UUID = UUID.randomUUID()
        private set
    private var active = false
    private var accepting = false
    private var pending: Pending? = null
    private var collectingInput = false
    private var inFlight: VoiceRequest? = null

    val waitingConfirmation get() = pending != null
    val confirmationExpired get() = pending?.let { elapsedMs() >= it.deadlineMs } == true

    fun activate() {
        abandon()
        active = true
    }
    fun abandon() {
        sessionId = UUID.randomUUID()
        active = false
        accepting = false
        pending = null
        collectingInput = false
        inFlight = null
    }
    fun beforeSpeech() { accepting = false }
    fun speechFinished() {
        if (active && inFlight == null) accepting = true
    }
    fun decide(raw: String): VoiceDecision {
        if (!active || !accepting || inFlight != null) return VoiceDecision.Ignore
        val text = VoicePolicy.normalize(raw)
        if (text.isEmpty() || text.length > 500) {
            return VoiceDecision.Prompt("명령은 오백 자 이내로 다시 말씀해주세요.")
        }
        val current = pending
        if (current != null) {
            if (confirmationExpired) return VoiceDecision.Expired
            val command = when {
                VoicePolicy.approves(text) -> "승인"
                VoicePolicy.cancels(text) -> "취소"
                else -> return VoiceDecision.Prompt("안내한 요청을 실행하려면 승인, 중단하려면 취소라고 말씀해주세요. 네나 응으로는 실행하지 않습니다.")
            }
            return send(command, current.id)
        }
        if (VoicePolicy.approves(text) || VoicePolicy.ambiguousApproval(text)) {
            return VoiceDecision.Prompt("현재 승인 대기 중인 요청이 없습니다. 먼저 요청 내용을 말씀해주세요.")
        }
        if (VoicePolicy.cancels(text)) {
            if (collectingInput) return send("취소")
            return VoiceDecision.Prompt("현재 입력하거나 승인 대기 중인 요청이 없습니다. 접수된 주문을 취소하려면 주문번호를 포함해 말씀해주세요.")
        }
        return send(text)
    }
    /** Cancel only a pending preview / slot dialogue, never an already accepted broker order. */
    fun endRequest(): VoiceRequest? {
        if (!active || inFlight != null || (pending == null && !collectingInput)) return null
        return (send("취소", pending?.id) as VoiceDecision.Send).request
    }
    private fun send(text: String, previewId: String? = null): VoiceDecision.Send {
        val request = VoiceRequest(sessionId, text, previewId)
        accepting = false
        inFlight = request
        return VoiceDecision.Send(request)
    }
    fun receive(request: VoiceRequest, response: AgentTurn): Boolean {
        if (!active || inFlight?.turnId != request.turnId || request.sessionId != sessionId) return false
        require(response.sessionId == sessionId)
        require(response.requiresConfirmation == (response.status == AgentStatus.WAITING_CONFIRMATION))
        val oldPending = pending
        if (response.requiresConfirmation) {
            val id = requireNotNull(response.previewId)
            require(id.isNotBlank())
            if (request.confirmationPreviewId != null) require(id == request.confirmationPreviewId)
            val now = elapsedMs()
            // Deadline starts at receipt, not after TTS, and cannot be extended by reprompting.
            val remaining = response.expiresAt?.let {
                java.time.Duration.between(wallNow(), it).toMillis().coerceIn(0L, 120_000L)
            } ?: 120_000L // Recovery confirmation also has a short local consent window.
            val deadline = now + remaining
            pending = Pending(id, if (oldPending?.id == id) minOf(oldPending.deadlineMs, deadline) else deadline)
        } else {
            pending = null
        }
        collectingInput = response.status == AgentStatus.NEEDS_INPUT
        accepting = false // Only the final TTS completion may open the consent gate.
        inFlight = null
        return true
    }
    fun failed(request: VoiceRequest): Boolean {
        if (inFlight?.turnId != request.turnId || request.sessionId != sessionId) return false
        abandon() // Never resend or resume a possibly executed request after a transport failure.
        return true
    }
}
