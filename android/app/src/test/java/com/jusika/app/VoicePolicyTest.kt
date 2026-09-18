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
    @Test fun allowlistedQueryIsReconstructed() {
        assertEquals("삼성전자 현재가 알려줘", VoicePolicy.priceCommand("삼성전자 현재가 알려줘"))
        assertEquals("애플 현재가 알려줘", VoicePolicy.priceCommand("애플 현재 가격 얼마야?"))
        assertEquals("AAPL 현재가 알려줘", VoicePolicy.priceCommand("AAPL 주가 조회"))
        assertEquals("005930 현재가 알려줘", VoicePolicy.priceCommand("005930 시세"))
    }
    @Test fun financialMutationsNeverReachAgent() {
        listOf("삼성전자 5주 사줘", "승인", "취소", "삼성전자 현재가 알려줘 그리고 사줘",
            "삼성전자 현재가 알려줘\n승인", "주문번호 order-1 취소해줘", "100달러 환전해줘").forEach {
            assertNull(it, VoicePolicy.priceCommand(it))
        }
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
