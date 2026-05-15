package com.navertraffic.samsung.soldier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class IpRotationManager {
    suspend fun fetchPublicIp(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = java.net.URL("https://api.ipify.org").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.inputStream.bufferedReader().use { it.readText() }.trim()
        }.getOrNull()
    }

    suspend fun rotate(log: (String) -> Unit): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val beforeIp = fetchPublicIp()
        log("IP rotation 시작: before=${beforeIp ?: "조회실패"}")
        Runtime.getRuntime().exec(arrayOf("su", "-c", "svc data disable")).waitFor()
        delay(4_000)
        Runtime.getRuntime().exec(arrayOf("su", "-c", "svc data enable")).waitFor()
        delay(15_000)
        val afterIp = fetchPublicIp()
        val changed = beforeIp != null && afterIp != null && beforeIp != afterIp
        log("IP rotation 완료: after=${afterIp ?: "조회실패"} | 변경=${if (changed) "성공" else "실패(동일)"}")
        Pair(beforeIp, afterIp)
    }
}
