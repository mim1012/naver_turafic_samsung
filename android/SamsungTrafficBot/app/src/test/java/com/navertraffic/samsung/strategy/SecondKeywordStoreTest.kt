package com.navertraffic.samsung.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondKeywordStoreTest {
    @Test
    fun generateCombinationsReturnsRequestedCount() {
        val combos = SecondKeywordStore.generateCombinations(
            source = "최상급 어린양으로 만든 양꼬치 양갈비 양고기",
            count = 5,
        )

        assertEquals(5, combos.size)
        assertTrue(combos.all { it.isNotBlank() })
    }

    @Test
    fun generateCombinationsIncludesRequiredWord() {
        val combos = SecondKeywordStore.generateCombinations(
            source = "최상급 어린양으로 만든 양꼬치 양갈비 양고기",
            requiredWord = "양꼬치",
            count = 5,
        )

        assertEquals(5, combos.size)
        assertTrue(combos.all { it.contains("양꼬치") })
    }

    @Test
    fun generateCombinationsFallsBackWhenSourceIsTooShort() {
        val combos = SecondKeywordStore.generateCombinations(
            source = "양꼬치 양갈비 양고기",
            count = 5,
        )

        assertEquals(5, combos.size)
        assertTrue(combos.all { it.isNotBlank() })
    }

    @Test
    fun buildGIntegratedFiveWordQueryKeepsFiveWords() {
        val query = SecondKeywordStore.buildGIntegratedFiveWordQuery(
            mainKeyword = "나이키 운동화",
            secondaryText = "나이키 에어맥스 운동화 스니커즈 남성 여성 편한 신발",
        )

        val words = query.split(Regex("\\s+")).filter { it.isNotBlank() }

        assertEquals(5, words.size)
        assertTrue(words.any { it == "나이키" || it == "운동화" })
    }

    @Test
    fun boundedMidPhraseMemoryEvictsOldPhrasesAndMids() {
        val memory = BoundedMidPhraseMemory(maxMids = 2, maxPhrasesPerMid = 2)

        assertEquals(1, memory.rememberMiss("mid-1", "phrase-1"))
        assertEquals(2, memory.rememberMiss("mid-1", "phrase-2"))
        assertEquals(3, memory.rememberMiss("mid-1", "phrase-3"))
        memory.rememberMiss("mid-2", "phrase-4")
        memory.rememberMiss("mid-3", "phrase-5")

        assertEquals(0, memory.missCount("mid-1"))
        assertEquals(setOf("phrase-4"), memory.failedPhrases("mid-2"))
        assertEquals(setOf("phrase-5"), memory.failedPhrases("mid-3"))
    }
}
