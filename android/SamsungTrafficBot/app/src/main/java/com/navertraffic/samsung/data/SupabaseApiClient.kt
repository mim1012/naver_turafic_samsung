package com.navertraffic.samsung.data

import com.navertraffic.samsung.strategy.StrategyATask
import com.navertraffic.samsung.strategy.StrategyGTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SupabaseApiClient(
    private val baseUrl: String,
    private val anonKey: String,
    private val slotTable: String = "sellermate_slot_naver",
) : StrategyTaskLeaseClient, CookieStorageClient, GroupControlClient {

    private val transport: HttpJsonTransport = UrlConnectionHttpJsonTransport()

    private fun now(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun base() = baseUrl.trimEnd('/')
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun jsonStr(value: String?): String {
        if (value == null) return "null"
        return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }

    private val headers
        get() = mapOf(
            "apikey" to anonKey,
            "Authorization" to "Bearer $anonKey",
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        )

    private val minimalHeaders
        get() = headers + ("Prefer" to "return=minimal")

    private val upsertHeaders
        get() = headers + ("Prefer" to "resolution=merge-duplicates,return=minimal")

    // ── Task Lease ───────────────────────────────────────────────────────────

    override suspend fun leaseTask(
        deviceName: String,
        role: DeviceIdentity.Role,
        strategy: String,
        appVersion: String,
    ): StrategyTaskLease? = withContext(Dispatchers.IO) {
        val url = "${base()}/$slotTable" +
            "?select=id,keyword,keyword_name,mid,link_url,success_count,fail_count,daily_target" +
            "&status=eq.${enc("작동중")}" +
            "&expiry_date=gt.${enc(now())}" +
            "&order=id.asc&limit=20"

        val raw = runCatching { transport.request("GET", url, null, headers) }.getOrNull()
            ?: return@withContext null

        val slot = parseJsonArray(raw).filter { row ->
            val mid = readString(row, "mid")?.takeIf { it.isNotBlank() }
            val keywordName = readString(row, "keyword_name")?.takeIf { it.isNotBlank() }
            val done = readInt(row, "success_count", 0) + readInt(row, "fail_count", 0)
            val target = readInt(row, "daily_target", 100)
            mid != null && keywordName != null && done < target
        }.randomOrNull() ?: return@withContext null

        val slotId = readInt(slot, "id", 0).takeIf { it > 0 } ?: return@withContext null
        val keyword = readString(slot, "keyword").orEmpty()
        val keywordName = readString(slot, "keyword_name").orEmpty()
        val mid = readString(slot, "mid").orEmpty()
        val linkUrl = readString(slot, "link_url").orEmpty()
        val leaseId = "sb_${slotId}_${System.currentTimeMillis()}"

        StrategyTaskLease(
            taskLeaseId = leaseId,
            taskG = StrategyGTask(
                keyword = keyword,
                keywordName = keywordName,
                linkUrl = linkUrl,
                mid = mid,
                productTitle = keywordName.takeIf { it.isNotBlank() },
            ),
        )
    }

    override suspend fun reportTask(report: StrategyTaskReport) = withContext(Dispatchers.IO) {
        val taskLeaseId = report.taskLeaseId ?: return@withContext
        if (!taskLeaseId.startsWith("sb_")) return@withContext
        val slotId = taskLeaseId.removePrefix("sb_").substringBefore("_").toIntOrNull()
            ?: return@withContext
        val success = report.result == StrategyTaskResult.SUCCESS

        val slotRaw = runCatching {
            transport.request(
                "GET",
                "${base()}/$slotTable?select=success_count,fail_count,keyword,keyword_name,link_url&id=eq.$slotId&limit=1",
                null,
                headers,
            )
        }.getOrNull()
        val row = slotRaw?.let { parseJsonArray(it).firstOrNull() }
        val successCount = row?.let { readInt(it, "success_count", 0) } ?: 0
        val failCount = row?.let { readInt(it, "fail_count", 0) } ?: 0

        val patch = if (success) """{"success_count":${successCount + 1}}""" else """{"fail_count":${failCount + 1}}"""
        runCatching {
            transport.request("PATCH", "${base()}/$slotTable?id=eq.$slotId", patch, minimalHeaders)
        }

        runCatching {
            transport.request(
                "POST",
                "${base()}/slot_rank_naverapp_history",
                buildHistoryJson(slotId, row),
                minimalHeaders,
            )
        }
        Unit
    }

    private fun buildHistoryJson(slotId: Int, row: String?): String {
        val keyword = row?.let { readString(it, "keyword") }
        val keywordName = row?.let { readString(it, "keyword_name") }
        val linkUrl = row?.let { readString(it, "link_url") }
        return """{"slot_status_id":$slotId,"source_table":${jsonStr(slotTable)},"source_row_id":$slotId,"customer_id":null,"keyword":${jsonStr(keyword)},"link_url":${jsonStr(linkUrl)},"keyword_name":${jsonStr(keywordName)},"created_at":${jsonStr(now())}}"""
    }

    // ── Cookies ──────────────────────────────────────────────────────────────

    override suspend fun saveCookies(deviceName: String, cookies: String) = withContext(Dispatchers.IO) {
        val body = """{"device_name":${jsonStr(deviceName)},"cookies":${jsonStr(cookies)},"updated_at":${jsonStr(now())}}"""
        runCatching {
            transport.request("POST", "${base()}/device_cookies?on_conflict=device_name", body, upsertHeaders)
        }
        Unit
    }

    override suspend fun loadCookies(deviceName: String): String? = withContext(Dispatchers.IO) {
        val url = "${base()}/device_cookies?device_name=eq.${enc(deviceName)}&limit=1"
        val raw = runCatching { transport.request("GET", url, null, headers) }.getOrNull()
            ?: return@withContext null
        val row = parseJsonArray(raw).firstOrNull() ?: return@withContext null
        readString(row, "cookies")?.takeIf { it.isNotBlank() }
    }

    // ── Group Control ─────────────────────────────────────────────────────────
    // Supabase table: device_group(group_id TEXT PK, state TEXT DEFAULT 'READY', updated_at TIMESTAMPTZ)

    override suspend fun heartbeat(heartbeat: DeviceHeartbeat): GroupControlResponse? {
        if (heartbeat.role == DeviceIdentity.Role.BOSS) return null
        return withContext(Dispatchers.IO) {
            val raw = runCatching {
                transport.request(
                    "GET",
                    "${base()}/device_group?group_id=eq.${enc(heartbeat.groupId)}&limit=1",
                    null,
                    headers,
                )
            }.getOrNull() ?: return@withContext null
            val row = parseJsonArray(raw).firstOrNull() ?: return@withContext null
            val state = readString(row, "state") ?: "READY"
            val pausing = state == "DRAINING" || state == "ROTATING"
            GroupControlResponse(
                groupState = if (state == "ROTATING") GroupState.ROTATING else if (state == "DRAINING") GroupState.DRAINING else GroupState.READY,
                command = if (pausing) GroupCommand.PAUSE_FOR_ROTATION else GroupCommand.NONE,
                commandId = null,
                policy = GroupPolicy(
                    rotateOwner = heartbeat.groupId,
                    rotateEveryGroupTasks = 10,
                    drainTimeoutSec = 120,
                    pauseSoldiersDuringRotation = true,
                    pauseOnRotationFail = true,
                ),
            )
        }
    }

    override suspend fun startRotation(groupId: String) = withContext(Dispatchers.IO) {
        val body = """{"group_id":${jsonStr(groupId)},"state":"DRAINING","updated_at":${jsonStr(now())}}"""
        runCatching {
            transport.request("POST", "${base()}/device_group?on_conflict=group_id", body, upsertHeaders)
        }
        Unit
    }

    override suspend fun reportRotation(report: RotationReport) = withContext(Dispatchers.IO) {
        val state = if (report.success) "READY" else "ROTATION_FAILED"
        val body = """{"group_id":${jsonStr(report.groupId)},"state":"$state","updated_at":${jsonStr(now())}}"""
        runCatching {
            transport.request("POST", "${base()}/device_group?on_conflict=group_id", body, upsertHeaders)
        }
        Unit
    }
}
