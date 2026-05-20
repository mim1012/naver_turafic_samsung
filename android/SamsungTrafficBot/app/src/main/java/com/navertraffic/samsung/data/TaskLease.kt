package com.navertraffic.samsung.data

import com.navertraffic.samsung.strategy.StrategyATask
import com.navertraffic.samsung.strategy.StrategyGTask

data class StrategyTaskLease(
    val taskLeaseId: String,
    val task: StrategyATask? = null,
    val taskG: StrategyGTask? = null,
) {
    val strategyName: String get() = if (taskG != null) "G" else "A"
}

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
