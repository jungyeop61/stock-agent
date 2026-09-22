package com.jusika.app

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Uses only Android voice metadata; never probes online synthesis or sends any audio. */
object OfflineKoreanTts {
    fun configure(engine: TextToSpeech): KoreanTtsResult {
        fun current() = runCatching { describe(engine.voice) }.getOrNull()
        var selected = current()
        if (selected != null && KoreanTtsPolicy.isUsable(selected)) return KoreanTtsResult.Ready(selected)
        // Region-specific Korean first. setLanguage resets the current voice, so never do it above.
        for (locale in listOf(Locale.KOREA, Locale.KOREAN)) {
            val supported = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.ERROR)
            if (supported >= TextToSpeech.LANG_AVAILABLE) {
                selected = current()
                if (selected != null && KoreanTtsPolicy.isUsable(selected)) return KoreanTtsResult.Ready(selected)
            }
        }
        val voices = runCatching { engine.voices?.toList().orEmpty() }.getOrDefault(emptyList())
        val described = voices.mapNotNull { runCatching { describe(it) }.getOrNull() }
        return KoreanTtsPolicy.select(current(), described) { candidate ->
            val native = voices.firstOrNull { it.name == candidate.name }
            if (native != null && engine.setVoice(native) == TextToSpeech.SUCCESS) current() else null
        }
    }

    private fun describe(voice: Voice?): KoreanTtsVoice? {
        if (voice == null) return null
        val features = voice.features
        return KoreanTtsVoice(
            name = voice.name,
            language = voice.locale.language,
            country = voice.locale.country,
            requiresNetwork = voice.isNetworkConnectionRequired,
            installed = features?.let { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it },
        )
    }
}
