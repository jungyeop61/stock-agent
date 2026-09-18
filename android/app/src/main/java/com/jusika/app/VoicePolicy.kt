package com.jusika.app

/** Only final, locally transcribed utterances enter this policy. No audio leaves the phone. */
object VoicePolicy {
    private val wake = Regex("^\\s*주식\\s*아(?:[\\s,.!?]|$)")
    private val price = Regex(
        "^([가-힣A-Za-z0-9.]{1,30})\\s*(?:현재가|현재\\s*가격|주가|가격|시세)\\s*" +
            "(?:알려\\s*줘|알려\\s*주세요|조회(?:해\\s*줘)?|얼마(?:야|예요)?|어때)?[.!?]*$",
    )
    fun wakeCommand(text: String): String? {
        val match = wake.find(text) ?: return null
        return text.substring(match.range.last + 1).trim()
    }
    private fun compact(text: String) = text.replace(Regex("[\\s,.!?]"), "")
    fun endsConversation(text: String) = compact(text) in setOf(
        "그만", "그만해", "종료", "종료해", "끝", "대화종료", "대화끝내줘",
    )
    fun disablesStandby(text: String) = compact(text) in setOf(
        "대기기능꺼줘", "대기꺼줘", "마이크꺼줘", "완전히종료해",
    )
    /** Construct a new read-only command, never forward arbitrary recognized words. */
    fun priceCommand(text: String): String? {
        val match = price.matchEntire(text.trim()) ?: return null
        return "${match.groupValues[1]} 현재가 알려줘"
    }
}
