package com.jusika.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One microphone / recognizer owner. Disabled audio is discarded, not transcribed or buffered. */
class OfflineSpeechLoop(
    private val modelDir: File,
    private val main: Handler,
    private val onReady: () -> Unit,
    private val onText: (String) -> Unit,
    private val onFailure: () -> Unit,
) {
    private val running = AtomicBoolean(true)
    private val enabled = AtomicBoolean(false)
    private val revision = AtomicLong(0)
    @Volatile private var recorder: AudioRecord? = null
    private val thread = Thread({ record() }, "jusika-offline-speech")

    fun start() = thread.start()
    fun enable(value: Boolean) {
        enabled.set(value)
        revision.incrementAndGet() // Invalidate queued results and reset prior acoustic context.
    }
    fun close() {
        running.set(false)
        enable(false)
        runCatching { recorder?.stop() } // Unblock read; only worker thread releases native objects.
        thread.interrupt()
    }

    @SuppressLint("MissingPermission") // Service starts only after runtime permission is granted.
    private fun record() {
        try {
            Model(modelDir.absolutePath).use { model ->
                if (!running.get()) return
                Recognizer(model, 16_000f).use { recognizer ->
                    val minBuffer = AudioRecord.getMinBufferSize(
                        16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    )
                    check(minBuffer > 0)
                    val audio = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuffer, 6_400),
                    )
                    recorder = audio
                    try {
                        check(audio.state == AudioRecord.STATE_INITIALIZED)
                        if (!running.get()) return
                        audio.startRecording()
                        check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                        main.post { if (running.get()) onReady() }
                        val buffer = ShortArray(3_200)
                        var lastRevision = revision.get()
                        while (running.get()) {
                            val currentRevision = revision.get()
                            val read = audio.read(buffer, 0, buffer.size)
                            if (!running.get()) break
                            check(read > 0)
                            if (revision.get() != currentRevision || !enabled.get()) continue
                            if (lastRevision != currentRevision) {
                                recognizer.reset()
                                lastRevision = currentRevision
                            }
                            if (recognizer.acceptWaveForm(buffer, read)) {
                                val text = JSONObject(recognizer.result).optString("text").trim()
                                if (text.isNotEmpty()) main.post {
                                    if (running.get() && enabled.get() && revision.get() == currentRevision) {
                                        onText(text)
                                    }
                                }
                            }
                        }
                    } finally {
                        runCatching { audio.stop() }
                        audio.release()
                        recorder = null
                    }
                }
            }
        } catch (_: Exception) {
            if (running.get()) main.post { if (running.get()) onFailure() }
        }
    }
}
