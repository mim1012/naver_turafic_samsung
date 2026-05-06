package com.navertraffic.samsung.data

interface GroupControlClient {
    suspend fun heartbeat(heartbeat: DeviceHeartbeat): GroupControlResponse?

    suspend fun reportRotation(report: RotationReport)
}

class NoopGroupControlClient : GroupControlClient {
    override suspend fun heartbeat(heartbeat: DeviceHeartbeat): GroupControlResponse? = null

    override suspend fun reportRotation(report: RotationReport) = Unit
}
