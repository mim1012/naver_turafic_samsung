package com.navertraffic.samsung.data

interface GroupControlClient {
    suspend fun heartbeat(heartbeat: DeviceHeartbeat): GroupControlResponse?

    suspend fun reportRotation(report: RotationReport)

    suspend fun reportDeviceCommand(report: DeviceCommandReport) {}

    suspend fun startRotation(groupId: String) {}
}

interface DeviceCommandHandler {
    suspend fun handleDeviceCommand(
        response: GroupControlResponse?,
        state: DeviceRuntimeState,
    ): Boolean
}

object NoopDeviceCommandHandler : DeviceCommandHandler {
    override suspend fun handleDeviceCommand(
        response: GroupControlResponse?,
        state: DeviceRuntimeState,
    ): Boolean = false
}

class NoopGroupControlClient : GroupControlClient {
    override suspend fun heartbeat(heartbeat: DeviceHeartbeat): GroupControlResponse? = null

    override suspend fun reportRotation(report: RotationReport) = Unit

    override suspend fun reportDeviceCommand(report: DeviceCommandReport) = Unit
}
