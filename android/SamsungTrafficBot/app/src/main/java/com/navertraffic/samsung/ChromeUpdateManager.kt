package com.navertraffic.samsung

import android.content.Context
import android.os.Build
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ChromeUpdateManager(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    data class ChromeRelease(
        val versionName: String,
        val apkUrl: String,
        val sha256: String? = null,
        val packageName: String = CHROME_PACKAGE,
    )

    data class UpdateResult(
        val updateRequired: Boolean,
        val installed: Boolean,
        val success: Boolean,
        val message: String,
        val currentVersion: String? = null,
        val release: ChromeRelease? = null,
    )

    suspend fun checkAndUpdate(
        serverUrl: String,
        apiKey: String?,
        deviceName: String,
    ): UpdateResult = withContext(Dispatchers.IO) {
        val provider = currentWebViewProvider()
        val currentVersion = provider?.versionName ?: currentPackageVersion(CHROME_PACKAGE)
        val currentMajor = currentVersion?.let { parseMajor(it) }
        val minMajor = BuildConfig.MIN_CHROME_MAJOR

        if (!requiresChromeUpdate(currentMajor)) {
            return@withContext UpdateResult(
                updateRequired = false,
                installed = false,
                success = true,
                message = "chrome ok",
                currentVersion = currentVersion,
            )
        }

        log("Chrome/WebView 업데이트 필요: 현재 ${currentVersion ?: "unknown"}, 필요 major >= $minMajor")

        val release = fetchLatestChromeFromServer(serverUrl, apiKey)
            ?: configuredChromeRelease()
            ?: return@withContext UpdateResult(
                updateRequired = true,
                installed = false,
                success = false,
                message = "chrome update apk url missing",
                currentVersion = currentVersion,
            ).also {
                log("Chrome 자동 업데이트 URL 없음: 서버 /android/chrome/latest 또는 chrome.update.apk.url 설정 필요")
            }

        val targetMajor = parseMajor(release.versionName)
        if (targetMajor < minMajor) {
            return@withContext UpdateResult(
                updateRequired = true,
                installed = false,
                success = false,
                message = "chrome release too old: ${release.versionName}",
                currentVersion = currentVersion,
                release = release,
            ).also {
                log("Chrome APK 버전 부족: ${release.versionName}, 필요 major >= $minMajor")
            }
        }

        installChrome(release, currentVersion, deviceName)
    }

    private fun requiresChromeUpdate(currentMajor: Int?): Boolean {
        if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.O..Build.VERSION_CODES.P) return false
        return currentMajor == null || currentMajor < BuildConfig.MIN_CHROME_MAJOR
    }

    private fun installChrome(
        release: ChromeRelease,
        currentVersion: String?,
        deviceName: String,
    ): UpdateResult {
        log("Chrome APK 다운로드 중: ${release.versionName}")
        val apkFile = File(context.cacheDir, "chrome-${release.versionName}.apk")
        if (!download(release.apkUrl, apkFile)) {
            log("Chrome APK 다운로드 실패")
            return UpdateResult(
                updateRequired = true,
                installed = false,
                success = false,
                message = "chrome apk download failed",
                currentVersion = currentVersion,
                release = release,
            )
        }

        val downloaded = downloadedPackageInfo(apkFile) ?: return UpdateResult(
            updateRequired = true,
            installed = false,
            success = false,
            message = "downloaded chrome apk invalid",
            currentVersion = currentVersion,
            release = release,
        ).also {
            apkFile.delete()
            log("Chrome APK 검증 실패: 패키지 정보를 읽을 수 없음")
        }
        if (downloaded.packageName != release.packageName || downloaded.packageName != CHROME_PACKAGE) {
            apkFile.delete()
            log("Chrome APK 검증 실패: package=${downloaded.packageName}")
            return UpdateResult(
                updateRequired = true,
                installed = false,
                success = false,
                message = "downloaded package is not chrome: ${downloaded.packageName}",
                currentVersion = currentVersion,
                release = release,
            )
        }
        val downloadedMajor = parseMajor(downloaded.versionName.orEmpty())
        if (downloadedMajor < BuildConfig.MIN_CHROME_MAJOR) {
            apkFile.delete()
            log("Chrome APK 검증 실패: version=${downloaded.versionName}")
            return UpdateResult(
                updateRequired = true,
                installed = false,
                success = false,
                message = "downloaded chrome apk too old: ${downloaded.versionName}",
                currentVersion = currentVersion,
                release = release,
            )
        }
        log("Chrome APK 패키지 검증 완료: ${downloaded.versionName}")

        val expectedSha256 = release.sha256?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        if (expectedSha256 != null) {
            val actualSha256 = sha256(apkFile)
            if (!expectedSha256.equals(actualSha256, ignoreCase = true)) {
                apkFile.delete()
                log("Chrome APK sha256 불일치: expected=$expectedSha256 actual=$actualSha256")
                return UpdateResult(
                    updateRequired = true,
                    installed = false,
                    success = false,
                    message = "chrome apk sha256 mismatch",
                    currentVersion = currentVersion,
                    release = release,
                )
            }
            log("Chrome APK sha256 검증 완료")
        }

        log("Chrome 설치 중: ${release.versionName}")
        val installResult = rootInstall(apkFile)
        if (!installResult.success) {
            log("Chrome 설치 실패: ${installResult.message}")
            return UpdateResult(
                updateRequired = true,
                installed = false,
                success = false,
                message = installResult.message,
                currentVersion = currentVersion,
                release = release,
            )
        }

        log("Chrome 설치 완료: 앱 재시작 예약")
        scheduleAppRestart(deviceName)
        return UpdateResult(
            updateRequired = true,
            installed = true,
            success = true,
            message = "chrome installed ${release.versionName}",
            currentVersion = currentVersion,
            release = release,
        )
    }

    private fun currentWebViewProvider(): ProviderInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.getCurrentWebViewPackage()?.let {
                ProviderInfo(it.packageName, it.versionName)
            }
        } else {
            null
        }

    private fun currentPackageVersion(packageName: String): String? =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull()

    private fun downloadedPackageInfo(apkFile: File) =
        @Suppress("DEPRECATION")
        context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)

    private fun fetchLatestChromeFromServer(serverUrl: String, apiKey: String?): ChromeRelease? =
        runCatching {
            if (serverUrl.isBlank()) return null
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            val query = "package=$CHROME_PACKAGE&sdk=${Build.VERSION.SDK_INT}&abi=${enc(abi)}"
            val conn = URL("${serverUrl.trimEnd('/')}/android/chrome/latest?$query").openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            if (!apiKey.isNullOrBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            if (conn.responseCode !in 200..299) return null
            parseChromeRelease(conn.inputStream.bufferedReader().readText())
        }.getOrNull()

    private fun configuredChromeRelease(): ChromeRelease? {
        val url = BuildConfig.CHROME_UPDATE_APK_URL.trim().takeIf { it.isNotBlank() } ?: return null
        val versionName = Regex("""Chrome[/_-]?([0-9][0-9A-Za-z._-]*)""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?: "${BuildConfig.MIN_CHROME_MAJOR}.0.0.0"
        return ChromeRelease(
            versionName = versionName,
            apkUrl = url,
            sha256 = BuildConfig.CHROME_UPDATE_SHA256.trim().takeIf { it.isNotBlank() },
        )
    }

    private fun parseChromeRelease(json: String): ChromeRelease? {
        val body = json.trim().removePrefix("[").trimStart()
        val objEnd = findObjectEnd(body)
        if (objEnd < 0) return null
        val obj = body.substring(0, objEnd + 1)
        val versionName = readJsonString(obj, "version_name")
            ?: readJsonString(obj, "versionName")
            ?: return null
        val apkUrl = readJsonString(obj, "apk_url")
            ?: readJsonString(obj, "apkUrl")
            ?: return null
        val sha256 = readJsonString(obj, "sha256")
            ?: readJsonString(obj, "apk_sha256")
            ?: readJsonString(obj, "apkSha256")
        val packageName = readJsonString(obj, "package_name")
            ?: readJsonString(obj, "packageName")
            ?: CHROME_PACKAGE
        return ChromeRelease(versionName, apkUrl, sha256, packageName)
    }

    private fun readJsonString(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
            .find(json)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\/", "/")

    private fun findObjectEnd(s: String): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in s.indices) {
            val ch = s[i]
            if (inString) {
                escaped = if (escaped) {
                    false
                } else if (ch == '\\') {
                    true
                } else {
                    if (ch == '"') inString = false
                    false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun download(apkUrl: String, dest: File): Boolean =
        runCatching {
            val conn = URL(apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 180_000
            conn.connect()
            if (conn.responseCode !in 200..299) return false
            dest.outputStream().use { out -> conn.inputStream.copyTo(out) }
            true
        }.getOrDefault(false)

    private fun rootInstall(apkFile: File): ShellResult {
        val command = "pm install -r ${apkFile.absolutePath}"
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val finished = proc.waitFor(180, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroy()
            return ShellResult(false, "pm install timeout")
        }
        val stdout = proc.inputStream.bufferedReader().readText().trim()
        val stderr = proc.errorStream.bufferedReader().readText().trim()
        val exit = proc.exitValue()
        return ShellResult(
            success = exit == 0,
            message = "exit=$exit stdout=$stdout stderr=$stderr",
        )
    }

    private fun scheduleAppRestart(deviceName: String) {
        val component = "${context.packageName}/.ui.MainActivity"
        val safeDeviceName = deviceName.takeIf { it.matches(Regex("""[A-Za-z0-9._-]+""")) }.orEmpty()
        val deviceExtra = safeDeviceName.takeIf { it.isNotBlank() }?.let { " -e deviceName $it" }.orEmpty()
        val command = "sh -c 'sleep 2; am force-stop ${context.packageName}; sleep 1; am start -n $component -e autoRun true$deviceExtra' >/dev/null 2>&1 &"
        runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor() }
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

    private fun parseMajor(versionName: String): Int =
        versionName.substringBefore('.').filter { it.isDigit() }.toIntOrNull() ?: 0

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private data class ProviderInfo(val packageName: String, val versionName: String)
    private data class ShellResult(val success: Boolean, val message: String)

    companion object {
        private const val CHROME_PACKAGE = "com.android.chrome"
    }
}
