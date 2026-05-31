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
import java.util.concurrent.ConcurrentHashMap

class SupabaseApiClient(
    private val baseUrl: String,
    private val anonKey: String,
    private val trafficTable: String = "sellermate_traffic_navershopping",
    private val slotTable: String = "sellermate_slot_naver",
    private val claimTtlSeconds: Int = 60,
    private val allowRawRestFallback: Boolean = true,
    private val transport: HttpJsonTransport = UrlConnectionHttpJsonTransport(),
) : StrategyTaskLeaseClient, CookieStorageClient, GroupControlClient {

    // leaseId -> link_url cache for failure history fallback.
    private val leaseLinkUrls = ConcurrentHashMap<String, String>()
    private val rpcClaims = ConcurrentHashMap<String, DirectTaskClaim>()
    private val reportedTaskLeaseIds = ConcurrentHashMap.newKeySet<String>()

    private fun now(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun base() = baseUrl.trimEnd('/')
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun cookieAlias(accountAlias: String?) = accountAlias?.trim().orEmpty()

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
        val rpcLease = runCatching { leaseTaskViaRpc(deviceName, strategy) }
        if (rpcLease.isSuccess || !allowRawRestFallback) {
            return@withContext rpcLease.getOrThrow()
        }

        // Local-dev fallback only. Production devices should use serverUrl, and direct
        // Supabase mode must be explicitly enabled in local.properties.
        leaseTaskViaRawRest()
    }

    private suspend fun leaseTaskViaRpc(deviceName: String, strategy: String): StrategyTaskLease? {
        val body = """{"p_device_name":${jsonStr(deviceName)},"p_strategy":${jsonStr(strategy)},"p_claim_ttl_seconds":$claimTtlSeconds}"""
        val raw = transport.request("POST", "${base()}/rpc/claim_android_naver_task", body, headers)
        val row = parseRpcRow(raw) ?: return null
        val taskId = readString(row, "task_id")
            ?: readInt(row, "traffic_id", 0).takeIf { it > 0 }?.toString()
            ?: return null
        val leaseId = readString(row, "lease_id")
            ?: "sb_rpc_${taskId}_${readInt(row, "slot_id", 0)}"
        val slotId = readInt(row, "slot_id", 0)
        val keyword = readString(row, "keyword").orEmpty()
        val keywordName = readString(row, "keyword_name").orEmpty()
        val linkUrl = readString(row, "link_url").orEmpty()
        val mid = readString(row, "mid").orEmpty()
        val productName = readString(row, "product_name")
            ?: readString(row, "productName")
            ?: readString(row, "product_title")
        val catalogMid = readString(row, "catalog_mid") ?: readString(row, "catalogMid")
        val strategyGroup = readString(row, "strategy_group")
        val strategyVersion = readString(row, "strategy_version")

        rpcClaims[leaseId] = DirectTaskClaim(
            taskId = taskId,
            leaseId = leaseId,
            slotId = slotId,
            linkUrl = linkUrl,
        )
        if (linkUrl.isNotBlank()) leaseLinkUrls[leaseId] = linkUrl

        return StrategyTaskLease(
            taskLeaseId = leaseId,
            strategyGroup = strategyGroup,
            strategyVersion = strategyVersion,
            task = StrategyATask(
                keyword = keyword,
                secondKeyword = keywordName,
                linkUrl = linkUrl,
                mid = mid,
                productTitle = keywordName.takeIf { it.isNotBlank() },
                tailKeyword = keyword.split(" ").lastOrNull().orEmpty(),
            ),
            taskG = StrategyGTask(
                keyword = keyword,
                keywordName = keywordName,
                linkUrl = linkUrl,
                mid = mid,
                productTitle = keywordName.takeIf { it.isNotBlank() },
                productName = productName?.takeIf { it.isNotBlank() },
                catalogMid = catalogMid?.takeIf { it.isNotBlank() },
            ),
        )
    }

    private suspend fun leaseTaskViaRawRest(): StrategyTaskLease? {
        // 1. 트래픽 큐에서 1건 가져오기 (id 오름차순)
        val trafficUrl = "${base()}/$trafficTable" +
            "?select=id,keyword,keyword_name,link_url,slot_id,strategy_group,strategy_version" +
            "&order=id.asc&limit=1"
        val trafficRaw = runCatching { transport.request("GET", trafficUrl, null, headers) }.getOrNull()
            ?: return null

        val trafficRow = parseJsonArray(trafficRaw).firstOrNull() ?: return null
        val trafficId = readInt(trafficRow, "id", 0).takeIf { it > 0 } ?: return null
        val slotId = readInt(trafficRow, "slot_id", 0).takeIf { it > 0 } ?: return null
        val keyword = readString(trafficRow, "keyword").orEmpty()
        val keywordName = readString(trafficRow, "keyword_name").orEmpty()
        val linkUrl = readString(trafficRow, "link_url").orEmpty()
        val productName = readString(trafficRow, "product_name")
            ?: readString(trafficRow, "productName")
            ?: readString(trafficRow, "product_title")
        val catalogMid = readString(trafficRow, "catalog_mid") ?: readString(trafficRow, "catalogMid")
        val strategyGroup = readString(trafficRow, "strategy_group")
        val strategyVersion = readString(trafficRow, "strategy_version")

        // 2. 트래픽 행 DELETE (클레임)
        runCatching {
            transport.request("DELETE", "${base()}/$trafficTable?id=eq.$trafficId", null, minimalHeaders)
        }

        // 3. 슬롯에서 mid 조회
        val slotRaw = runCatching {
            transport.request("GET", "${base()}/$slotTable?select=mid&id=eq.$slotId&limit=1", null, headers)
        }.getOrNull()
        val mid = slotRaw?.let { parseJsonArray(it).firstOrNull() }
            ?.let { readString(it, "mid") }.orEmpty()

        val leaseId = "sb_${trafficId}_${slotId}"
        if (linkUrl.isNotBlank()) leaseLinkUrls[leaseId] = linkUrl

        return StrategyTaskLease(
            taskLeaseId = leaseId,
            strategyGroup = strategyGroup,
            strategyVersion = strategyVersion,
            task = StrategyATask(
                keyword = keyword,
                secondKeyword = keywordName,
                linkUrl = linkUrl,
                mid = mid,
                productTitle = keywordName.takeIf { it.isNotBlank() },
                tailKeyword = keyword.split(" ").last(),
            ),
            taskG = StrategyGTask(
                keyword = keyword,
                keywordName = keywordName,
                linkUrl = linkUrl,
                mid = mid,
                productTitle = keywordName.takeIf { it.isNotBlank() },
                productName = productName?.takeIf { it.isNotBlank() },
                catalogMid = catalogMid?.takeIf { it.isNotBlank() },
            ),
        )
    }

    override suspend fun reportTask(report: StrategyTaskReport) = withContext(Dispatchers.IO) {
        val taskLeaseId = report.taskLeaseId ?: return@withContext
        if (reportedTaskLeaseIds.contains(taskLeaseId)) return@withContext
        val rpcClaim = rpcClaims[taskLeaseId]
        val rpcReported = if (rpcClaim != null) {
            runCatching { reportTaskViaRpc(report, rpcClaim) }
                .onSuccess {
                    rpcClaims.remove(taskLeaseId)
                    leaseLinkUrls.remove(taskLeaseId)
                    reportedTaskLeaseIds.add(taskLeaseId)
                }
                .isSuccess
        } else {
            runCatching { reportTaskViaRpc(report, null) }
                .onSuccess { reportedTaskLeaseIds.add(taskLeaseId) }
                .isSuccess
        }
        if (rpcReported || !allowRawRestFallback) return@withContext

        // Local-dev fallback only. Kept for staged RPC rollout against developer Supabase projects.
        reportTaskViaRawRest(report)
        reportedTaskLeaseIds.add(taskLeaseId)
    }

    private suspend fun reportTaskViaRpc(report: StrategyTaskReport, claim: DirectTaskClaim?) {
        val taskLeaseId = report.taskLeaseId ?: return
        val success = report.result == StrategyTaskResult.SUCCESS
        val taskId = claim?.taskId ?: taskLeaseId
        val leaseId = claim?.leaseId ?: taskLeaseId
        val linkUrl = claim?.linkUrl?.takeIf { it.isNotBlank() } ?: leaseLinkUrls[taskLeaseId]
        val idempotencyKey = "$taskId:$leaseId:${report.deviceName}"
        val body = """{"p_task_id":${jsonStr(taskId)},"p_lease_id":${jsonStr(leaseId)},"p_device_name":${jsonStr(report.deviceName)},"p_success":$success,"p_link_url":${jsonStr(linkUrl)},"p_source":"android_direct_supabase_dev","p_idempotency_key":${jsonStr(idempotencyKey)}}"""
        transport.request("POST", "${base()}/rpc/report_android_naver_task", body, minimalHeaders)
        recordKeywordFailureIfNeeded(report, claim, taskId, leaseId, linkUrl, idempotencyKey)
    }

    private suspend fun reportTaskViaRawRest(report: StrategyTaskReport) {
        val taskLeaseId = report.taskLeaseId ?: return
        if (!taskLeaseId.startsWith("sb_")) return
        // leaseId 형식: sb_{trafficId}_{slotId}
        val slotId = taskLeaseId.removePrefix("sb_").substringAfterLast("_").toIntOrNull()
            ?: return
        val success = report.result == StrategyTaskResult.SUCCESS
        val cachedLinkUrl = leaseLinkUrls.remove(taskLeaseId)

        val slotRaw = runCatching {
            transport.request(
                "GET",
                "${base()}/$slotTable?select=success_count,fail_count,keyword,keyword_name&id=eq.$slotId&limit=1",
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

        if (success) return

        runCatching {
            transport.request(
                "POST",
                "${base()}/slot_rank_naverapp_history",
                buildHistoryJson(slotId, row, cachedLinkUrl),
                minimalHeaders,
            )
        }
        Unit
    }

    private suspend fun recordKeywordFailureIfNeeded(
        report: StrategyTaskReport,
        claim: DirectTaskClaim?,
        taskId: String,
        leaseId: String,
        linkUrl: String?,
        idempotencyKey: String,
    ) {
        if (report.result == StrategyTaskResult.SUCCESS) return
        val failureReason = report.failureReason ?: inferFailureReason(report.message) ?: return
        if (!failureReason.isKeywordBlacklistCandidate()) return
        val queryPhrase = report.queryPhrase?.trim()?.takeIf { it.isNotBlank() } ?: return
        val slotId = claim?.slotId ?: return
        keywordFailureStrategies(report.strategyGroup).forEach { strategy ->
            val strategyKey = "$idempotencyKey:keyword_failure:$strategy"
            val body = """{"p_task_id":${jsonStr(taskId)},"p_lease_id":${jsonStr(leaseId)},"p_device_name":${jsonStr(report.deviceName)},"p_slot_id":$slotId,"p_strategy":${jsonStr(strategy)},"p_failure_reason":${jsonStr(failureReason)},"p_query_phrase":${jsonStr(queryPhrase)},"p_message":${jsonStr(report.message)},"p_link_url":${jsonStr(linkUrl)},"p_final_url":${jsonStr(report.finalUrl)},"p_mid_found":${report.midFound ?: "null"},"p_detail_status":${jsonStr(report.detailStatus)},"p_idempotency_key":${jsonStr(strategyKey)}}"""
            runCatching {
                transport.request("POST", "${base()}/rpc/record_android_keyword_failure", body, minimalHeaders)
            }
        }
    }

    private fun keywordFailureStrategies(strategyGroup: String?): List<String> {
        val strategy = strategyGroup?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: "G"
        return if (strategy == "G") listOf("G", "A") else listOf(strategy)
    }

    private fun inferFailureReason(message: String?): String? {
        val text = message?.trim().orEmpty()
        return when {
            text.startsWith("MID product not found after exploration") -> "mid_product_not_found_after_exploration"
            text.startsWith("MID product not found after timeout") -> "mid_product_not_found_after_timeout"
            text == "rate_limited_429" -> "rate_limited_429"
            text.startsWith("detail_dom_not_confirmed") -> "detail_dom_not_confirmed"
            text.isBlank() -> null
            else -> text.substringBefore(':').take(80)
        }
    }

    private fun String.isKeywordBlacklistCandidate(): Boolean {
        return this == "mid_product_not_found_after_exploration" ||
            this == "mid_product_not_found_after_timeout"
    }

    private fun parseRpcRow(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed == "null" || trimmed == "{}" || trimmed == "[]") return null
        if (trimmed.startsWith("[")) return parseJsonArray(trimmed).firstOrNull()
        if (trimmed.startsWith("{")) return trimmed
        return null
    }

    private data class DirectTaskClaim(
        val taskId: String,
        val leaseId: String,
        val slotId: Int,
        val linkUrl: String,
    )

    private fun buildHistoryJson(slotId: Int, row: String?, linkUrl: String?): String {
        val keyword = row?.let { readString(it, "keyword") }
        val keywordName = row?.let { readString(it, "keyword_name") }
        val resolvedLinkUrl = linkUrl?.takeIf { it.isNotBlank() } ?: ""
        return """{"slot_status_id":$slotId,"source_table":${jsonStr(slotTable)},"source_row_id":$slotId,"customer_id":null,"keyword":${jsonStr(keyword)},"link_url":${jsonStr(resolvedLinkUrl)},"keyword_name":${jsonStr(keywordName)},"created_at":${jsonStr(now())}}"""
    }

    // ── Cookies ──────────────────────────────────────────────────────────────

    override suspend fun saveCookies(deviceName: String, accountAlias: String?, cookies: String) = withContext(Dispatchers.IO) {
        val alias = cookieAlias(accountAlias)
        val body = """{"device_name":${jsonStr(deviceName)},"account_alias":${jsonStr(alias)},"cookies":${jsonStr(cookies)},"updated_at":${jsonStr(now())}}"""
        runCatching {
            transport.request("POST", "${base()}/device_cookies?on_conflict=device_name,account_alias", body, upsertHeaders)
        }.onFailure {
            runCatching {
                transport.request("POST", "${base()}/device_cookies?on_conflict=device_name", body, upsertHeaders)
            }
        }
        Unit
    }

    override suspend fun loadCookies(deviceName: String, accountAlias: String?): String? = withContext(Dispatchers.IO) {
        val alias = cookieAlias(accountAlias)
        val url = "${base()}/device_cookies?device_name=eq.${enc(deviceName)}&account_alias=eq.${enc(alias)}&limit=1"
        val raw = runCatching { transport.request("GET", url, null, headers) }.getOrNull()
        val row = raw?.let { parseJsonArray(it).firstOrNull() }
        if (row != null) return@withContext readString(row, "cookies")?.takeIf { it.isNotBlank() }
        if (alias.isNotBlank()) return@withContext null

        val legacyUrl = "${base()}/device_cookies?device_name=eq.${enc(deviceName)}&account_alias=is.null&limit=1"
        val legacyRaw = runCatching { transport.request("GET", legacyUrl, null, headers) }.getOrNull()
            ?: return@withContext null
        val legacyRow = parseJsonArray(legacyRaw).firstOrNull() ?: return@withContext null
        readString(legacyRow, "cookies")?.takeIf { it.isNotBlank() }
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
