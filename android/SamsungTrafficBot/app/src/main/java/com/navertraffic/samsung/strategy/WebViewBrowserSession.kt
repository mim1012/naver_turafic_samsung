package com.navertraffic.samsung.strategy

class WebViewBrowserSession(
    private val webViewManager: SamsungWebViewManager,
) : BrowserSession {
    override val supportsPageInspection: Boolean = true

    override suspend fun loadAndWait(url: String, timeoutMs: Long) {
        webViewManager.loadAndWait(url, timeoutMs)
    }

    override suspend fun visibleText(): String {
        return webViewManager.visibleText()
    }

    override fun currentUrl(): String? {
        return webViewManager.currentUrl()
    }

    override suspend fun clickMidLink(mid: String, titleHint: String?): Boolean {
        return webViewManager.clickMidLink(mid)
    }

    override suspend fun swipeDetail(durationMs: Long) {
        webViewManager.swipeDetail(durationMs)
    }

    override suspend fun resetSurface() {
        webViewManager.resetSurface()
    }

    override suspend fun simulateAutocomplete(keyword: String) {
        webViewManager.simulateAutocomplete(keyword)
    }

    override suspend fun tapSearchBar(): Boolean {
        return webViewManager.tapSearchBar()
    }

    override suspend fun typeIntoSearchBar(keyword: String): Boolean {
        return webViewManager.typeIntoSearchBar(keyword)
    }

    override suspend fun tapSearchSubmitAndWait(timeoutMs: Long): Boolean {
        return webViewManager.tapSearchSubmitAndWait(timeoutMs)
    }

    override suspend fun scrollBy(dy: Int) {
        webViewManager.scrollByJs(dy)
    }

    override suspend fun fillCaptchaAndSubmit(answer: String): Boolean {
        return webViewManager.fillCaptchaAndSubmit(answer)
    }
}
