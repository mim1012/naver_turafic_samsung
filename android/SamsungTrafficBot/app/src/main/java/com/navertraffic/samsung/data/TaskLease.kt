package com.navertraffic.samsung.data

import com.navertraffic.samsung.strategy.StrategyATask

data class StrategyTaskLease(
    val taskLeaseId: String,
    val task: StrategyATask,
)

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
