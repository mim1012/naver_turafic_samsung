package com.navertraffic.samsung.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondKeywordStoreTest {
    @Test
    fun generateCandidatesPermutesFirstFiveWords() {
        val candidates = SecondKeywordStore.generateCandidates("최상급 어린양으로 만든 양꼬치 양갈비 양고기")

        assertEquals(120, candidates.size)
        assertEquals("최상급 어린양으로 만든 양꼬치 양갈비", candidates.first())
        assertTrue(candidates.contains("양갈비 양꼬치 만든 어린양으로 최상급"))
    }

    @Test
    fun generateCandidatesKeepsShortPhrase() {
        val candidates = SecondKeywordStore.generateCandidates("양꼬치 양갈비 양고기")

        assertEquals(listOf("양꼬치 양갈비 양고기"), candidates)
    }
}
