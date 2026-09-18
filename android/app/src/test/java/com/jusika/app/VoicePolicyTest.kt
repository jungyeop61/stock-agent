package com.jusika.app

import org.junit.Assert.*
import org.junit.Test

class VoicePolicyTest {
    @Test fun wakeOnlyAndSpacedWakeAreRecognized() {
        assertEquals("", VoicePolicy.wakeCommand("주식아"))
        assertEquals("", VoicePolicy.wakeCommand("주식 아"))
        assertEquals("삼성전자 현재가 알려줘", VoicePolicy.wakeCommand("주식아 삼성전자 현재가 알려줘"))
    }
    @Test fun ambientWordsAndSubstringsDoNotWake() {
        listOf("삼성전자 현재가 알려줘", "주식", "주식아니야", "오늘은 주식아", "주식 알아봐").forEach {
            assertNull(it, VoicePolicy.wakeCommand(it))
        }
    }
    @Test fun explicitConsentIsNotOrdinaryAgreement() {
        assertTrue(VoicePolicy.approves("승 인!"))
        listOf("네", "응", "예", "승인하지마", "승인하고 애플 사줘").forEach {
            assertFalse(it, VoicePolicy.approves(it))
        }
    }
    @Test fun normalizingSpeechDoesNotInventAnOrder() {
        assertEquals("삼성전자 5주 사줘", VoicePolicy.normalize(" 삼성전자  5주\n사줘 "))
        assertFalse(VoicePolicy.cancels("주문번호 order-1 취소해줘"))
        assertTrue(VoicePolicy.cancels("취소해줘"))
    }
    @Test fun conversationEndAndMicrophoneOffAreDistinct() {
        assertTrue(VoicePolicy.endsConversation("그만"))
        assertTrue(VoicePolicy.endsConversation("종료해"))
        assertFalse(VoicePolicy.disablesStandby("그만"))
        assertTrue(VoicePolicy.disablesStandby("대기 기능 꺼줘"))
        assertTrue(VoicePolicy.disablesStandby("마이크 꺼줘"))
        assertFalse(VoicePolicy.endsConversation("대기 기능 꺼줘"))
        assertFalse(VoicePolicy.endsConversation("그만 사줘"))
    }
}
