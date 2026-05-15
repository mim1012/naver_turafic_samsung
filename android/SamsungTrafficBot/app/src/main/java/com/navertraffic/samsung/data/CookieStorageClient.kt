package com.navertraffic.samsung.data

interface CookieStorageClient {
    suspend fun saveCookies(deviceName: String, cookies: String)
    suspend fun loadCookies(deviceName: String): String?
}

class NoopCookieStorageClient : CookieStorageClient {
    override suspend fun saveCookies(deviceName: String, cookies: String) = Unit
    override suspend fun loadCookies(deviceName: String): String? = null
}
