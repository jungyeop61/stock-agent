package com.jusika.app

import org.junit.Assert.*
import org.junit.Test

class KoreanTtsPolicyTest {
    private fun voice(name: String = "korean", language: String = "ko", country: String = "KR",
                      network: Boolean = false, installed: Boolean? = true) =
        KoreanTtsVoice(name, language, country, network, installed)

    @Test fun keepsCurrentOfflineKoreanVoiceWithoutReselectingOrNeedingVoiceList() {
        val current = voice()
        val result = KoreanTtsPolicy.select(current, emptyList()) { fail("Must not reselect current"); null }
        assertEquals(KoreanTtsResult.Ready(current), result)
    }
    @Test fun acceptsTwoAndThreeLetterKoreanCodesOnly() {
        for (language in listOf("ko", "kor", "KO", "KOR")) assertTrue(KoreanTtsPolicy.isUsable(voice(language = language)))
        for (language in listOf("en", "ja", "korean", "")) assertFalse(KoreanTtsPolicy.isUsable(voice(language = language)))
    }
    @Test fun rejectsCurrentOnlineMissingOrUnknownVoiceAndSelectsOfflineAlternative() {
        val offline = voice("offline")
        for (current in listOf(voice("online", network = true), voice("missing", installed = false),
                               voice("unknown", installed = null), voice("english", language = "en"))) {
            val attempts = mutableListOf<String>()
            val result = KoreanTtsPolicy.select(current, listOf(current, offline)) { attempts.add(it.name); it }
            assertEquals(KoreanTtsResult.Ready(offline), result)
            assertEquals(listOf("offline"), attempts)
        }
    }
    @Test fun triesNextVoiceWhenFirstVoiceSelectionFails() {
        val first = voice("1")
        val second = voice("2")
        val attempts = mutableListOf<String>()
        val result = KoreanTtsPolicy.select(null, listOf(first, second)) {
            attempts.add(it.name); if (it == first) null else it
        }
        assertEquals(KoreanTtsResult.Ready(second), result)
        assertEquals(listOf("1", "2"), attempts)
    }
    @Test fun selectionExceptionDoesNotPreventTryingAnotherVoice() {
        val first = voice("1")
        val second = voice("2")
        assertEquals(KoreanTtsResult.Ready(second), KoreanTtsPolicy.select(null, listOf(first, second)) {
            if (it == first) error("engine selection failure") else it
        })
    }
    @Test fun verifiesActualVoiceAndDoesNotAcceptSilentOnlineOrWrongLanguageFallback() {
        val available = listOf(voice("1"), voice("2"))
        for (actual in listOf(voice(network = true), voice(language = "en"), voice(installed = false),
                              voice(installed = null))) {
            val result = KoreanTtsPolicy.select(null, available) { actual } as KoreanTtsResult.Failed
            assertEquals(KoreanTtsFailure.SELECTION_FAILED, result.reason)
            assertEquals(2, result.usableCount)
        }
    }
    @Test fun regionSpecificKoreanIsTriedFirstIncludingThreeLetterCountry() {
        val attempts = mutableListOf<String>()
        KoreanTtsPolicy.select(null, listOf(voice("generic", country = ""), voice("three", country = "KOR"),
                                           voice("two", country = "KR"))) { attempts.add(it.name); null }
        assertEquals(listOf("three", "two", "generic"), attempts)
    }
    @Test fun duplicatesAreOnlyTriedOnce() {
        val candidate = voice()
        var attempts = 0
        KoreanTtsPolicy.select(null, listOf(candidate, candidate)) { attempts++; null }
        assertEquals(1, attempts)
    }
    @Test fun classifiesFailuresWithoutMislabelingEverythingAsMissingData() {
        val cases = listOf(
            emptyList<KoreanTtsVoice>() to KoreanTtsFailure.METADATA_UNAVAILABLE,
            listOf(voice(language = "en")) to KoreanTtsFailure.NO_KOREAN_VOICE,
            listOf(voice(network = true)) to KoreanTtsFailure.NETWORK_ONLY,
            listOf(voice(installed = false)) to KoreanTtsFailure.DATA_MISSING,
            listOf(voice(installed = null)) to KoreanTtsFailure.INSTALL_STATE_UNKNOWN,
            listOf(voice()) to KoreanTtsFailure.SELECTION_FAILED,
        )
        for ((voices, reason) in cases) {
            assertEquals(reason, (KoreanTtsPolicy.select(null, voices) { null } as KoreanTtsResult.Failed).reason)
        }
    }
    @Test fun neverTriesOnlineUninstalledOrUnknownCandidates() {
        val candidates = listOf(voice("online", network = true), voice("missing", installed = false),
                                voice("unknown", installed = null))
        val result = KoreanTtsPolicy.select(null, candidates) { fail("Unsafe candidate selected"); null }
        assertTrue(result is KoreanTtsResult.Failed)
    }
}
