package com.navertraffic.samsung.strategy

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class ExternalSamsungBrowserSession(
    private val context: Context,
    private val log: (String) -> Unit,
) : BrowserSession {
    override val supportsPageInspection: Boolean
        get() = BrowserAccessibilityService.isReady()

    private var lastUrl: String? = null

    override suspend fun loadAndWait(url: String, timeoutMs: Long, includeReferer: Boolean) {
        lastUrl = url
        log("Samsung Internet 실행: $url")
        withContext(Dispatchers.Main) {
            val intent = Intent(context, SamsungBrowserLaunchActivity::class.java).apply {
                putExtra(SamsungBrowserLaunchActivity.EXTRA_URL, url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }
        delay(timeoutMs.coerceAtMost(3_500))
    }

    override suspend fun visibleText(): String {
        return BrowserAccessibilityService.visibleText()
    }

    override fun currentUrl(): String? {
        return lastUrl
    }

    override suspend fun productDetailStatus(mid: String): ProductDetailStatus {
        val text = visibleText()
        return when {
            looksRateLimited(text) -> ProductDetailStatus.RATE_LIMITED
            looksLikeProductDetail(text) -> ProductDetailStatus.DETAIL
            text.isNotBlank() -> ProductDetailStatus.NOT_DETAIL
            else -> ProductDetailStatus.UNKNOWN
        }
    }

    override suspend fun clickMidLink(mid: String, titleHint: String?): Boolean {
        log("Samsung Internet 화면에서 MID($mid) 상품 터치 대기")
        if (!BrowserAccessibilityService.isReady()) {
            log("접근성 서비스 비활성: 외부 브라우저 터치 불가")
            return false
        }
        return BrowserAccessibilityService.clickProduct(titleHint).also {
            if (it) log("Samsung Internet 화면 터치 실행 완료")
            if (!it) log("Samsung Internet 화면 터치 실패")
        }
    }

    override suspend fun swipeDetail(durationMs: Long) {
        if (!BrowserAccessibilityService.isReady()) {
            log("접근성 서비스 비활성: 상세 슬라이드 생략")
            return
        }
        BrowserAccessibilityService.swipeDetail(durationMs)
    }

    override suspend fun resetSurface() {
        lastUrl = null
        log("APK 백그라운드 유지: 다음 URL을 Samsung Internet 전면에 로드")
    }

    override suspend fun scrollBy(dy: Int) {
        if (!BrowserAccessibilityService.isReady()) return
        if (dy > 0) BrowserAccessibilityService.swipeDetail(1_500)
        // 위로 스크롤(dy < 0)은 Accessibility 모드에서 미지원 — 생략
    }

    override suspend fun fillCaptchaAndSubmit(answer: String): Boolean {
        if (!BrowserAccessibilityService.isReady()) {
            log("접근성 서비스 비활성: 캡챠 입력 불가")
            return false
        }
        return BrowserAccessibilityService.fillCaptcha(answer)
    }

    private fun looksRateLimited(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.contains("429") ||
            normalized.contains("too many requests") ||
            normalized.contains("rate limit") ||
            text.contains("요청이 너무 많") ||
            text.contains("접속이 지연")
    }

    private fun looksLikeProductDetail(text: String): Boolean {
        if (text.isBlank()) return false
        val commerceSignals = listOf("구매하기", "바로구매", "장바구니", "찜하기", "톡톡", "리뷰")
        return commerceSignals.count { text.contains(it) } >= 2
    }
}
