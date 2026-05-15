package com.navertraffic.samsung.data

interface GroupControlClient {
    suspend fun heartbeat(heartbeat: DeviceHeartbeat): GroupControlResponse?

    suspend fun reportRotation(report: RotationReport)

    suspend fun startRotation(groupId: String) {}
}

class NoopGroupControlClient : GroupControlClient {
    override suspend fun heartbeat(heartbeat: DeviceHeartbeat): GroupControlResponse? = null

    override suspend fun reportRotation(report: RotationReport) = Unit
}
