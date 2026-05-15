package com.navertraffic.samsung.strategy

class DryRunBrowserSession(
    private val log: (String) -> Unit,
) : BrowserSession {
    override val supportsPageInspection: Boolean = false

    private var lastUrl: String? = null

    override suspend fun loadAndWait(url: String, timeoutMs: Long) {
        lastUrl = url
        log("DRY_RUN URL 기록: $url")
    }

    override suspend fun visibleText(): String = ""

    override fun currentUrl(): String? = lastUrl

    override suspend fun scrollBy(dy: Int) {
        log("DRY_RUN scrollBy: dy=$dy")
    }
}
