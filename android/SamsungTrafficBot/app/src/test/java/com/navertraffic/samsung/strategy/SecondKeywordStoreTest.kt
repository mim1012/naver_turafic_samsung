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
}
