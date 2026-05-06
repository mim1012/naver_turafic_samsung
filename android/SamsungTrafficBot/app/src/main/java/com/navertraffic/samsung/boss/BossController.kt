package com.navertraffic.samsung.boss

import com.navertraffic.samsung.data.AccountLeaseClient
import com.navertraffic.samsung.data.AccountLeaseReport
import com.navertraffic.samsung.data.AccountLeaseResult
import com.navertraffic.samsung.data.DeviceHeartbeat
import com.navertraffic.samsung.data.DeviceIdentity
import com.navertraffic.samsung.data.DeviceRuntimeState
import com.navertraffic.samsung.data.GroupCommand
import com.navertraffic.samsung.data.GroupControlClient
import com.navertraffic.samsung.data.GroupControlResponse
import com.navertraffic.samsung.data.GroupState
import com.navertraffic.samsung.data.NoopAccountLeaseClient
import com.navertraffic.samsung.data.NoopGroupControlClient
import com.navertraffic.samsung.data.RotationReport
import com.navertraffic.samsung.soldier.IpRotationManager
import com.navertraffic.samsung.strategy.SamsungBrowserStrategyA
import com.navertraffic.samsung.strategy.StrategyATask

class BossController(
    private val identity: DeviceIdentity,
    private val strategyA: SamsungBrowserStrategyA,
    private val ipRotationManager: IpRotationManager,
    private val accountLeaseClient: AccountLeaseClient = NoopAccountLeaseClient(),
    private val groupControlClient: GroupControlClient = NoopGroupControlClient(),
    private val rotateEveryGroupTasks: Int,
    private val log: (String) -> Unit,
) {
    private var taskCount = 0

    suspend fun runOnce(task: StrategyATask): Boolean {
        log("대장봇 실행: 그룹 IP owner (${rotateEveryGroupTasks}건마다 회전)")
        val control = heartbeat(DeviceRuntimeState.IDLE)
        if (handleControl(control)) return false

        val lease = runCatching {
            accountLeaseClient.leaseAccount(identity.rawName, identity.role, "A", APP_VERSION)
        }.onFailure {
            log("계정 lease 실패: ${it.message}")
        }.getOrNull()
        lease?.let { log("계정 lease 획득: ${it.accountAlias}") }

        heartbeat(DeviceRuntimeState.RUNNING_TASK)
        val result = strategyA.runDetailed(task, log)
        val reportResult = when {
            result.success -> AccountLeaseResult.SUCCESS
            result.signals.isNotEmpty() -> AccountLeaseResult.PROTECTED
            else -> AccountLeaseResult.FAILED
        }
        runCatching {
            accountLeaseClient.report(
                AccountLeaseReport(
                    leaseId = lease?.leaseId,
                    deviceName = identity.rawName,
                    result = reportResult,
                    signals = result.signals,
                    lastUrl = result.lastUrl,
                    message = result.message,
                ),
            )
        }.onFailure { log("계정 report 실패: ${it.message}") }

        lease?.let {
            runCatching {
                accountLeaseClient.release(
                    leaseId = it.leaseId,
                    deviceName = identity.rawName,
                    reason = if (result.success) "task_complete" else "task_failed",
                )
            }.onFailure { error -> log("계정 release 실패: ${error.message}") }
        }

        if (result.success) {
            taskCount += 1
            log("대장봇 완료: ${taskCount}건")
            if (taskCount % rotateEveryGroupTasks.coerceAtLeast(1) == 0) {
                rotateGroupIp()
            }
        }
        heartbeat(DeviceRuntimeState.IDLE)
        return result.success
    }

    suspend fun rotateGroupIp(commandId: String? = null) {
        log("대장봇 그룹 IP 로테이션 시작 - 쫄병 작업은 서버에서 일시정지되어야 함")
        val success = runCatching { ipRotationManager.rotate(log) }
            .onSuccess { log("대장봇 그룹 IP 로테이션 완료") }
            .onFailure { log("대장봇 그룹 IP 로테이션 실패: ${it.message}") }
            .isSuccess

        runCatching {
            groupControlClient.reportRotation(
                RotationReport(
                    commandId = commandId,
                    deviceName = identity.rawName,
                    groupId = identity.groupId,
                    beforeIp = null,
                    afterIp = null,
                    success = success,
                    message = if (success) null else "rotation command failed",
                ),
            )
        }.onFailure { log("로테이션 report 실패: ${it.message}") }
    }

    private suspend fun heartbeat(state: DeviceRuntimeState): GroupControlResponse? {
        return runCatching {
            groupControlClient.heartbeat(
                DeviceHeartbeat(
                    deviceName = identity.rawName,
                    groupId = identity.groupId,
                    role = identity.role,
                    state = state,
                    taskCount = taskCount,
                ),
            )
        }.onFailure { log("heartbeat 실패: ${it.message}") }.getOrNull()
    }

    private suspend fun handleControl(response: GroupControlResponse?): Boolean {
        if (response == null) return false
        return when {
            response.command == GroupCommand.ROTATE_GROUP_IP -> {
                rotateGroupIp(response.commandId)
                true
            }
            response.command == GroupCommand.PAUSE_FOR_ROTATION ||
                response.groupState == GroupState.DRAINING ||
                response.groupState == GroupState.ROTATING -> {
                log("그룹 상태 ${response.groupState}: 새 작업 대기")
                true
            }
            response.command == GroupCommand.STOP ||
                response.groupState == GroupState.STOPPED ||
                response.groupState == GroupState.PAUSED ||
                response.groupState == GroupState.ROTATION_FAILED -> {
                log("그룹 상태 ${response.groupState}: 작업 중지")
                true
            }
            else -> false
        }
    }

    companion object {
        private const val APP_VERSION = "0.1.0"
    }
}
