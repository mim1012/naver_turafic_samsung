package com.navertraffic.samsung.data

interface AccountLeaseClient {
    suspend fun leaseAccount(
        deviceName: String,
        role: DeviceIdentity.Role,
        strategy: String,
        appVersion: String,
    ): AccountLease?

    suspend fun report(report: AccountLeaseReport)

    suspend fun release(
        leaseId: String,
        deviceName: String,
        reason: String,
    )
}

class NoopAccountLeaseClient : AccountLeaseClient {
    override suspend fun leaseAccount(
        deviceName: String,
        role: DeviceIdentity.Role,
        strategy: String,
        appVersion: String,
    ): AccountLease? = null

    override suspend fun report(report: AccountLeaseReport) = Unit

    override suspend fun release(
        leaseId: String,
        deviceName: String,
        reason: String,
    ) = Unit
}
