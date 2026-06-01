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
    private val detailConfirmationTimeoutMs: Long = 25_000,
    private val detailConfirmationPollMs: Long = 1_000,
    private val deviceName: String = "",
    private val captchaChallengeClient: CaptchaChallengeClient? = null,
    private val screenshotCapture: ScreenshotCapture? = null,
    private val captchaChallengeTimeoutMs: Long = 5 * 60 * 1_000,
) {
    private val secondPhraseMemory = BoundedMidPhraseMemory()

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

        val firstPhrase = SecondKeywordStore.buildGIntegratedFiveWordQuery(
            mainKeyword = task.keyword,
            secondaryText = task.keywordName,
        )
        val secondPhrase = pickSecondPhrase(task, log)

        // 1차 검색 — GUI G와 동일하게 5단어 통합검색 URL 진입, MID 클릭 없음
        log("1차 검색(G 5단어): $firstPhrase")
        browserSession.loadAndWait(buildNaverSearchUrl(firstPhrase), 30_000, includeReferer = false)
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

        // 2차 검색 — GUI G처럼 검색창 전체값을 갈아끼운 뒤 제출하고, 실패 시 URL 이동 폴백
        log("2차 검색(G): $secondPhrase")
        val typedSecondSearch = submitSecondSearchViaSearchBox(secondPhrase, log)
        if (!typedSecondSearch) {
            log("2차 검색창 입력 실패 → URL 직접 이동 폴백")
            browserSession.loadAndWait(buildNaverSearchUrl(secondPhrase), 30_000, includeReferer = false)
        }
        delay(Random.nextLong(2_500, 4_000))
        detectProtection(log)?.let {
            webViewManager?.setBrowserMode(isChrome = false)
            return it
        }
        detectRateLimit(log)?.let {
            webViewManager?.setBrowserMode(isChrome = false)
            return it
        }

        // GUI G처럼 2차 검색 직후 쇼핑 영역까지 먼저 밀어 올린다.
        log("G모드 2차 검색 직후 쇼핑 영역 선탐색 스크롤")
        repeat(4) {
            browserSession.scrollBy((explorationScrollPixels * 0.95).toInt().coerceAtLeast(280))
            delay(Random.nextLong(350, 700))
        }

        // 탐색 스크롤하며 MID 탐색 (패킷 방식: DOM에서 href만 추출, WebView 네비게이션 없음)
        log("2차 MID 탐색: ${task.mid}")
        val wm = webViewManager
        var midHref: String? = null

        val midExplorationResult = withTimeoutOrNull(midExplorationTimeoutMs) {
            repeat(explorationScrollCount) {
                if (midHref == null) {
                    midHref = wm?.findMidLinkHref(task.mid, task.productTitle ?: task.keywordName)
                        ?: if (browserSession.clickMidLink(task.mid, task.productTitle ?: task.keywordName)) "clicked" else null
                }
                if (midHref == null) {
                    browserSession.scrollBy(explorationScrollPixels)
                    delay(Random.nextLong(350, 700))
                }
            }
            if (midHref == null) {
                midHref = wm?.findMidLinkHref(task.mid, task.productTitle ?: task.keywordName)
                    ?: if (browserSession.clickMidLink(task.mid, task.productTitle ?: task.keywordName)) "clicked" else null
            }
            midHref != null
        }
        val clicked = midExplorationResult == true

        if (!clicked) {
            val reason = if (midExplorationResult == null) "timeout" else "exploration"
            log("MID 미탐색: ${reason}, 다음 작업으로 이동")
            rememberSecondPhraseMiss(task.mid, secondPhrase, log)
            webViewManager?.setBrowserMode(isChrome = false)
            return StrategyAResult(
                success = false,
                lastUrl = browserSession.currentUrl(),
                message = "MID product not found after $reason: ${task.mid}",
                failureReason = "mid_product_not_found_after_$reason",
                queryPhrase = secondPhrase,
                midFound = false,
            )
        }

        val href = midHref
        if (wm != null && href != null && href != "clicked") {
            val referer = browserSession.currentUrl()
            log("패킷 상세페이지 진입: ${task.mid}")
            val packetResult = wm.fetchDetailPageByPacket(href, task.mid, referer)
            when (packetResult) {
                is PacketDetailResult.Success ->
                    log("패킷 상세페이지 확인: ${task.mid} (${packetResult.elapsedMs}ms)")
                is PacketDetailResult.NotDetail -> {
                    log("패킷 상세페이지 미확인: code=${packetResult.httpCode} url=${packetResult.finalUrl} (${packetResult.elapsedMs}ms)")
                    rememberSecondPhraseMiss(task.mid, secondPhrase, log)
                    wm.setBrowserMode(isChrome = false)
                    return StrategyAResult(
                        success = false,
                        lastUrl = packetResult.finalUrl,
                        message = "packet_detail_not_confirmed",
                        failureReason = "packet_detail_not_confirmed",
                        queryPhrase = secondPhrase,
                        midFound = true,
                    )
                }
                is PacketDetailResult.Error -> {
                    log("패킷 상세페이지 오류: code=${packetResult.httpCode} ${packetResult.message} (${packetResult.elapsedMs}ms)")
                    rememberSecondPhraseMiss(task.mid, secondPhrase, log)
                    wm.setBrowserMode(isChrome = false)
                    return StrategyAResult(
                        success = false,
                        lastUrl = browserSession.currentUrl(),
                        message = "packet_detail_error:${packetResult.message}",
                        failureReason = "packet_detail_error",
                        queryPhrase = secondPhrase,
                        midFound = true,
                    )
                }
            }
        } else {
            // WebView 폴백: webViewManager 없거나 탭 방식으로 이미 클릭된 경우
            log("상세 페이지 진입 대기")
            delay(Random.nextLong(4_500, 9_000))
            logDetailDiagnostics("MID 클릭 후 상세 후보", task, log)
            detectProtection(log)?.let {
                webViewManager?.setBrowserMode(isChrome = false)
                return it
            }
            when (val confirmation = confirmDetailStatus(task, log)) {
                is DetailConfirmation.Confirmed -> log("상세페이지 DOM 확인: ${task.mid}")
                is DetailConfirmation.RateLimited -> {
                    log("429/요청 제한 감지: 작업 실패")
                    webViewManager?.setBrowserMode(isChrome = false)
                    return StrategyAResult(
                        success = false,
                        lastUrl = browserSession.currentUrl(),
                        message = "rate_limited_429",
                        failureReason = "rate_limited_429",
                        queryPhrase = secondPhrase,
                        midFound = true,
                    )
                }
                is DetailConfirmation.NotConfirmed -> {
                    val diagnostics = pageDiagnosticsSummary(confirmation.diagnostics)
                    log("상세페이지 DOM 미확인 최종: ${confirmation.status} ${confirmation.failureReason} $diagnostics")
                    webViewManager?.setBrowserMode(isChrome = false)
                    return StrategyAResult(
                        success = false,
                        lastUrl = browserSession.currentUrl(),
                        message = "${confirmation.failureReason}:${confirmation.status.name.lowercase()} $diagnostics",
                        failureReason = confirmation.failureReason,
                        queryPhrase = secondPhrase,
                        detailStatus = confirmation.status.name.lowercase(),
                        midFound = true,
                    )
                }
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
        return StrategyAResult(
            success = true,
            lastUrl = finalUrl,
            queryPhrase = secondPhrase,
            detailStatus = ProductDetailStatus.DETAIL.name.lowercase(),
            midFound = true,
        )
    }

    private suspend fun submitSecondSearchViaSearchBox(
        query: String,
        log: (String) -> Unit,
    ): Boolean {
        val tapped = browserSession.tapSearchBar()
        if (tapped) delay(Random.nextLong(250, 500))
        val typed = browserSession.typeIntoSearchBar(query)
        if (!typed) return false
        delay(Random.nextLong(250, 550))
        val submitted = browserSession.submitSearchWithEnterAndWait(45_000)
        if (submitted) {
            log("2차 검색창 전체삭제/재입력 Enter 제출 완료")
        }
        return submitted
    }

    private suspend fun confirmDetailStatus(
        task: StrategyGTask,
        log: (String) -> Unit,
    ): DetailConfirmation {
        val titleHint = task.productTitle ?: task.keywordName
        val deadline = System.currentTimeMillis() + detailConfirmationTimeoutMs
        var attempt = 1

        while (true) {
            val lastStatus = browserSession.productDetailStatus(task.mid, titleHint)
            if (lastStatus == ProductDetailStatus.DETAIL) return DetailConfirmation.Confirmed
            if (lastStatus == ProductDetailStatus.RATE_LIMITED) return DetailConfirmation.RateLimited

            val lastDiagnostics = browserSession.pageDiagnostics(titleHint)
            val failureReason = classifyDetailFailure(lastDiagnostics)
            val now = System.currentTimeMillis()
            if (now >= deadline || !shouldKeepWaitingForDetail(failureReason)) {
                return DetailConfirmation.NotConfirmed(lastStatus, failureReason, lastDiagnostics)
            }

            log(
                "상세페이지 DOM 대기 ${attempt}: $lastStatus $failureReason " +
                    pageDiagnosticsSummary(lastDiagnostics),
            )
            attempt += 1
            delay(detailConfirmationPollMs.coerceAtMost((deadline - now).coerceAtLeast(0)))
        }
    }

    private suspend fun logDetailDiagnostics(
        label: String,
        task: StrategyGTask,
        log: (String) -> Unit,
    ) {
        log("$label ${pageDiagnosticsSummary(task)}")
    }

    private suspend fun pageDiagnosticsSummary(task: StrategyGTask): String {
        val diagnostics = browserSession.pageDiagnostics(task.productTitle ?: task.keywordName)
        return pageDiagnosticsSummary(diagnostics)
    }

    private fun pageDiagnosticsSummary(diagnostics: PageDiagnostics): String {
        return "url=${diagnostics.url.orEmpty().compactForDiagnostics(120)} " +
            "jsUrl=${diagnostics.jsUrl.orEmpty().compactForDiagnostics(120)} " +
            "ready=${diagnostics.readyState.ifBlank { "-" }} " +
            "body=${diagnostics.bodyLength} " +
            "title=${diagnostics.title.ifBlank { "-" }} " +
            "markers=${diagnostics.htmlMarkers.ifBlank { "none" }} " +
            "text=${diagnostics.visibleTextSample.ifBlank { "-" }}"
    }

    private fun classifyDetailFailure(diagnostics: PageDiagnostics): String {
        val url = "${diagnostics.url.orEmpty()} ${diagnostics.jsUrl.orEmpty()}".lowercase()
        val title = diagnostics.title.lowercase()
        val text = diagnostics.visibleTextSample.lowercase()
        val markers = diagnostics.htmlMarkers.lowercase()

        return when {
            url.contains("cr.shopping.naver.com") ||
                url.contains("cr2.shopping.naver.com") ||
                url.contains("cr3.shopping.naver.com") -> "bridge_stuck"
            title.contains("[에러페이지]") ||
                text.contains("현재 서비스 접속이 불가") -> "smartstore_error_page"
            title.contains("웹페이지를 사용할 수 없음") ||
                text.contains("웹페이지를 사용할 수 없음") ||
                text.contains("err_name_not_resolved") ||
                text.contains("err_") -> "webview_load_error"
            looksLikeProductUrl(url) &&
                (title.contains("네이버 검색") || text.startsWith("본문 바로가기 naver 이전페이지")) -> {
                    "dom_navigation_race"
                }
            markers.isNotBlank() && markers != "none" -> "weak_detail_signal"
            diagnostics.bodyLength == 0 || text.isBlank() || text == "-" -> "empty_or_unknown"
            else -> "detail_dom_not_confirmed"
        }
    }

    private fun shouldKeepWaitingForDetail(failureReason: String): Boolean {
        return failureReason == "bridge_stuck" ||
            failureReason == "dom_navigation_race" ||
            failureReason == "empty_or_unknown" ||
            failureReason == "weak_detail_signal"
    }

    private fun looksLikeProductUrl(url: String): Boolean {
        return url.contains("/products/") ||
            url.contains("smartstore.naver.com") ||
            url.contains("brand.naver.com") ||
            url.contains("shopping.naver.com")
    }

    private sealed class DetailConfirmation {
        object Confirmed : DetailConfirmation()
        object RateLimited : DetailConfirmation()
        data class NotConfirmed(
            val status: ProductDetailStatus,
            val failureReason: String,
            val diagnostics: PageDiagnostics,
        ) : DetailConfirmation()
    }

    private fun pickSecondPhrase(
        task: StrategyGTask,
        log: (String) -> Unit,
    ): String {
        val mid = task.mid.trim()
        val missCount = secondPhraseMemory.missCount(mid)
        if (missCount >= FORCE_FULL_SECOND_SEARCH_AFTER_MISSES) {
            log("G모드 2차 미노출 누적 $missCount 회 → 5단어 재조합 유지")
        }

        val failed = secondPhraseMemory.failedPhrases(mid)
        repeat(200) { attempt ->
            val candidate = SecondKeywordStore.buildGIntegratedFiveWordQuery(
                mainKeyword = task.keyword,
                secondaryText = task.keywordName,
            )
            if (candidate !in failed) {
                if (attempt > 0) log("G 2차 5단어 ${attempt + 1}번째 시도로 채택: $candidate")
                return candidate
            }
        }

        val fallback = SecondKeywordStore.buildGIntegratedFiveWordQuery(task.keyword, task.keywordName)
        log("G 2차 제외 목록 충돌 다수 → 임의 5단어 사용: $fallback")
        return fallback
    }

    private fun rememberSecondPhraseMiss(
        mid: String,
        phrase: String,
        log: (String) -> Unit,
    ) {
        val key = mid.trim()
        if (key.isBlank() || phrase.isBlank()) return
        val nextCount = secondPhraseMemory.rememberMiss(key, phrase)
        log("G 2차 미노출 조합 등록: mid=$key count=$nextCount phrase=$phrase")
    }

    private fun buildNaverSearchUrl(query: String): String {
        return buildNaverSearchUrlForQuery(query)
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
            failureReason = "rate_limited_429",
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
            failureReason = "protection_signal_detected",
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
        private const val FORCE_FULL_SECOND_SEARCH_AFTER_MISSES = 10

        internal fun buildNaverSearchUrlForQuery(query: String): String {
            val encoded = URLEncoder.encode(query, "UTF-8")
            return "https://m.search.naver.com/search.naver?where=m&query=$encoded"
        }
    }
}
