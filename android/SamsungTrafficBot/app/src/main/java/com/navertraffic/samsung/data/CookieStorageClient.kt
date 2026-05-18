package com.navertraffic.samsung.data

interface CookieStorageClient {
    suspend fun saveCookies(deviceName: String, accountAlias: String?, cookies: String)
    suspend fun loadCookies(deviceName: String, accountAlias: String?): String?
}

class NoopCookieStorageClient : CookieStorageClient {
    override suspend fun saveCookies(deviceName: String, accountAlias: String?, cookies: String) = Unit
    override suspend fun loadCookies(deviceName: String, accountAlias: String?): String? = null
}
