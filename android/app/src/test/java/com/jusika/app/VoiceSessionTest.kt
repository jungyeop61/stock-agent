package com.jusika.app

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

class VoiceSessionTest {
    private var clock = 0L
    private val wall = Instant.parse("2026-09-18T09:00:00Z")
    private lateinit var session: VoiceSession
    @Before fun setup() { session = VoiceSession({ clock }, { wall.plusMillis(clock) }) }
    private fun activate() { session.activate(); session.speechFinished() }
    private fun send(text: String) = (session.decide(text) as VoiceDecision.Send).request
    private fun response(request: VoiceRequest, status: AgentStatus, expiry: Instant? = null) = AgentTurn(
        request.sessionId, status, "주문 내용을 안내합니다.", status == AgentStatus.WAITING_CONFIRMATION,
        if (status == AgentStatus.WAITING_CONFIRMATION) "preview-1" else null, expiry,
    )
    private fun preview(expiry: Instant? = wall.plusSeconds(120)) {
        activate()
        val request = send("삼성전자 5주 사줘")
        assertTrue(session.receive(request, response(request, AgentStatus.WAITING_CONFIRMATION, expiry)))
    }
    @Test fun ambientSpeechNeverSendsBeforeWake() {
        assertEquals(VoiceDecision.Ignore, session.decide("삼성전자 5주 사줘"))
        assertEquals(VoiceDecision.Ignore, session.decide("승인"))
    }
    @Test fun allCommandsAndSlotAnswersUseSameSession() {
        activate()
        val request = send("사줘")
        session.receive(request, response(request, AgentStatus.NEEDS_INPUT))
        session.speechFinished()
        val slot = send("삼성전자")
        assertEquals(request.sessionId, slot.sessionId)
        assertEquals("삼성전자", slot.text)
    }
    @Test fun approvalCannotArriveBeforeFullSpeechCompletion() {
        preview()
        assertEquals(VoiceDecision.Ignore, session.decide("승인"))
        session.speechFinished()
        val approve = send("승인")
        assertEquals("preview-1", approve.confirmationPreviewId)
        assertEquals("승인", approve.text)
    }
    @Test fun ambiguousAnswersAndChangedOrdersCannotApprove() {
        preview(); session.speechFinished()
        listOf("네", "예", "응", "주문해", "사", "애플 2주 사줘", "승인하지마").forEach {
            assertTrue(it, session.decide(it) is VoiceDecision.Prompt)
        }
        assertTrue(session.waitingConfirmation)
    }
    @Test fun duplicateAndConcurrentInputsAreIgnored() {
        activate(); val request = send("삼성전자 5주 사줘")
        assertEquals(VoiceDecision.Ignore, session.decide("삼성전자 5주 사줘"))
        session.receive(request, response(request, AgentStatus.WAITING_CONFIRMATION)); session.speechFinished()
        send("승인")
        assertEquals(VoiceDecision.Ignore, session.decide("승인"))
    }
    @Test fun expiryStartsBeforeReadoutAndUsesServerDeadline() {
        preview(wall.plusSeconds(2)); clock = 2_001
        session.speechFinished()
        assertEquals(VoiceDecision.Expired, session.decide("승인"))
        assertEquals("취소", session.endRequest()!!.text)
    }
    @Test fun repromptDoesNotRenewConsentAndRecoveryAlsoExpires() {
        preview(null); session.speechFinished(); clock = 100_000
        val request = send("승인")
        session.receive(request, response(request, AgentStatus.WAITING_CONFIRMATION)); session.speechFinished()
        clock = 120_001
        assertEquals(VoiceDecision.Expired, session.decide("승인"))
    }
    @Test fun transportFailureDropsSessionAndNeverRetriesApproval() {
        preview(); session.speechFinished(); val approve = send("승인")
        assertTrue(session.failed(approve))
        assertNotEquals(approve.sessionId, session.sessionId)
        assertEquals(VoiceDecision.Ignore, session.decide("승인"))
        assertFalse(session.receive(approve, response(approve, AgentStatus.COMPLETED)))
    }
    @Test fun endingPreviewOnlySendsBoundCancellation() {
        preview(); val request = session.endRequest()!!
        assertEquals("취소", request.text)
        assertEquals("preview-1", request.confirmationPreviewId)
    }
    @Test fun endingInputCollectionCancelsWithoutPreview() {
        activate(); val request = send("사줘")
        session.receive(request, response(request, AgentStatus.NEEDS_INPUT))
        val cancel = session.endRequest()!!
        assertEquals(request.sessionId, cancel.sessionId)
        assertNull(cancel.confirmationPreviewId)
    }
    @Test fun completedBrokerOrderIsNeverCancelledOnConversationEnd() {
        activate(); val request = send("내 보유 주식 알려줘")
        session.receive(request, response(request, AgentStatus.COMPLETED))
        assertNull(session.endRequest())
        session.abandon(); activate()
        assertNotEquals(request.sessionId, session.sessionId)
        assertTrue(session.decide("승인") is VoiceDecision.Prompt)
    }
    @Test fun oversizedCommandIsNotSent() {
        activate(); assertTrue(session.decide("가".repeat(501)) is VoiceDecision.Prompt)
    }
}
