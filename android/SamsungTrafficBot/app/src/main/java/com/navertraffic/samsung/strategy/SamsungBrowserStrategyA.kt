package com.navertraffic.samsung.strategy

import com.navertraffic.samsung.data.ProtectionSignal
import kotlinx.coroutines.delay
import java.net.URLEncoder

data class StrategyAResult(
    val success: Boolean,
    val signals: List<ProtectionSignal> = emptyList(),
    val lastUrl: String? = null,
    val message: String? = null,
)

class SamsungBrowserStrategyA(
    private val browserSession: BrowserSession,
    private val protectionDetector: ProtectionDetector = ProtectionDetector(),
    private val stepDelayMs: Long = 3_000,
    private val productDelayMs: Long = 5_000,
    private val useMidClick: Boolean = true,
    private val detailSwipeMs: Long = 2_000,
) {
    constructor(
        webViewManager: SamsungWebViewManager,
        protectionDetector: ProtectionDetector = ProtectionDetector(),
    ) : this(WebViewBrowserSession(webViewManager), protectionDetector)

    suspend fun run(task: StrategyATask, log: (String) -> Unit): Boolean {
        return runDetailed(task, log).success
    }

    suspend fun runDetailed(task: StrategyATask, log: (String) -> Unit): StrategyAResult {
        task.validate()?.let {
            log("INVALID_TASK: $it")
            return StrategyAResult(
                success = false,
                lastUrl = browserSession.currentUrl(),
                message = it,
            )
        }

        if (!browserSession.supportsPageInspection) {
            log("외부 Samsung Internet 모드: 보호 문구 자동 감지는 건너뜀")
        }

        log("1차 검색: ${task.keyword}")
        browserSession.loadAndWait(buildNaverSearchUrl(task.keyword))
        delay(stepDelayMs)
        detectProtection(log)?.let { return it }

        log("2차 검색: ${task.secondKeyword}")
        browserSession.loadAndWait(buildNaverSearchUrl(task.secondKeyword))
        delay(stepDelayMs)
        detectProtection(log)?.let { return it }

        val mid = task.mid.orEmpty()
        if (useMidClick && mid.isNotBlank()) {
            log("MID 상품 터치 진입: $mid")
            val clicked = browserSession.clickMidLink(mid, task.productTitle ?: task.secondKeyword)
            if (!clicked) {
                return StrategyAResult(
                    success = false,
                    lastUrl = browserSession.currentUrl(),
                    message = "MID product not found: $mid",
                )
            }
        } else {
            log("상품 진입: ${task.linkUrl}")
            browserSession.loadAndWait(task.linkUrl)
        }
        delay(productDelayMs)
        browserSession.swipeDetail(detailSwipeMs)
        detectProtection(log)?.let { return it }
        return StrategyAResult(success = true, lastUrl = browserSession.currentUrl())
    }

    private fun buildNaverSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return "https://m.search.naver.com/search.naver?where=m&query=$encoded"
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
            message = "protection or re-authentication signal detected",
        )
    }
}
