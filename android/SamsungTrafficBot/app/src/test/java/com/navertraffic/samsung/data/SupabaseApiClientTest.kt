package com.navertraffic.samsung.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseApiClientTest {
    @Test
    fun directLeaseUsesClaimRpc() = runBlocking {
        val transport = RecordingTransport(
            "POST https://example.supabase.co/rpc/claim_android_naver_task" to """
                {
                  "task_id": "traffic_10",
                  "lease_id": "lease_10",
                  "traffic_id": 10,
                  "slot_id": 20,
                  "keyword": "first keyword",
                  "keyword_name": "second keyword",
                  "link_url": "https://smartstore.naver.com/store/products/123",
                  "mid": "123"
                }
            """.trimIndent(),
        )
        val client = SupabaseApiClient(
            baseUrl = "https://example.supabase.co/",
            anonKey = "anon",
            allowRawRestFallback = false,
            transport = transport,
        )

        val lease = client.leaseTask("z1-1", DeviceIdentity.Role.SOLDIER, "G", "0.1.20")

        assertEquals("lease_10", lease?.taskLeaseId)
        assertEquals("first keyword", lease?.taskG?.keyword)
        assertEquals("second keyword", lease?.taskG?.keywordName)
        assertEquals("123", lease?.taskG?.mid)
        assertEquals(listOf("POST https://example.supabase.co/rpc/claim_android_naver_task"), transport.calls.map { it.signature })
        assertTrue(transport.calls.single().body.orEmpty().contains(""""p_device_name":"z1-1""""))
        assertTrue(transport.calls.single().body.orEmpty().contains(""""p_strategy":"G""""))
    }

    @Test
    fun directReportUsesReportRpcWithStableIdempotencyKey() = runBlocking {
        val transport = RecordingTransport(
            "POST https://example.supabase.co/rpc/claim_android_naver_task" to """
                {
                  "task_id": "traffic_10",
                  "lease_id": "lease_10",
                  "traffic_id": 10,
                  "slot_id": 20,
                  "keyword": "first keyword",
                  "keyword_name": "second keyword",
                  "link_url": "https://smartstore.naver.com/store/products/123",
                  "mid": "123"
                }
            """.trimIndent(),
            "POST https://example.supabase.co/rpc/report_android_naver_task" to "{}",
        )
        val client = SupabaseApiClient(
            baseUrl = "https://example.supabase.co",
            anonKey = "anon",
            allowRawRestFallback = false,
            transport = transport,
        )
        val lease = client.leaseTask("z1-1", DeviceIdentity.Role.SOLDIER, "G", "0.1.20")

        client.reportTask(
            StrategyTaskReport(
                taskLeaseId = lease?.taskLeaseId,
                deviceName = "z1-1",
                result = StrategyTaskResult.SUCCESS,
                message = "done",
            ),
        )

        val signatures = transport.calls.map { it.signature }
        assertEquals(
            listOf(
                "POST https://example.supabase.co/rpc/claim_android_naver_task",
                "POST https://example.supabase.co/rpc/report_android_naver_task",
            ),
            signatures,
        )
        assertFalse(signatures.any { it.contains("sellermate_traffic_navershopping") })
        assertFalse(signatures.any { it.contains("sellermate_slot_naver") })

        val reportBody = transport.calls.last().body.orEmpty()
        assertTrue(reportBody.contains(""""p_task_id":"traffic_10""""))
        assertTrue(reportBody.contains(""""p_lease_id":"lease_10""""))
        assertTrue(reportBody.contains(""""p_device_name":"z1-1""""))
        assertTrue(reportBody.contains(""""p_success":true"""))
        assertTrue(reportBody.contains(""""p_idempotency_key":"traffic_10:lease_10:z1-1""""))
    }

    @Test
    fun directReportSuppressesDuplicateLeaseReportsInProcess() = runBlocking {
        val transport = RecordingTransport(
            "POST https://example.supabase.co/rpc/claim_android_naver_task" to """
                {
                  "task_id": "traffic_10",
                  "lease_id": "lease_10",
                  "traffic_id": 10,
                  "slot_id": 20,
                  "keyword": "first keyword",
                  "keyword_name": "second keyword",
                  "link_url": "https://smartstore.naver.com/store/products/123",
                  "mid": "123"
                }
            """.trimIndent(),
            "POST https://example.supabase.co/rpc/report_android_naver_task" to "{}",
        )
        val client = SupabaseApiClient(
            baseUrl = "https://example.supabase.co",
            anonKey = "anon",
            allowRawRestFallback = false,
            transport = transport,
        )
        val lease = client.leaseTask("z1-1", DeviceIdentity.Role.SOLDIER, "G", "0.1.20")
        val report = StrategyTaskReport(
            taskLeaseId = lease?.taskLeaseId,
            deviceName = "z1-1",
            result = StrategyTaskResult.SUCCESS,
            message = "done",
        )

        client.reportTask(report)
        client.reportTask(report)

        val reportCalls = transport.calls.filter { it.signature == "POST https://example.supabase.co/rpc/report_android_naver_task" }
        assertEquals(1, reportCalls.size)
    }

    @Test
    fun failedMidReportRecordsKeywordFailureCandidate() = runBlocking {
        val transport = RecordingTransport(
            "POST https://example.supabase.co/rpc/claim_android_naver_task" to """
                {
                  "task_id": "traffic_10",
                  "lease_id": "lease_10",
                  "traffic_id": 10,
                  "slot_id": 20,
                  "keyword": "first keyword",
                  "keyword_name": "second keyword",
                  "link_url": "https://smartstore.naver.com/store/products/123",
                  "mid": "123"
                }
            """.trimIndent(),
            "POST https://example.supabase.co/rpc/report_android_naver_task" to "{}",
            "POST https://example.supabase.co/rpc/record_android_keyword_failure" to "{}",
        )
        val client = SupabaseApiClient(
            baseUrl = "https://example.supabase.co",
            anonKey = "anon",
            allowRawRestFallback = false,
            transport = transport,
        )
        val lease = client.leaseTask("z1-1", DeviceIdentity.Role.SOLDIER, "G", "0.1.20")

        client.reportTask(
            StrategyTaskReport(
                taskLeaseId = lease?.taskLeaseId,
                deviceName = "z1-1",
                result = StrategyTaskResult.FAILED,
                message = "MID product not found after exploration: 123",
                strategyGroup = "G",
                failureReason = "mid_product_not_found_after_exploration",
                queryPhrase = "second keyword",
                finalUrl = "https://m.search.naver.com/search.naver",
                midFound = false,
            ),
        )

        val signatures = transport.calls.map { it.signature }
        assertEquals(
            listOf(
                "POST https://example.supabase.co/rpc/claim_android_naver_task",
                "POST https://example.supabase.co/rpc/report_android_naver_task",
                "POST https://example.supabase.co/rpc/record_android_keyword_failure",
                "POST https://example.supabase.co/rpc/record_android_keyword_failure",
            ),
            signatures,
        )
        val keywordBodies = transport.calls.takeLast(2).map { it.body.orEmpty() }
        val keywordBody = keywordBodies.first()
        assertTrue(keywordBody.contains(""""p_slot_id":20"""))
        assertTrue(keywordBody.contains(""""p_failure_reason":"mid_product_not_found_after_exploration""""))
        assertTrue(keywordBody.contains(""""p_query_phrase":"second keyword""""))
        assertTrue(keywordBody.contains(""""p_mid_found":false"""))
        assertTrue(keywordBodies.any { it.contains(""""p_strategy":"G"""") })
        assertTrue(keywordBodies.any { it.contains(""""p_strategy":"A"""") })
    }

    private class RecordingTransport(
        vararg responses: Pair<String, String>,
    ) : HttpJsonTransport {
        private val responsesBySignature = responses.toMap()
        val calls = mutableListOf<Call>()

        override suspend fun request(
            method: String,
            url: String,
            body: String?,
            headers: Map<String, String>,
        ): String {
            val signature = "$method $url"
            calls.add(Call(signature, body))
            return responsesBySignature[signature] ?: error("Unexpected request: $signature")
        }
    }

    private data class Call(
        val signature: String,
        val body: String?,
    )
}
