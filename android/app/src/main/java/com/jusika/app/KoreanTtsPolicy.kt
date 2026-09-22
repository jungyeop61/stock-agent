package com.jusika.app

import java.util.Locale

/** Engine-independent policy. Unknown or network-only voice metadata never enables standby. */
data class KoreanTtsVoice(
    val name: String,
    val language: String,
    val country: String = "",
    val requiresNetwork: Boolean = false,
    val installed: Boolean? = true,
)

enum class KoreanTtsFailure(val message: String) {
    METADATA_UNAVAILABLE("음성 엔진에서 음성 정보를 확인하지 못했습니다. 엔진 설정을 확인해주세요."),
    NO_KOREAN_VOICE("음성 엔진에서 한국어 음성을 찾지 못했습니다. 한국어 음성 설정을 확인해주세요."),
    NETWORK_ONLY("음성 엔진이 한국어 음성을 온라인 전용으로 보고했습니다. 오프라인 음성 설정을 확인해주세요."),
    DATA_MISSING("음성 엔진이 오프라인 한국어 음성을 미설치로 보고했습니다. 음성 데이터 설치 상태를 확인해주세요."),
    INSTALL_STATE_UNKNOWN("한국어 음성의 설치 상태를 확인하지 못했습니다. 음성 엔진 설정을 확인해주세요."),
    SELECTION_FAILED("오프라인 한국어 음성은 있지만 선택하지 못했습니다. 음성 엔진 설정을 확인해주세요."),
}

sealed class KoreanTtsResult {
    data class Ready(val voice: KoreanTtsVoice) : KoreanTtsResult()
    data class Failed(val reason: KoreanTtsFailure, val koreanCount: Int, val usableCount: Int) : KoreanTtsResult()
}

object KoreanTtsPolicy {
    fun isKorean(voice: KoreanTtsVoice) = voice.language.lowercase(Locale.ROOT) in setOf("ko", "kor")
    fun isUsable(voice: KoreanTtsVoice) = isKorean(voice) && !voice.requiresNetwork && voice.installed == true

    fun select(
        current: KoreanTtsVoice?,
        available: List<KoreanTtsVoice>,
        activate: (KoreanTtsVoice) -> KoreanTtsVoice?,
    ): KoreanTtsResult {
        // A verified current offline voice needs no setVoice call (some engines reject re-selection).
        if (current != null && isUsable(current)) return KoreanTtsResult.Ready(current)
        val known = (listOfNotNull(current) + available).distinctBy { it.name }
        val korean = known.filter(::isKorean)
        val usable = korean.filter(::isUsable).sortedWith(
            compareBy<KoreanTtsVoice> { if (it.country.uppercase(Locale.ROOT) in setOf("KR", "KOR")) 0 else 1 }
                .thenBy { it.name },
        )
        for (candidate in usable) {
            val actual = runCatching { activate(candidate) }.getOrNull()
            // Successful selection alone is insufficient: verify the engine's actual selected voice.
            if (actual != null && isUsable(actual)) return KoreanTtsResult.Ready(actual)
        }
        val reason = when {
            known.isEmpty() -> KoreanTtsFailure.METADATA_UNAVAILABLE
            korean.isEmpty() -> KoreanTtsFailure.NO_KOREAN_VOICE
            korean.all { it.requiresNetwork } -> KoreanTtsFailure.NETWORK_ONLY
            usable.isNotEmpty() -> KoreanTtsFailure.SELECTION_FAILED
            korean.any { !it.requiresNetwork && it.installed == null } -> KoreanTtsFailure.INSTALL_STATE_UNKNOWN
            else -> KoreanTtsFailure.DATA_MISSING
        }
        return KoreanTtsResult.Failed(reason, korean.size, usable.size)
    }
}
