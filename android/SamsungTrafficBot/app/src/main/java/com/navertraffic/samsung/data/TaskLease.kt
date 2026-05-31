package com.navertraffic.samsung.data

import com.navertraffic.samsung.strategy.StrategyATask
import com.navertraffic.samsung.strategy.StrategyGTask
import com.navertraffic.samsung.strategy.StrategyVariant

data class StrategyTaskLease(
    val taskLeaseId: String,
    val task: StrategyATask? = null,
    val taskG: StrategyGTask? = null,
    val strategyGroup: String? = null,
    val strategyVersion: String? = null,
    val strategyConfig: NaverStrategyConfig? = null,
) {
    val strategyName: String get() = if (taskG != null) "G" else "A"
    val assignedStrategyGroup: String?
        get() = strategyGroup?.trim()?.uppercase()
            ?.takeIf { it.isNotBlank() && it != "UNASSIGNED" && it != "LEGACY" }

    fun assignedVariant(): StrategyVariant? {
        return when (assignedStrategyGroup) {
            "A" -> StrategyVariant.A
            "B" -> StrategyVariant.B
            "C" -> StrategyVariant.C
            else -> null
        }
    }
}

data class NaverStrategyConfig(
    val entryFlow: String? = null,
    val uaProfile: String? = null,
    val keywordMode: String? = null,
    val searchExecution: String? = null,
    val midMatchMode: String? = null,
    val rawJson: String? = null,
)

class StrategyTaskLeaseBlockedException(
    val reason: String,
    val groupState: String,
    message: String,
) : RuntimeException(message)

enum class StrategyTaskResult {
    SUCCESS,
    FAILED,
    SKIPPED,
}

data class StrategyTaskReport(
    val taskLeaseId: String?,
    val deviceName: String,
    val result: StrategyTaskResult,
    val message: String? = null,
    val strategyGroup: String? = null,
    val strategyVersion: String? = null,
    val entryFlow: String? = null,
    val uaProfile: String? = null,
    val keywordMode: String? = null,
    val searchExecution: String? = null,
    val midMatchMode: String? = null,
    val browserLayer: String? = null,
    val failureReason: String? = null,
    val queryPhrase: String? = null,
    val finalUrl: String? = null,
    val midFound: Boolean? = null,
    val detailStatus: String? = null,
)

interface StrategyTaskLeaseClient {
    suspend fun leaseTask(
        deviceName: String,
        role: DeviceIdentity.Role,
        strategy: String,
        appVersion: String,
    ): StrategyTaskLease?

    suspend fun reportTask(report: StrategyTaskReport)
}

class NoopStrategyTaskLeaseClient : StrategyTaskLeaseClient {
    override suspend fun leaseTask(
        deviceName: String,
        role: DeviceIdentity.Role,
        strategy: String,
        appVersion: String,
    ): StrategyTaskLease? = null

    override suspend fun reportTask(report: StrategyTaskReport) = Unit
}
