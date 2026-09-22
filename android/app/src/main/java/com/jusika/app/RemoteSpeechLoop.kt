package com.jusika.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/** Captures one bounded utterance after an explicit app launch; it never performs wake-word standby. */
class RemoteSpeechLoop(
    private val permissionCheck: () -> Int,
    private val main: Handler,
    private val onReady: () -> Unit,
    private val onAudio: (ByteArray) -> Unit,
    private val onNoSpeech: () -> Unit,
    private val onFailure: () -> Unit,
) {
    private val running = AtomicBoolean(true)
    private val enabled = AtomicBoolean(false)
    private val revision = AtomicLong(0)
    @Volatile private var recorder: AudioRecord? = null
    private val thread = Thread({ record() }, "jusika-remote-speech")

    fun start() = thread.start()
    fun enable(value: Boolean) {
        enabled.set(value)
        revision.incrementAndGet()
    }
    fun close() {
        running.set(false)
        enable(false)
        runCatching { recorder?.stop() }
        thread.interrupt()
    }

    @SuppressLint("MissingPermission")
    private fun record() {
        if (permissionCheck() != PackageManager.PERMISSION_GRANTED) {
            main.post(onFailure)
            return
        }
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBuffer > 0)
            val audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_SAMPLES * 4),
            )
            recorder = audio
            try {
                check(audio.state == AudioRecord.STATE_INITIALIZED)
                audio.startRecording()
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                main.post { if (running.get()) onReady() }
                val buffer = ShortArray(FRAME_SAMPLES)
                var currentRevision = revision.get()
                var gate = VoiceCaptureGate()
                var started = false
                var pcm = ByteArrayOutputStream()
                val preRoll = ArrayDeque<ShortArray>()
                while (running.get()) {
                    val read = audio.read(buffer, 0, buffer.size)
                    if (!running.get()) break
                    check(read > 0)
                    val observedRevision = revision.get()
                    if (observedRevision != currentRevision) {
                        currentRevision = observedRevision
                        gate = VoiceCaptureGate()
                        started = false
                        pcm = ByteArrayOutputStream()
                        preRoll.clear()
                    }
                    if (!enabled.get()) continue
                    val frame = buffer.copyOf(read)
                    val decision = gate.accept(rms(frame), read)
                    if (!started) {
                        preRoll.addLast(frame)
                        while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                    }
                    if (decision == CaptureDecision.STARTED) {
                        started = true
                        for (part in preRoll) appendPcm(pcm, part)
                        preRoll.clear()
                    } else if (started) {
                        appendPcm(pcm, frame)
                    }
                    when (decision) {
                        CaptureDecision.COMPLETE, CaptureDecision.MAX_LENGTH -> {
                            enabled.set(false)
                            val capturedRevision = currentRevision
                            val samples = bytesToShorts(pcm.toByteArray())
                            val wav = PcmWave.encode(samples)
                            main.post {
                                if (running.get() && revision.get() == capturedRevision) onAudio(wav)
                            }
                        }
                        CaptureDecision.NO_SPEECH -> {
                            enabled.set(false)
                            val capturedRevision = currentRevision
                            main.post {
                                if (running.get() && revision.get() == capturedRevision) onNoSpeech()
                            }
                        }
                        else -> Unit
                    }
                }
            } finally {
                runCatching { audio.stop() }
                audio.release()
                recorder = null
            }
        } catch (_: Exception) {
            if (running.get()) main.post { if (running.get()) onFailure() }
        }
    }

    private fun rms(samples: ShortArray): Int {
        if (samples.isEmpty()) return 0
        var sum = 0.0
        for (sample in samples) sum += sample.toDouble() * sample.toDouble()
        return sqrt(sum / samples.size).toInt()
    }

    private fun appendPcm(output: ByteArrayOutputStream, samples: ShortArray) {
        for (sample in samples) {
            output.write(sample.toInt() and 0xff)
            output.write((sample.toInt() ushr 8) and 0xff)
        }
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        return ShortArray(bytes.size / 2) { index ->
            val low = bytes[index * 2].toInt() and 0xff
            val high = bytes[index * 2 + 1].toInt()
            ((high shl 8) or low).toShort()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 1_600
        private const val PRE_ROLL_FRAMES = 5
    }
}
