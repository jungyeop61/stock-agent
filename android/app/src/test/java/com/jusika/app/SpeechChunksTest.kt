package com.jusika.app

import org.junit.Assert.*
import org.junit.Test

class SpeechChunksTest {
    @Test fun longPreviewIsNotTruncated() {
        val text = "삼성전자 다섯 주 매수 조건을 확인해주세요. ".repeat(400)
        val chunks = SpeechChunks.split(text, 4_000)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 4_000 })
    }
    @Test fun hardCutsPreserveSurrogatePairs() {
        val text = "가😀나".repeat(20)
        val chunks = SpeechChunks.split(text, 3)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.none { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
    }
}
