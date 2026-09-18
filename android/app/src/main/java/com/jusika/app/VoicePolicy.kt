package com.jusika.app

/** Only final, locally transcribed utterances enter this policy. No audio leaves the phone. */
object VoicePolicy {
    private val wake = Regex("^\\s*주식\\s*아(?:[\\s,.!?]|$)")
    fun wakeCommand(text: String): String? {
        val match = wake.find(text) ?: return null
        return text.substring(match.range.last + 1).trim()
    }
    private fun compact(text: String) = text.replace(Regex("[\\s,.!?]"), "")
    fun normalize(text: String) = text.trim().replace(Regex("\\s+"), " ")
    fun approves(text: String) = compact(text) == "승인"
    fun ambiguousApproval(text: String) = compact(text) in setOf("네", "예", "응", "주문해", "진행해")
    fun cancels(text: String) = compact(text) in setOf("취소", "취소해", "취소해줘", "아니", "아니요", "안해", "하지마")
    fun endsConversation(text: String) = compact(text) in setOf(
        "그만", "그만해", "종료", "종료해", "끝", "대화종료", "대화끝내줘",
    )
    fun disablesStandby(text: String) = compact(text) in setOf(
        "대기기능꺼줘", "대기꺼줘", "마이크꺼줘", "완전히종료해",
    )
}
