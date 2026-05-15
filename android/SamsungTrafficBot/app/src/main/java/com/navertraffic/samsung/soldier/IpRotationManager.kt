package com.navertraffic.samsung.soldier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class IpRotationManager {
    suspend fun fetchPublicIp(): String? = withContext(Dispatchers.IO) {
        // withTimeoutOrNull로 DNS 블록 포함 전체에 타임아웃 적용
        withTimeoutOrNull(12_000) {
            runCatching {
                val conn = java.net.URL("https://api.ipify.org").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.inputStream.bufferedReader().use { it.readText() }.trim()
            }.getOrNull()
        }
    }

    suspend fun rotate(log: (String) -> Unit): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val beforeIp = fetchPublicIp()
        log("IP rotation 시작: before=${beforeIp ?: "조회실패"}")
        execRoot("svc data disable")
        delay(4_000)
        execRoot("svc data enable")
        delay(15_000)
        val afterIp = fetchPublicIp()
        val changed = beforeIp != null && afterIp != null && beforeIp != afterIp
        log("IP rotation 완료: after=${afterIp ?: "조회실패"} | 변경=${if (changed) "성공" else "실패(동일)"}")
        Pair(beforeIp, afterIp)
    }

    // stdout/stderr 닫기 + 10초 waitFor 타임아웃으로 데드락 방지
    private fun execRoot(cmd: String) {
        runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            proc.inputStream.close()
            proc.errorStream.close()
            val finished = proc.waitFor(10, TimeUnit.SECONDS)
            if (!finished) proc.destroy()
        }
    }
}
