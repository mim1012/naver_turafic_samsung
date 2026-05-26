package com.navertraffic.samsung.strategy

internal class BoundedMidPhraseMemory(
    private val maxMids: Int = 128,
    private val maxPhrasesPerMid: Int = 20,
) {
    private val entries = linkedMapOf<String, Entry>()

    fun missCount(mid: String): Int {
        return entries[mid.trim()]?.missCount ?: 0
    }

    fun failedPhrases(mid: String): Set<String> {
        return entries[mid.trim()]?.phrases?.toSet().orEmpty()
    }

    fun rememberMiss(mid: String, phrase: String): Int {
        val key = mid.trim()
        val normalizedPhrase = phrase.trim()
        if (key.isBlank() || normalizedPhrase.isBlank()) return missCount(key)

        val entry = entries.remove(key) ?: Entry()
        entry.missCount += 1
        entry.phrases.add(normalizedPhrase)
        while (entry.phrases.size > maxPhrasesPerMid) {
            val first = entry.phrases.firstOrNull() ?: break
            entry.phrases.remove(first)
        }
        entries[key] = entry

        while (entries.size > maxMids) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
        return entry.missCount
    }

    private class Entry {
        var missCount: Int = 0
        val phrases = linkedSetOf<String>()
    }
}
