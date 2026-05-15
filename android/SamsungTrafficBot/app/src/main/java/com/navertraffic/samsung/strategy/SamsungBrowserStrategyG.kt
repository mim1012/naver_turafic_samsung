package com.navertraffic.samsung.strategy

import java.net.URLEncoder
import kotlin.random.Random
import kotlinx.coroutines.delay

class SamsungBrowserStrategyG(
    private val browserSession: BrowserSession,
    private val webViewManager: SamsungWebViewManager? = null,
    private val protectionDetector: ProtectionDetector = ProtectionDetector(),
    private val explorationScrollCount: Int = 4,
    private val explorationScrollPixels: Int = 500,
) {
    constructor(
        webViewManager: SamsungWebViewManager,
        protectionDetector: ProtectionDetector = ProtectionDetector(),
    ) : this(WebViewBrowserSession(webViewManager), webViewManager, protectionDetector)

    suspend fun runDetailed(task: StrategyGTask, log: (String) -> Unit): StrategyAResult {
        task.validate()?.let {
            log("INVALID_TASK: $it")
            return StrategyAResult(success = false, lastUrl = browserSession.currentUrl(), message = it)
        }

        if (!browserSession.supportsPageInspection) {
            log("외부 Samsung Internet 모드: 보호 문구 자동 감지는 건너뜀")
        }

        webViewManager?.setBrowserMode(isChrome = true)
        log("UA: Chrome 137 모드 적용")

        log("초기 랜딩: $AI_REWARD_LANDING_URL")
        browserSession.loadAndWait(AI_REWARD_LANDING_URL, 8_000)
        delay(Random.nextLong(800, 1_500))

        log("네이버 홈 진입")
        browserSession.loadAndWait(NAVER_HOME_URL, 15_000)
        delay(Random.nextLong(800, 1_500))

        // 1차 검색 — 검색만, MID 클릭 없음
        // URL 직접 진입: 검색바 submit은 느린 기기에서 타이밍 불안정
        log("1차 검색: ${task.keyword}")
        browserSession.simulateAutocomplete(task.keyword)
        delay(Random.nextLong(600, 1_200))
        browserSession.loadAndWait(buildNaverSearchUrl(task.keyword), 30_000)
        delay(Random.nextLong(2_500, 4_000))

        log("1차 탐색 스크롤 (${explorationScrollCount}회 × ${explorationScrollPixels}px)")
        repeat(explorationScrollCount) {
            browserSession.scrollBy(explorationScrollPixels)
            delay(Random.nextLong(350, 700))
        }
        detectProtection(log)?.let {
            webViewManager?.setBrowserMode(isChrome = false)
            return it
        }

        // 2차 검색 — keywordName으로 검색 후 MID 탐색
        log("2차 검색: ${task.keywordName}")
        browserSession.simulateAutocomplete(task.keywordName)
        delay(Random.nextLong(600, 1_200))
        browserSession.loadAndWait(buildNaverSearchUrl(task.keywordName), 30_000)
        delay(Random.nextLong(2_500, 4_000))

        // 탐색 스크롤하며 MID 탐색
        log("2차 MID 탐색: ${task.mid}")
        var clicked = false
        repeat(explorationScrollCount) {
            if (!clicked) {
                clicked = browserSession.clickMidLink(task.mid, task.productTitle ?: task.keywordName)
            }
            if (!clicked) {
                browserSession.scrollBy(explorationScrollPixels)
                delay(Random.nextLong(350, 700))
            }
        }
        if (!clicked) {
            clicked = browserSession.clickMidLink(task.mid, task.productTitle ?: task.keywordName)
        }

        if (!clicked) {
            webViewManager?.setBrowserMode(isChrome = false)
            return StrategyAResult(
                success = false,
                lastUrl = browserSession.currentUrl(),
                message = "MID product not found after exploration: ${task.mid}",
            )
        }

        log("상세 페이지 진입 대기")
        delay(Random.nextLong(4_500, 9_000))
        detectProtection(log)?.let {
            webViewManager?.setBrowserMode(isChrome = false)
            return it
        }

        // 오실레이션 스크롤: 아래→위→아래
        log("오실레이션 스크롤")
        browserSession.scrollBy(800)
        delay(Random.nextLong(600, 1_200))
        browserSession.scrollBy(-400)
        delay(Random.nextLong(600, 1_200))
        browserSession.scrollBy(600)
        delay(Random.nextLong(1_000, 2_000))

        val finalUrl = browserSession.currentUrl()

        log("브라우저 닫기 → 네이버 메인홈")
        browserSession.resetSurface()
        delay(Random.nextLong(400, 900))
        browserSession.loadAndWait(NAVER_HOME_URL, 15_000)
        delay(Random.nextLong(2_000, 4_000))

        webViewManager?.setBrowserMode(isChrome = false)
        return StrategyAResult(success = true, lastUrl = finalUrl)
    }

    private fun buildNaverSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return "https://m.search.naver.com/search.naver?query=$encoded&where=m"
    }

    private suspend fun detectProtection(log: (String) -> Unit): StrategyAResult? {
        if (!browserSession.supportsPageInspection) return null
        val signals = protectionDetector.detectFromText(browserSession.visibleText())
        if (signals.isEmpty()) return null
        log("보호/재인증 신호 감지: ${signals.joinToString()}")
        return StrategyAResult(
            success = false,
            signals = signals,
            lastUrl = browserSession.currentUrl(),
            message = "protection signal detected",
        )
    }

    companion object {
        const val NAVER_HOME_URL = "https://m.naver.com/"
        const val AI_REWARD_LANDING_URL = "https://snsz.kr"
    }
}
