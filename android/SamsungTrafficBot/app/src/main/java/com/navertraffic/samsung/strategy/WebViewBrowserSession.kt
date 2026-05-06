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
}
