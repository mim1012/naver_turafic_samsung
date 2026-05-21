package com.navertraffic.samsung.strategy

enum class ProductDetailStatus {
    DETAIL,
    NOT_DETAIL,
    RATE_LIMITED,
    UNKNOWN,
}

interface BrowserSession {
    val supportsPageInspection: Boolean

    suspend fun loadAndWait(url: String, timeoutMs: Long = 30_000)

    suspend fun visibleText(): String

    fun currentUrl(): String?

    suspend fun clickMidLink(mid: String, titleHint: String? = null): Boolean = false

    suspend fun productDetailStatus(mid: String): ProductDetailStatus = ProductDetailStatus.UNKNOWN

    suspend fun swipeDetail(durationMs: Long = 2_000) = Unit

    suspend fun resetSurface() = Unit

    suspend fun simulateAutocomplete(keyword: String) = Unit

    suspend fun tapSearchBar(): Boolean = false

    suspend fun typeIntoSearchBar(keyword: String): Boolean = false

    suspend fun tapSearchSubmitAndWait(timeoutMs: Long = 30_000): Boolean = false

    suspend fun scrollBy(dy: Int) = Unit

    suspend fun fillCaptchaAndSubmit(answer: String): Boolean = false
}
