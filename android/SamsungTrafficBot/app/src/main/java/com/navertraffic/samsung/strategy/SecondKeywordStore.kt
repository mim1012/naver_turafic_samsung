package com.navertraffic.samsung.strategy

class SecondKeywordStore {
    private fun key(mid: String): String = "mid:$mid"

    companion object {
        val DEFAULT_TAIL_KEYWORDS = listOf("추천", "할인", "가격비교", "인기", "후기")

        /**
         * 상품명에서 (5 - requiredWords.size)개 랜덤 + requiredWord(1차 키워드 전체) 포함 = 5단어.
         * 꼬리키워드 없음. requiredWord가 null이면 pool에서 5단어 전부 뽑음.
         */
        fun generateCombinations(
            source: String,
            requiredWord: String? = null,
            tailKeywords: List<String> = DEFAULT_TAIL_KEYWORDS,
            count: Int = 5,
        ): List<String> {
            val seen = LinkedHashSet<String>()
            val result = mutableListOf<String>()

            val requiredTokens = requiredWord
                ?.split(Regex("\\s+"))?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()

            val pool = source.split(Regex("\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .filter { it !in requiredTokens }

            val pickCount = (5 - requiredTokens.size).coerceAtLeast(1)

            if (pool.size < pickCount) {
                val base = (pool + requiredTokens).distinct()
                val combo = base.joinToString(" ")
                seen.add(combo); result.add(combo)
                return List(count) { i -> result[i % result.size] }
            }

            var attempts = 0
            while (result.size < count && attempts++ < count * 20) {
                val picked = pool.shuffled().take(pickCount)
                val words = picked + requiredTokens
                val combo = words.joinToString(" ")
                if (seen.add(combo)) result.add(combo)
            }
            return List(count) { i -> result[i % result.size] }
        }

        /**
         * GUI G 전략과 맞춘 통합검색 5단어 조합.
         * 1차 키워드에서 1단어 + 상품명/2차 텍스트에서 4단어를 뽑는다.
         */
        fun buildGIntegratedFiveWordQuery(
            mainKeyword: String,
            secondaryText: String,
        ): String {
            val mainWords = tokenize(mainKeyword)
            val mainPick = mainWords.randomOrNull() ?: "상품"
            var pool = tokenize(secondaryText)
                .distinct()
                .filter { it != mainPick }
            if (pool.isEmpty()) pool = tokenize(secondaryText).distinct()
            if (pool.isEmpty()) pool = listOf(mainPick)

            val shuffled = pool.shuffled()
            val four = List(4) { index -> shuffled[index % shuffled.size] }
            return (listOf(mainPick) + four).joinToString(" ")
        }

        private fun tokenize(text: String): List<String> {
            return text
                .replace(Regex("\\s+"), " ")
                .trim()
                .split(" ")
                .filter { it.isNotBlank() }
        }
    }
}
