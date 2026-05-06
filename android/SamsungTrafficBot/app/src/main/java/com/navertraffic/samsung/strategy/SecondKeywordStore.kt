package com.navertraffic.samsung.strategy

import android.content.Context

class SecondKeywordStore(context: Context) {
    private val prefs = context.getSharedPreferences("second_keyword_exclusions", Context.MODE_PRIVATE)

    fun nextKeyword(mid: String, source: String): String? {
        val excluded = excludedKeywords(mid)
        return generateCandidates(source).firstOrNull { it !in excluded }
    }

    fun exclude(mid: String, keyword: String) {
        if (mid.isBlank() || keyword.isBlank()) return
        val updated = excludedKeywords(mid).toMutableSet()
        updated.add(keyword)
        prefs.edit().putStringSet(key(mid), updated).apply()
    }

    fun excludedCount(mid: String): Int = excludedKeywords(mid).size

    private fun excludedKeywords(mid: String): Set<String> {
        return prefs.getStringSet(key(mid), emptySet()).orEmpty()
    }

    private fun key(mid: String): String = "mid:$mid"

    companion object {
        fun generateCandidates(source: String): List<String> {
            val words = source
                .split(Regex("\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(5)
            if (words.isEmpty()) return emptyList()
            if (words.size < 5) return listOf(words.joinToString(" "))

            val out = mutableListOf<String>()
            fun permute(prefix: List<String>, remaining: List<String>) {
                if (remaining.isEmpty()) {
                    out.add(prefix.joinToString(" "))
                    return
                }
                remaining.forEachIndexed { index, word ->
                    permute(prefix + word, remaining.filterIndexed { i, _ -> i != index })
                }
            }
            permute(emptyList(), words)
            return out
        }
    }
}
