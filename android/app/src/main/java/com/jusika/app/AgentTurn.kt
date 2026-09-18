package com.jusika.app

import java.time.Instant
import java.util.UUID

enum class AgentStatus { COMPLETED, WAITING_CONFIRMATION, CANCELLED, NEEDS_INPUT, ERROR }

data class AgentTurn(
    val sessionId: UUID,
    val status: AgentStatus,
    val message: String,
    val requiresConfirmation: Boolean,
    val previewId: String? = null,
    val expiresAt: Instant? = null,
)

data class VoiceRequest(
    val sessionId: UUID,
    val text: String,
    val confirmationPreviewId: String? = null,
    val turnId: UUID = UUID.randomUUID(),
)
