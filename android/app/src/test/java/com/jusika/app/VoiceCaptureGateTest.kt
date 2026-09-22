package com.jusika.app

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCaptureGateTest {
    @Test fun requiresTwoLoudFramesAndCompletesAfterSilence() {
        val gate = VoiceCaptureGate()
        assertEquals(CaptureDecision.WAITING, gate.accept(800, 1_600))
        assertEquals(CaptureDecision.STARTED, gate.accept(800, 1_600))
        repeat(9) { assertEquals(CaptureDecision.RECORDING, gate.accept(0, 1_600)) }
        assertEquals(CaptureDecision.COMPLETE, gate.accept(0, 1_600))
    }

    @Test fun givesUpWhenNoSpeechArrives() {
        val gate = VoiceCaptureGate()
        repeat(199) { assertEquals(CaptureDecision.WAITING, gate.accept(0, 1_600)) }
        assertEquals(CaptureDecision.NO_SPEECH, gate.accept(0, 1_600))
    }

    @Test fun boundsLongUtterance() {
        val gate = VoiceCaptureGate()
        gate.accept(800, 1_600)
        gate.accept(800, 1_600)
        repeat(248) { gate.accept(800, 1_600) }
        assertEquals(CaptureDecision.MAX_LENGTH, gate.accept(800, 1_600))
    }
}
