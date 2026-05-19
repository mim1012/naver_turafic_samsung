package com.navertraffic.samsung.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.navertraffic.samsung.strategy.StrategyATask
import com.navertraffic.samsung.strategy.StrategyGTask
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class AndroidServerApiClient(
    private val baseUrl: String,
    private val apiKey: String? = null,
    private val transport: HttpJsonTransport = UrlConnectionHttpJsonTransport(),
) : AccountLeaseClient, GroupControlClient, StrategyTaskLeaseClient, CookieStorageClient {
    override suspend fun leaseAccount(
        deviceName: String,
        role: DeviceIdentity.Role,
        strategy: String,
        appVersion: String,
    ): AccountLease? {
        val body = jsonBody(
            "deviceName" to deviceName,
            "role" to role.apiName(),
            "strategy" to strategy,
            "appVersion" to appVersion,
        )

        return AndroidServerApiJson.parseAccountLease(post("/android/accounts/lease", body))
    }

    suspend fun currentAccount(
        deviceName: String,
        role: DeviceIdentity.Role,
        strategy: String,
        appVersion: String,
    ): AccountLease? {
        val body = jsonBody(
            "deviceName" to deviceName,
            "role" to role.apiName(),
            "strategy" to strategy,
            "appVersion" to appVersion,
        )

        return AndroidServerApiJson.parseCurrentAccount(post("/android/accounts/current", body))
    }

    override suspend fun report(report: AccountLeaseReport) {
        post("/android/accounts/report", AndroidServerApiJson.accountReportBody(report))
    }

    override suspend fun release(
        leaseId: String,
        deviceName: String,
        reason: String,
    ) {
        val body = jsonBody(
            "leaseId" to leaseId,
            "deviceName" to deviceName,
            "reason" to reason,
        )

        post("/android/accounts/release", body)
    }

    override suspend fun heartbeat(heartbeat: DeviceHeartbeat): GroupControlResponse? {
        val raw = post("/android/heartbeat", AndroidServerApiJson.deviceHeartbeatBody(heartbeat))
        if (raw.isBlank() || raw.trim() == "{}") return null
        return AndroidServerApiJson.parseGroupControlResponse(raw)
    }

    override suspend fun reportRotation(report: RotationReport) {
        post("/android/group/rotation-report", AndroidServerApiJson.rotationReportBody(report))
    }

    override suspend fun leaseTask(
        deviceName: String,
        role: DeviceIdentity.Role,
        strategy: String,
        appVersion: String,
    ): StrategyTaskLease? {
        val body = jsonBody(
            "deviceName" to deviceName,
            "role" to role.apiName(),
            "strategy" to strategy,
            "appVersion" to appVersion,
        )

        return AndroidServerApiJson.parseStrategyTaskLease(post("/android/tasks/lease", body))
    }

    override suspend fun reportTask(report: StrategyTaskReport) {
        post("/android/tasks/report", AndroidServerApiJson.strategyTaskReportBody(report))
    }

    override suspend fun saveCookies(deviceName: String, accountAlias: String?, cookies: String) {
        post("/android/cookies/save", AndroidServerApiJson.cookieSaveBody(deviceName, accountAlias, cookies))
    }

    override suspend fun loadCookies(deviceName: String, accountAlias: String?): String? {
        val raw = post("/android/cookies/load", AndroidServerApiJson.cookieLoadBody(deviceName, accountAlias))
        return readString(raw, "cookies")?.takeIf { it.isNotBlank() }
    }

    private suspend fun post(path: String, body: JsonBody): String {
        val normalizedBase = baseUrl.trimEnd('/')
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return transport.postJson(
            url = normalizedBase + normalizedPath,
            body = body.text,
            headers = buildMap {
                put("Content-Type", "application/json; charset=utf-8")
                put("Accept", "application/json")
                apiKey?.takeIf { it.isNotBlank() }?.let {
                    put("Authorization", "Bearer $it")
                }
            },
        )
    }
}

data class JsonBody(val text: String) {
    fun getString(key: String): String = readString(text, key).orEmpty()
}

object AndroidServerApiJson {
    fun parseAccountLease(raw: String): AccountLease? {
        val leaseId = readString(raw, "leaseId")?.takeIf { it.isNotBlank() } ?: return null
        return AccountLease(
            leaseId = leaseId,
            accountAlias = readString(raw, "accountAlias").orEmpty(),
            loginId = readString(raw, "loginId").orEmpty(),
            password = readString(raw, "password").orEmpty(),
            expiresAt = readString(raw, "expiresAt").orEmpty(),
        )
    }

    fun parseCurrentAccount(raw: String): AccountLease? {
        val loginId = readString(raw, "loginId")?.takeIf { it.isNotBlank() } ?: return null
        val password = readString(raw, "password")?.takeIf { it.isNotBlank() } ?: return null
        return AccountLease(
            leaseId = readString(raw, "leaseId").orEmpty(),
            accountAlias = readString(raw, "accountAlias").orEmpty(),
            loginId = loginId,
            password = password,
            expiresAt = readString(raw, "expiresAt").orEmpty(),
        )
    }

    fun parseGroupControlResponse(raw: String): GroupControlResponse {
        val policy = readObject(raw, "policy").orEmpty()
        return GroupControlResponse(
            groupState = readEnum(raw, "groupState", GroupState.READY),
            command = readEnum(raw, "command", GroupCommand.NONE),
            commandId = readString(raw, "commandId")?.takeIf { it.isNotBlank() },
            policy = GroupPolicy(
                rotateOwner = readString(policy, "rotateOwner").orEmpty(),
                rotateEveryGroupTasks = readInt(policy, "rotateEveryGroupTasks", 1),
                drainTimeoutSec = readInt(policy, "drainTimeoutSec", 120),
                pauseSoldiersDuringRotation = readBoolean(policy, "pauseSoldiersDuringRotation", true),
                pauseOnRotationFail = readBoolean(policy, "pauseOnRotationFail", true),
            ),
        )
    }

    fun parseStrategyTaskLease(raw: String): StrategyTaskLease? {
        val taskLeaseId = readString(raw, "taskLeaseId")?.takeIf { it.isNotBlank() } ?: return null
        val keywordName = readString(raw, "keywordName")?.takeIf { it.isNotBlank() }
        val mid = readString(raw, "mid")?.takeIf { it.isNotBlank() }

        return if (keywordName != null && mid != null) {
            StrategyTaskLease(
                taskLeaseId = taskLeaseId,
                taskG = StrategyGTask(
                    keyword = readString(raw, "keyword").orEmpty(),
                    keywordName = keywordName,
                    linkUrl = readString(raw, "linkUrl").orEmpty(),
                    mid = mid,
                    productTitle = readString(raw, "productTitle")?.takeIf { it.isNotBlank() },
                ),
            )
        } else {
            StrategyTaskLease(
                taskLeaseId = taskLeaseId,
                task = StrategyATask(
                    keyword = readString(raw, "keyword").orEmpty(),
                    secondKeyword = readString(raw, "secondKeyword").orEmpty(),
                    linkUrl = readString(raw, "linkUrl").orEmpty(),
                    mid = mid,
                    productTitle = readString(raw, "productTitle")?.takeIf { it.isNotBlank() },
                ),
            )
        }
    }

    fun deviceHeartbeatBody(heartbeat: DeviceHeartbeat): JsonBody {
        return jsonBody(
            "deviceName" to heartbeat.deviceName,
            "groupId" to heartbeat.groupId,
            "role" to heartbeat.role.apiName(),
            "state" to heartbeat.state.name,
            "taskCount" to heartbeat.taskCount,
            "appVersion" to heartbeat.appVersion,
            "currentIp" to heartbeat.currentIp,
            "lastError" to heartbeat.lastError,
        )
    }

    fun cookieSaveBody(deviceName: String, accountAlias: String?, cookies: String): JsonBody {
        return jsonBody(
            "deviceName" to deviceName,
            "accountAlias" to accountAlias?.takeIf { it.isNotBlank() },
            "cookies" to cookies,
        )
    }

    fun cookieLoadBody(deviceName: String, accountAlias: String?): JsonBody {
        return jsonBody(
            "deviceName" to deviceName,
            "accountAlias" to accountAlias?.takeIf { it.isNotBlank() },
        )
    }

    fun accountReportBody(report: AccountLeaseReport): JsonBody {
        return jsonBody(
            "leaseId" to report.leaseId,
            "deviceName" to report.deviceName,
            "result" to report.result.apiName(),
            "signals" to JsonArray(report.signals.map { it.name }),
            "lastUrl" to report.lastUrl,
            "message" to report.message,
        )
    }

    fun rotationReportBody(report: RotationReport): JsonBody {
        return jsonBody(
            "commandId" to report.commandId,
            "deviceName" to report.deviceName,
            "groupId" to report.groupId,
            "beforeIp" to report.beforeIp,
            "afterIp" to report.afterIp,
            "success" to report.success,
            "message" to report.message,
        )
    }

    fun strategyTaskReportBody(report: StrategyTaskReport): JsonBody {
        return jsonBody(
            "taskLeaseId" to report.taskLeaseId,
            "deviceName" to report.deviceName,
            "result" to report.result.apiName(),
            "message" to report.message,
        )
    }
}

interface HttpJsonTransport {
    suspend fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
    ): String

    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): String = request("POST", url, body, headers)
}

class UrlConnectionHttpJsonTransport : HttpJsonTransport {
    override suspend fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            if (body != null) doOutput = true
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }

        try {
            if (body != null) {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else (connection.errorStream ?: connection.inputStream)
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code: $response")
            response
        } finally {
            connection.disconnect()
        }
    }
}

private fun DeviceIdentity.Role.apiName(): String = name.lowercase()

private fun AccountLeaseResult.apiName(): String = name.lowercase()

private fun StrategyTaskResult.apiName(): String = name.lowercase()

private data class JsonArray(val values: List<String>)

private fun jsonBody(vararg pairs: Pair<String, Any?>): JsonBody {
    return JsonBody(
        pairs.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${escapeJson(key)}\":${encodeJson(value)}"
        },
    )
}

private fun encodeJson(value: Any?): String {
    return when (value) {
        null -> "null"
        is String -> "\"${escapeJson(value)}\""
        is Number,
        is Boolean -> value.toString()
        is JsonArray -> value.values.joinToString(prefix = "[", postfix = "]") { "\"${escapeJson(it)}\"" }
        else -> "\"${escapeJson(value.toString())}\""
    }
}

private fun escapeJson(value: String): String {
    return buildString {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}

internal fun parseJsonArray(raw: String): List<String> {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("[")) return emptyList()
    val results = mutableListOf<String>()
    var depth = 0
    var start = -1
    var inString = false
    var escape = false
    for (i in trimmed.indices) {
        val c = trimmed[i]
        if (escape) { escape = false; continue }
        if (c == '\\' && inString) { escape = true; continue }
        if (c == '"') { inString = !inString; continue }
        if (inString) continue
        when (c) {
            '{' -> { if (depth == 0) start = i; depth++ }
            '}' -> { depth--; if (depth == 0 && start >= 0) { results.add(trimmed.substring(start, i + 1)); start = -1 } }
        }
    }
    return results
}

internal fun readString(raw: String, key: String): String? {
    val match = Regex(""""${Regex.escape(key)}"\s*:\s*(null|"((?:\\.|[^"])*)")""")
        .find(raw) ?: return null
    if (match.groupValues[1] == "null") return null
    return unescapeJson(match.groupValues[2])
}

internal fun readInt(raw: String, key: String, default: Int): Int {
    return Regex(""""${Regex.escape(key)}"\s*:\s*(-?\d+)""")
        .find(raw)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
        ?: default
}

private fun readBoolean(raw: String, key: String, default: Boolean): Boolean {
    return Regex(""""${Regex.escape(key)}"\s*:\s*(true|false)""", RegexOption.IGNORE_CASE)
        .find(raw)
        ?.groupValues
        ?.get(1)
        ?.toBooleanStrictOrNull()
        ?: default
}

private fun readObject(raw: String, key: String): String? {
    val start = Regex(""""${Regex.escape(key)}"\s*:\s*\{""").find(raw)?.range?.last ?: return null
    var depth = 1
    var index = start + 1
    while (index < raw.length) {
        when (raw[index]) {
            '{' -> depth += 1
            '}' -> {
                depth -= 1
                if (depth == 0) return raw.substring(start, index + 1)
            }
        }
        index += 1
    }
    return null
}

private inline fun <reified T : Enum<T>> readEnum(raw: String, key: String, default: T): T {
    val value = readString(raw, key)?.takeIf { it.isNotBlank() } ?: return default
    return enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: default
}

private fun unescapeJson(value: String): String {
    return value
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}
