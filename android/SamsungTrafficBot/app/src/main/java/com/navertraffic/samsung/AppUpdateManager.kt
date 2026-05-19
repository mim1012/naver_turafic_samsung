package com.navertraffic.samsung

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class AppUpdateManager(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    data class ReleaseInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String? = null,
    )

    data class UpdateResult(
        val installed: Boolean,
        val success: Boolean,
        val message: String,
        val release: ReleaseInfo? = null,
    )

    suspend fun checkAndUpdate(supabaseUrl: String, anonKey: String): Boolean = withContext(Dispatchers.IO) {
        checkAndUpdateDetailed(supabaseUrl, anonKey).installed
    }

    suspend fun checkAndUpdateDetailed(
        supabaseUrl: String,
        anonKey: String,
        restartAfterInstall: Boolean = true,
    ): UpdateResult = withContext(Dispatchers.IO) {
        if (supabaseUrl.isBlank()) return@withContext UpdateResult(
            installed = false,
            success = true,
            message = "supabase url blank",
        )

        val latest = fetchLatestRelease(supabaseUrl, anonKey) ?: return@withContext UpdateResult(
            installed = false,
            success = false,
            message = "latest release not found",
        )
        installIfNewer(latest, restartAfterInstall)
    }

    suspend fun checkAndUpdateFromServer(serverUrl: String, apiKey: String?): Boolean = withContext(Dispatchers.IO) {
        checkAndUpdateFromServerDetailed(serverUrl, apiKey).installed
    }

    suspend fun checkAndUpdateFromServerDetailed(
        serverUrl: String,
        apiKey: String?,
        restartAfterInstall: Boolean = true,
    ): UpdateResult = withContext(Dispatchers.IO) {
        if (serverUrl.isBlank()) return@withContext UpdateResult(
            installed = false,
            success = true,
            message = "server url blank",
        )

        val latest = fetchLatestReleaseFromServer(serverUrl, apiKey) ?: return@withContext UpdateResult(
            installed = false,
            success = false,
            message = "latest release not found",
        )
        installIfNewer(latest, restartAfterInstall)
    }

    private fun installIfNewer(latest: ReleaseInfo, restartAfterInstall: Boolean): UpdateResult {
        if (latest.versionCode <= BuildConfig.VERSION_CODE) {
            return UpdateResult(
                installed = false,
                success = true,
                message = "already latest: ${BuildConfig.VERSION_CODE}",
                release = latest,
            )
        }

        log("신버전 발견: ${latest.versionName} (코드 ${latest.versionCode}) — 현재: ${BuildConfig.VERSION_CODE}")
        log("APK 다운로드 중...")

        val apkFile = File(context.cacheDir, "update.apk")
        if (!downloadApk(latest.apkUrl, apkFile)) {
            log("APK 다운로드 실패")
            return UpdateResult(
                installed = false,
                success = false,
                message = "apk download failed",
                release = latest,
            )
        }

        val expectedSha256 = latest.sha256?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        if (expectedSha256 != null) {
            val actualSha256 = sha256(apkFile)
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                apkFile.delete()
                val message = "sha256 mismatch: expected=$expectedSha256 actual=$actualSha256"
                log("APK 검증 실패: $message")
                return UpdateResult(
                    installed = false,
                    success = false,
                    message = message,
                    release = latest,
                )
            }
            log("APK sha256 검증 완료")
        }

        log("APK 설치 중 (루트 pm install)...")
        val proc = Runtime.getRuntime().exec(
            arrayOf("su", "-c", "pm install -r \"${apkFile.absolutePath}\"")
        )
        val stdout = proc.inputStream.bufferedReader().readText().trim()
        val stderr = proc.errorStream.bufferedReader().readText().trim()
        val exitCode = proc.waitFor()

        return if (exitCode == 0 && (stdout.endsWith("Success") || stderr.endsWith("Success"))) {
            if (restartAfterInstall) {
                log("업데이트 완료 (${latest.versionName}) — 재시작")
                Runtime.getRuntime().exec(
                    arrayOf(
                        "su",
                        "-c",
                        "sh -c 'sleep 1; am force-stop com.navertraffic.samsung; sleep 1; am start -n com.navertraffic.samsung/.ui.MainActivity' >/dev/null 2>&1 &",
                    )
                )
            } else {
                log("업데이트 설치 완료 (${latest.versionName}) — 명령 보고 후 재시작")
            }
            UpdateResult(
                installed = true,
                success = true,
                message = "installed ${latest.versionName} (${latest.versionCode})",
                release = latest,
            )
        } else {
            val message = "pm install failed exit=$exitCode stdout=$stdout stderr=$stderr"
            log("APK 설치 실패 ($message)")
            UpdateResult(
                installed = false,
                success = false,
                message = message,
                release = latest,
            )
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
        val versionCode = Regex(""""(?:version_code|versionCode)"\s*:\s*(\d+)""").find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val versionName = Regex(""""(?:version_name|versionName)"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1) ?: return null
        val apkUrl = Regex(""""(?:apk_url|apkUrl)"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""").find(obj)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.replace("\\/", "/") ?: return null
        val sha256 = Regex(""""(?:sha256|apk_sha256|apkSha256)"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
        return ReleaseInfo(versionCode, versionName, apkUrl, sha256)
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
