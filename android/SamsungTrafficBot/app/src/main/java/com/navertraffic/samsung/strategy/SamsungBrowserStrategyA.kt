package com.navertraffic.samsung.strategy

import com.navertraffic.samsung.data.CaptchaChallengeClient
import com.navertraffic.samsung.data.CaptchaChallengeRequest
import com.navertraffic.samsung.data.ProtectionSignal
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder

data class StrategyAResult(
    val success: Boolean,
    val signals: List<ProtectionSignal> = emptyList(),
    val lastUrl: String? = null,
    val message: String? = null,
)

fun StrategyAResult.isRecoverableTaskFailure(): Boolean {
    if (success || signals.isNotEmpty()) return false
    val text = message.orEmpty()
    return text.startsWith("MID product not found") ||
        text == "rate_limited_429" ||
        text.startsWith("detail_dom_not_confirmed") ||
        text.endsWith("is required for Strategy A") ||
        text.endsWith("is required for Strategy G")
}

enum class StrategyVariant {
    /** A: 검색창탭 + fetch autocomplete + URL 직접 로드 (기준선) */
    A,
    /** B: 검색창탭 + 붙여넣기(commitText) + fetch autocomplete + 검색버튼 탭 (AI Reward 방식) */
    B,
    /** C: B와 동일한 전략, 다른 상품/키워드로 교차 검증 */
    C,
}

class SamsungBrowserStrategyA(
    private val browserSession: BrowserSession,
    private val protectionDetector: ProtectionDetector = ProtectionDetector(),
    private val stepDelayMs: Long = 3_000,
    private val productDelayMs: Long = 5_000,
    private val useMidClick: Boolean = true,
    private val detailSwipeMs: Long = 2_000,
    private val midClickTimeoutMs: Long = 30_000,
    val variant: StrategyVariant = StrategyVariant.A,
    private val captchaChallengeClient: CaptchaChallengeClient? = null,
    private val screenshotCapture: ScreenshotCapture? = null,
    private val captchaChallengeTimeoutMs: Long = 5 * 60 * 1_000,
) {
    constructor(
        webViewManager: SamsungWebViewManager,
        protectionDetector: ProtectionDetector = ProtectionDetector(),
        variant: StrategyVariant = StrategyVariant.A,
    ) : this(WebViewBrowserSession(webViewManager), protectionDetector, variant = variant)

    suspend fun run(task: StrategyATask, log: (String) -> Unit): Boolean {
        return runDetailed(task, log).success
    }

    suspend fun runDetailed(
        task: StrategyATask,
        log: (String) -> Unit,
        variantOverride: StrategyVariant? = null,
    ): StrategyAResult {
        val activeVariant = variantOverride ?: variant
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

        log("초기 랜딩: $AI_REWARD_LANDING_URL")
        browserSession.loadAndWait(AI_REWARD_LANDING_URL, 8_000)
        delay(Random.nextLong(800, 1_500))

        log("네이버 홈 진입: 검색창 탭 준비")
        browserSession.loadAndWait(NAVER_HOME_URL, 15_000)
        delay(Random.nextLong(800, 1_500))
        val tapped = browserSession.tapSearchBar()
        if (tapped) delay(Random.nextLong(400, 700))
        if (activeVariant != StrategyVariant.A) {
            browserSession.typeIntoSearchBar(task.keyword)
            delay(Random.nextLong(300, 600))
        }
        log("1차 자동완성 시뮬레이션 [${activeVariant}]: ${task.keyword}")
        browserSession.simulateAutocomplete(task.keyword)
        delay(Random.nextLong(400, 700))

        log("1차 검색: ${task.keyword}")
        if (activeVariant == StrategyVariant.A) {
            browserSession.loadAndWait(buildFirstSearchUrl(task.keyword))
        } else {
            browserSession.tapSearchSubmitAndWait()
        }
        delay(Random.nextLong(2_500, 4_500))
        log("1차 검색결과 스크롤")
        repeat(Random.nextInt(2, 4)) {
            browserSession.swipeDetail(Random.nextLong(1_800, 3_200))
            delay(Random.nextLong(600, 1_400))
        }
        detectProtection(log)?.let { return it }

        val tapped2 = browserSession.tapSearchBar()
        if (tapped2) delay(Random.nextLong(400, 700))
        if (activeVariant != StrategyVariant.A) {
            browserSession.typeIntoSearchBar(task.secondKeyword)
            delay(Random.nextLong(300, 600))
        }
        log("2차 자동완성 시뮬레이션 [${activeVariant}]: ${task.secondKeyword}")
        browserSession.simulateAutocomplete(task.secondKeyword)
        delay(Random.nextLong(400, 700))
        // React controlled input resets value to URL query after re-render — re-type immediately before submit
        if (activeVariant != StrategyVariant.A) {
            browserSession.typeIntoSearchBar(task.secondKeyword)
            delay(200)
        }

        log("2차 검색: ${task.secondKeyword}")
        if (activeVariant == StrategyVariant.A) {
            browserSession.loadAndWait(buildSecondSearchUrl(task.keyword, task.secondKeyword))
        } else {
            browserSession.tapSearchSubmitAndWait()
        }
        delay(Random.nextLong(2_500, 4_500))
        log("2차 검색결과 스크롤")
        repeat(Random.nextInt(2, 4)) {
            browserSession.swipeDetail(Random.nextLong(1_800, 3_200))
            delay(Random.nextLong(600, 1_400))
        }
        detectProtection(log)?.let { return it }

        val mid = task.mid.orEmpty()
        if (useMidClick && mid.isNotBlank()) {
            log("MID 상품 터치 진입: $mid")
            val clickResult = withTimeoutOrNull(midClickTimeoutMs) {
                browserSession.clickMidLink(mid, task.productTitle ?: task.secondKeyword)
            }
            val clicked = clickResult == true
            if (!clicked) {
                val reason = if (clickResult == null) "timeout" else "carousel scan"
                return StrategyAResult(
                    success = false,
                    lastUrl = browserSession.currentUrl(),
                    message = "MID product not found after $reason: $mid",
                )
            }
            log("추적 리다이렉트 대기 중 → 상품 상세")
            delay(Random.nextLong(4_500, 9_000))
        } else {
            log("상품 상세 직접 진입: ${task.linkUrl}")
            browserSession.loadAndWait(task.linkUrl)
            delay(Random.nextLong(4_500, 9_000))
        }

        repeat(Random.nextInt(2, 5)) {
            browserSession.swipeDetail(Random.nextLong(1_500, 3_000))
            delay(Random.nextLong(500, 1_200))
        }
        detectProtection(log)?.let { return it }

        val finalUrl = browserSession.currentUrl()

        log("브라우저 닫기 → 네이버 메인홈")
        browserSession.resetSurface()
        delay(Random.nextLong(400, 900))
        browserSession.loadAndWait(NAVER_HOME_URL, 15_000)
        delay(Random.nextLong(2_000, 4_000))

        return StrategyAResult(success = true, lastUrl = finalUrl)
    }

    private fun buildFirstSearchUrl(query: String): String {
        val q = URLEncoder.encode(query, "UTF-8")
        return "https://m.search.naver.com/search.naver?sm=mtp_hty.top&where=m&query=$q"
    }

    private fun buildSecondSearchUrl(firstKeyword: String, secondKeyword: String): String {
        val oq = URLEncoder.encode(firstKeyword, "UTF-8")
        val q  = URLEncoder.encode(secondKeyword, "UTF-8")
        return "https://m.search.naver.com/search.naver?sm=mtb_hty.top&where=m&ssc=tab.m.all&oquery=$oq&query=$q"
    }

    companion object {
        const val NAVER_HOME_URL = "https://m.naver.com/"
        const val AI_REWARD_LANDING_URL = "https://snsz.kr"
    }

    private suspend fun detectProtection(
        log: (String) -> Unit,
        leaseId: String? = null,
        accountAlias: String = "",
    ): StrategyAResult? {
        if (!browserSession.supportsPageInspection) return null
        val signals = protectionDetector.detectFromText(browserSession.visibleText())
        if (signals.isEmpty()) return null
        log("보호/재인증 신호 감지: ${signals.joinToString()}")

        if (signals.contains(ProtectionSignal.CAPTCHA_OR_SECURITY_PAGE) &&
            captchaChallengeClient != null &&
            screenshotCapture != null
        ) {
            val solved = tryCaptchaChallenge(signals, leaseId, accountAlias, log)
            if (solved) return null
        }

        return StrategyAResult(
            success = false,
            signals = signals,
            lastUrl = browserSession.currentUrl(),
            message = "protection or re-authentication signal detected",
        )
    }

    private suspend fun tryCaptchaChallenge(
        signals: List<ProtectionSignal>,
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
                    deviceName = "",
                    leaseId = leaseId,
                    accountAlias = accountAlias,
                    screenshotBase64 = screenshot,
                    signalType = signals.first { it == ProtectionSignal.CAPTCHA_OR_SECURITY_PAGE }.name,
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
}
