package com.jusika.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmWaveTest {
    @Test fun encodesMonoPcmAsBoundedWave() {
        val output = PcmWave.encode(shortArrayOf(-32768, 0, 32767))
        assertEquals("RIFF", String(output.copyOfRange(0, 4)))
        assertEquals("WAVE", String(output.copyOfRange(8, 12)))
        assertEquals("data", String(output.copyOfRange(36, 40)))
        assertEquals(50, output.size)
        assertTrue(output.copyOfRange(44, 50).contentEquals(byteArrayOf(0, -128, 0, 0, -1, 127)))
    }
}
