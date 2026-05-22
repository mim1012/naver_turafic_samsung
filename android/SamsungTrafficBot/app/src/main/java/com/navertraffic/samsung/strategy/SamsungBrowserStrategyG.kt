package com.navertraffic.samsung.strategy

import com.navertraffic.samsung.data.CaptchaChallengeClient
import com.navertraffic.samsung.data.CaptchaChallengeRequest
import com.navertraffic.samsung.data.ProtectionSignal
import java.net.URLEncoder
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class SamsungBrowserStrategyG(
    private val browserSession: BrowserSession,
    private val webViewManager: SamsungWebViewManager? = null,
    private val protectionDetector: ProtectionDetector = ProtectionDetector(),
    private val explorationScrollCount: Int = 4,
    private val explorationScrollPixels: Int = 500,
    private val midExplorationTimeoutMs: Long = 30_000,
    private val deviceName: String = "",
    private val captchaChallengeClient: CaptchaChallengeClient? = null,
    private val screenshotCapture: ScreenshotCapture? = null,
    private val captchaChallengeTimeoutMs: Long = 5 * 60 * 1_000,
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
        log("UA: Chrome 138 모드 적용")

        log("네이버 홈 진입")
        browserSession.loadAndWait(NAVER_HOME_URL, 15_000)
        delay(Random.nextLong(800, 1_500))
        detectProtection(log)?.let {
            webViewManager?.setBrowserMode(isChrome = false)
            return it
        }
        detectRateLimit(log)?.let {
            webViewManager?.setBrowserMode(isChrome = false)
            return it
        }

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
        detectRateLimit(log)?.let {
            webViewManager?.setBrowserMode(isChrome = false)
            return it
        }

        // 2차 검색 — keywordName으로 검색 후 MID 탐색
        log("2차 검색: ${task.keywordName}")
        browserSession.simulateAutocomplete(task.keywordName)
        delay(Random.nextLong(600, 1_200))
        browserSession.loadAndWait(buildNaverSearchUrl(task.keywordName), 30_000)
        delay(Random.nextLong(2_500, 4_000))
        detectProtection(log)?.let {
            webViewManager?.setBrowserMode(isChrome = false)
            return it
        }
        detectRateLimit(log)?.let {
            webViewManager?.setBrowserMode(isChrome = false)
            return it
        }

        // 탐색 스크롤하며 MID 탐색
        log("2차 MID 탐색: ${task.mid}")
        val midExplorationResult = withTimeoutOrNull(midExplorationTimeoutMs) {
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
            clicked
        }
        val clicked = midExplorationResult == true

        if (!clicked) {
            val reason = if (midExplorationResult == null) "timeout" else "exploration"
            log("MID 미탐색: ${reason}, 다음 작업으로 이동")
            webViewManager?.setBrowserMode(isChrome = false)
            return StrategyAResult(
                success = false,
                lastUrl = browserSession.currentUrl(),
                message = "MID product not found after $reason: ${task.mid}",
            )
        }

        log("상세 페이지 진입 대기")
        delay(Random.nextLong(4_500, 9_000))
        detectProtection(log)?.let {
            webViewManager?.setBrowserMode(isChrome = false)
            return it
        }
        when (val detailStatus = browserSession.productDetailStatus(task.mid)) {
            ProductDetailStatus.DETAIL -> log("상세페이지 DOM 확인: ${task.mid}")
            ProductDetailStatus.RATE_LIMITED -> {
                log("429/요청 제한 감지: 작업 실패")
                webViewManager?.setBrowserMode(isChrome = false)
                return StrategyAResult(
                    success = false,
                    lastUrl = browserSession.currentUrl(),
                    message = "rate_limited_429",
                )
            }
            ProductDetailStatus.NOT_DETAIL, ProductDetailStatus.UNKNOWN -> {
                log("상세페이지 DOM 미확인: $detailStatus")
                webViewManager?.setBrowserMode(isChrome = false)
                return StrategyAResult(
                    success = false,
                    lastUrl = browserSession.currentUrl(),
                    message = "detail_dom_not_confirmed:${detailStatus.name.lowercase()}",
                )
            }
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

        log("상세 페이지 표시 유지: 다음 작업 전까지 현재 화면 유지")

        webViewManager?.setBrowserMode(isChrome = false)
        return StrategyAResult(success = true, lastUrl = finalUrl)
    }

    private fun buildNaverSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return "https://m.search.naver.com/search.naver?query=$encoded&where=m"
    }

    private suspend fun detectRateLimit(log: (String) -> Unit): StrategyAResult? {
        if (!browserSession.supportsPageInspection) return null
        val text = browserSession.visibleText()
        if (!looksRateLimited(text)) return null
        log("429/요청 제한 감지: 작업 실패")
        return StrategyAResult(
            success = false,
            lastUrl = browserSession.currentUrl(),
            message = "rate_limited_429",
        )
    }

    private suspend fun detectProtection(
        log: (String) -> Unit,
        leaseId: String? = null,
        accountAlias: String = "",
    ): StrategyAResult? {
        if (!browserSession.supportsPageInspection) return null
        val signals = protectionDetector.detectFromText(browserSession.visibleText()).toMutableList()
        if (browserSession.currentUrl()?.contains("nidlogin.login", ignoreCase = true) == true) {
            signals += ProtectionSignal.LOGIN_STILL_REQUIRED
        }
        if (signals.isEmpty()) return null
        log("보호/재인증 신호 감지: ${signals.joinToString()}")

        if (signals.contains(ProtectionSignal.CAPTCHA_OR_SECURITY_PAGE) &&
            captchaChallengeClient != null &&
            screenshotCapture != null
        ) {
            val solved = tryCaptchaChallenge(leaseId, accountAlias, log)
            if (solved) return null
        }

        return StrategyAResult(
            success = false,
            signals = signals.distinct(),
            lastUrl = browserSession.currentUrl(),
            message = "protection signal detected",
        )
    }

    private fun looksRateLimited(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.contains("429") ||
            normalized.contains("too many requests") ||
            normalized.contains("rate limit") ||
            text.contains("요청이 너무 많") ||
            text.contains("접속이 지연")
    }

    private suspend fun tryCaptchaChallenge(
        leaseId: String?,
        accountAlias: String,
        log: (String) -> Unit,
    ): Boolean {
        val challengeClient = captchaChallengeClient ?: return false
        val capture = screenshotCapture ?: return false
        log("영수증 캡챠 감지: 스크린샷 캡처 중...")
        val screenshot = capture.captureBase64() ?: run {
            log("스크린샷 캡처 실패")
            return false
        }
        val challengeId = try {
            challengeClient.submitChallenge(
                CaptchaChallengeRequest(
                    deviceName = deviceName,
                    leaseId = leaseId,
                    accountAlias = accountAlias,
                    screenshotBase64 = screenshot,
                    signalType = ProtectionSignal.CAPTCHA_OR_SECURITY_PAGE.name,
                    pageUrl = browserSession.currentUrl(),
                ),
            )
        } catch (e: Exception) {
            log("챌린지 제출 실패: ${e.message}")
            return false
        }
        log("챌린지 제출 완료: $challengeId — 관리자 응답 대기 중 (최대 ${captchaChallengeTimeoutMs / 1000}초)")

        val deadline = System.currentTimeMillis() + captchaChallengeTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(10_000)
            val poll = try {
                challengeClient.pollAnswer(challengeId)
            } catch (e: Exception) {
                log("폴링 실패: ${e.message}")
                continue
            }
            when (poll?.status) {
                "answered" -> {
                    val answer = poll.answer ?: run {
                        log("응답값 비어있음")
                        return false
                    }
                    log("관리자 응답 수신: $answer — 캡챠 입력 중")
                    val submitted = browserSession.fillCaptchaAndSubmit(answer)
                    runCatching { challengeClient.completeChallenge(challengeId, submitted) }
                    if (submitted) {
                        log("캡챠 제출 완료: 전략 계속")
                        delay(3_000)
                        return true
                    } else {
                        log("캡챠 입력 실패")
                        return false
                    }
                }
                "expired" -> {
                    log("챌린지 만료됨")
                    return false
                }
                else -> {
                    val remaining = (deadline - System.currentTimeMillis()) / 1000
                    log("관리자 응답 대기 중... 남은 시간: ${remaining}초")
                }
            }
        }
        log("관리자 응답 시간 초과")
        runCatching { challengeClient.completeChallenge(challengeId, false) }
        return false
    }

    companion object {
        const val NAVER_HOME_URL = "https://m.naver.com/"
    }
}
