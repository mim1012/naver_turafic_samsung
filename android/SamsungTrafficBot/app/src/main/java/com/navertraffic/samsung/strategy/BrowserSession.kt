package com.navertraffic.samsung.strategy

enum class ProductDetailStatus {
    DETAIL,
    NOT_DETAIL,
    RATE_LIMITED,
    UNKNOWN,
}

data class PageDiagnostics(
    val url: String? = null,
    val jsUrl: String? = null,
    val readyState: String = "",
    val bodyLength: Int = 0,
    val title: String = "",
    val visibleTextSample: String = "",
    val htmlMarkers: String = "",
)

interface BrowserSession {
    val supportsPageInspection: Boolean

    suspend fun loadAndWait(url: String, timeoutMs: Long = 30_000, includeReferer: Boolean = true)

    suspend fun visibleText(): String

    fun currentUrl(): String?

    suspend fun clickMidLink(mid: String, titleHint: String? = null): Boolean = false

    suspend fun productDetailStatus(mid: String, titleHint: String? = null): ProductDetailStatus =
        ProductDetailStatus.UNKNOWN

    suspend fun pageDiagnostics(titleHint: String? = null): PageDiagnostics {
        return PageDiagnostics(
            url = currentUrl(),
            visibleTextSample = visibleText().compactForDiagnostics(),
        )
    }

    suspend fun swipeDetail(durationMs: Long = 2_000) = Unit

    suspend fun resetSurface() = Unit

    suspend fun simulateAutocomplete(keyword: String) = Unit

    suspend fun tapSearchBar(): Boolean = false

    suspend fun typeIntoSearchBar(keyword: String): Boolean = false

    suspend fun tapSearchSubmitAndWait(timeoutMs: Long = 30_000): Boolean = false

    suspend fun submitSearchWithEnterAndWait(timeoutMs: Long = 30_000): Boolean = false

    suspend fun scrollBy(dy: Int) = Unit

    suspend fun fillCaptchaAndSubmit(answer: String): Boolean = false
}

internal fun String.compactForDiagnostics(maxLen: Int = 220): String {
    val compact = replace(Regex("\\s+"), " ").trim()
    return if (compact.length <= maxLen) compact else compact.take(maxLen) + "..."
}
