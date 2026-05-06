package com.navertraffic.samsung.strategy

interface BrowserSession {
    val supportsPageInspection: Boolean

    suspend fun loadAndWait(url: String, timeoutMs: Long = 30_000)

    suspend fun visibleText(): String

    fun currentUrl(): String?

    suspend fun clickMidLink(mid: String, titleHint: String? = null): Boolean = false

    suspend fun swipeDetail(durationMs: Long = 2_000) = Unit

    suspend fun resetSurface() = Unit
}
