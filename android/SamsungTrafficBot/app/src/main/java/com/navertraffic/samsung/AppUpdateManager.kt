package com.navertraffic.samsung

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AppUpdateManager(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    data class ReleaseInfo(val versionCode: Int, val versionName: String, val apkUrl: String)

    suspend fun checkAndUpdate(supabaseUrl: String, anonKey: String): Boolean = withContext(Dispatchers.IO) {
        if (supabaseUrl.isBlank()) return@withContext false

        val latest = fetchLatestRelease(supabaseUrl, anonKey) ?: return@withContext false
        installIfNewer(latest)
    }

    suspend fun checkAndUpdateFromServer(serverUrl: String, apiKey: String?): Boolean = withContext(Dispatchers.IO) {
        if (serverUrl.isBlank()) return@withContext false

        val latest = fetchLatestReleaseFromServer(serverUrl, apiKey) ?: return@withContext false
        installIfNewer(latest)
    }

    private fun installIfNewer(latest: ReleaseInfo): Boolean {
        if (latest.versionCode <= BuildConfig.VERSION_CODE) return false

        log("신버전 발견: ${latest.versionName} (코드 ${latest.versionCode}) — 현재: ${BuildConfig.VERSION_CODE}")
        log("APK 다운로드 중...")

        val apkFile = File(context.cacheDir, "update.apk")
        if (!downloadApk(latest.apkUrl, apkFile)) {
            log("APK 다운로드 실패")
            return false
        }

        log("APK 설치 중 (루트 pm install)...")
        val proc = Runtime.getRuntime().exec(
            arrayOf("su", "-c", "pm install -r \"${apkFile.absolutePath}\"")
        )
        val stdout = proc.inputStream.bufferedReader().readText().trim()
        val stderr = proc.errorStream.bufferedReader().readText().trim()
        val exitCode = proc.waitFor()

        return if (exitCode == 0 && (stdout.endsWith("Success") || stderr.endsWith("Success"))) {
            log("업데이트 완료 (${latest.versionName}) — 재시작")
            Runtime.getRuntime().exec(
                arrayOf("su", "-c", "am start -n com.navertraffic.samsung/.ui.MainActivity")
            )
            true
        } else {
            log("APK 설치 실패 (exit=$exitCode): $stdout $stderr")
            false
        }
    }

    private fun fetchLatestRelease(supabaseUrl: String, anonKey: String): ReleaseInfo? =
        runCatching {
            val url = "${supabaseUrl.trimEnd('/')}/app_releases?enabled=eq.true&order=version_code.desc&limit=1"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("apikey", anonKey)
            conn.setRequestProperty("Authorization", "Bearer $anonKey")
            conn.setRequestProperty("Accept", "application/json")
            val raw = conn.inputStream.bufferedReader().readText()
            parseRelease(raw)
        }.getOrNull()

    private fun fetchLatestReleaseFromServer(serverUrl: String, apiKey: String?): ReleaseInfo? =
        runCatching {
            val url = "${serverUrl.trimEnd('/')}/android/app-release/latest"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Accept", "application/json")
            if (!apiKey.isNullOrBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            val raw = conn.inputStream.bufferedReader().readText()
            parseRelease(raw)
        }.getOrNull()

    private fun downloadApk(apkUrl: String, dest: File): Boolean =
        runCatching {
            val conn = URL(apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.connect()
            if (conn.responseCode != 200) return false
            dest.outputStream().use { out -> conn.inputStream.copyTo(out) }
            true
        }.getOrDefault(false)

    private fun parseRelease(json: String): ReleaseInfo? {
        val body = json.trim().removePrefix("[").trimStart()
        val objEnd = findObjectEnd(body)
        if (objEnd < 0) return null
        val obj = body.substring(0, objEnd + 1)
        val versionCode = Regex(""""version_code"\s*:\s*(\d+)""").find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val versionName = Regex(""""version_name"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1) ?: return null
        val apkUrl = Regex(""""apk_url"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""").find(obj)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.replace("\\/", "/") ?: return null
        return ReleaseInfo(versionCode, versionName, apkUrl)
    }

    private fun findObjectEnd(s: String): Int {
        var depth = 0
        for (i in s.indices) {
            when (s[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }
}
