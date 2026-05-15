package com.navertraffic.samsung.strategy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungBrowserStrategyATest {
    @Test
    fun dryRunSessionRecordsStrategyAUrlSequence() = runBlocking {
        val logs = mutableListOf<String>()
        val strategy = SamsungBrowserStrategyA(
            browserSession = DryRunBrowserSession(logs::add),
            stepDelayMs = 0,
            productDelayMs = 0,
            useMidClick = false,
        )

        val success = strategy.run(
            StrategyATask(
                keyword = "양꼬치 양갈비 양고기",
                secondKeyword = "최상급 어린양으로 만든 양꼬치 양갈비 양고기",
                linkUrl = "https://msearch.shopping.naver.com/product/82095489871",
                mid = "82095489871",
                productTitle = "최상급 어린양으로 만든 양꼬치 양갈비 양고기",
            ),
            logs::add,
        )

        assertTrue(success)
        assertTrue(logs.count { it.startsWith("DRY_RUN URL 기록:") } >= 3)
        assertTrue(logs.any { it.contains("query=%EC%96%91%EA%BC%AC%EC%B9%98") })
        assertTrue(logs.any { it.contains("product/82095489871") })
    }
}
