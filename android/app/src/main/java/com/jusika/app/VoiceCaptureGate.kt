package com.jusika.app

enum class CaptureDecision { WAITING, STARTED, RECORDING, COMPLETE, NO_SPEECH, MAX_LENGTH }

/** Deterministic local VAD. Audio leaves the phone only after speech begins and then ends. */
class VoiceCaptureGate(
    private val sampleRate: Int = 16_000,
    private val speechThreshold: Int = 400,
    private val startFrames: Int = 2,
    private val endSilenceMs: Int = 1_000,
    private val noSpeechMs: Int = 20_000,
    private val maxSpeechMs: Int = 25_000,
) {
    private var totalSamples = 0L
    private var speechSamples = 0L
    private var quietSamples = 0L
    private var loudFrames = 0
    private var started = false

    fun accept(level: Int, samples: Int): CaptureDecision {
        require(level >= 0 && samples > 0)
        totalSamples += samples
        if (!started) {
            loudFrames = if (level >= speechThreshold) loudFrames + 1 else 0
            if (loudFrames >= startFrames) {
                started = true
                speechSamples = samples.toLong()
                return CaptureDecision.STARTED
            }
            return if (millis(totalSamples) >= noSpeechMs) CaptureDecision.NO_SPEECH
            else CaptureDecision.WAITING
        }
        speechSamples += samples
        quietSamples = if (level < speechThreshold) quietSamples + samples else 0
        return when {
            millis(speechSamples) >= maxSpeechMs -> CaptureDecision.MAX_LENGTH
            millis(quietSamples) >= endSilenceMs -> CaptureDecision.COMPLETE
            else -> CaptureDecision.RECORDING
        }
    }

    private fun millis(samples: Long): Long = samples * 1_000 / sampleRate
}
