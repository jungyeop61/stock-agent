package com.jusika.app

/** Preserve every word of an approval preview instead of truncating it to a TTS limit. */
object SpeechChunks {
    fun split(text: String, limit: Int): List<String> {
        require(limit >= 2 && text.isNotBlank())
        val chunks = mutableListOf<String>()
        var remaining = text
        while (remaining.length > limit) {
            var cut = remaining.lastIndexOfAny(charArrayOf('\n', ' ', '.', '!', '?'), limit - 1) + 1
            if (cut < limit / 2) cut = limit
            if (Character.isHighSurrogate(remaining[cut - 1])) cut--
            chunks.add(remaining.substring(0, cut))
            remaining = remaining.substring(cut)
        }
        if (remaining.isNotEmpty()) chunks.add(remaining)
        return chunks
    }
}
