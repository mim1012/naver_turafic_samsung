package com.navertraffic.samsung.soldier

import com.navertraffic.samsung.BuildConfig
import com.navertraffic.samsung.data.AccountLeaseClient
import com.navertraffic.samsung.data.AccountLeaseReport
import com.navertraffic.samsung.data.AccountLeaseResult
import com.navertraffic.samsung.data.DeviceHeartbeat
import com.navertraffic.samsung.data.DeviceCommandHandler
import com.navertraffic.samsung.data.DeviceIdentity
import com.navertraffic.samsung.data.DeviceRuntimeState
import com.navertraffic.samsung.data.GroupCommand
import com.navertraffic.samsung.data.GroupControlClient
import com.navertraffic.samsung.data.GroupControlResponse
import com.navertraffic.samsung.data.GroupState
import com.navertraffic.samsung.data.NoopDeviceCommandHandler
import com.navertraffic.samsung.data.NoopAccountLeaseClient
import com.navertraffic.samsung.data.NoopGroupControlClient
import com.navertraffic.samsung.strategy.BotStrategy
import com.navertraffic.samsung.strategy.StrategyAResult
import com.navertraffic.samsung.strategy.StrategyATask
import com.navertraffic.samsung.strategy.StrategyGTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SoldierController(
    private val identity: DeviceIdentity,
    private val botStrategy: BotStrategy,
    private val accountLeaseClient: AccountLeaseClient = NoopAccountLeaseClient(),
    private val groupControlClient: GroupControlClient = NoopGroupControlClient(),
    private val deviceCommandHandler: DeviceCommandHandler = NoopDeviceCommandHandler,
    private val log: (String) -> Unit,
) {
    private var taskCount = 0
    @Volatile
    private var runtimeState = DeviceRuntimeState.IDLE
    @Volatile
    private var lastError: String? = null
    @Volatile
    private var lastDeviceCommandBlocked = false

    fun startHeartbeatMonitor(
        scope: CoroutineScope,
        intervalMs: Long = HEARTBEAT_INTERVAL_MS,
    ): Job = scope.launch {
        log("쫄병봇 heartbeat 모니터 시작: ${intervalMs / 1000}초 주기")
        while (isActive) {
            heartbeat(runtimeState)
            delay(intervalMs)
        }
    }

    suspend fun runOnce(task: StrategyATask): StrategyAResult {
        val strategy = (botStrategy as? BotStrategy.A)?.strategy
            ?: return StrategyAResult(success = false, message = "wrong_strategy_for_A")
        return executeWithLifecycle("A") { strategy.runDetailed(task, log) }
    }

    suspend fun runOnce(task: StrategyGTask): StrategyAResult {
        val strategy = (botStrategy as? BotStrategy.G)?.strategy
            ?: return StrategyAResult(success = false, message = "wrong_strategy_for_G")
        return executeWithLifecycle("G") { strategy.runDetailed(task, log) }
    }

    private suspend fun executeWithLifecycle(
        strategyName: String,
        run: suspend () -> StrategyAResult,
    ): StrategyAResult {
        log("쫄병봇 실행: IP 로테이션은 대장봇 그룹 owner가 수행")
        runtimeState = DeviceRuntimeState.IDLE
        val control = heartbeat(DeviceRuntimeState.IDLE)
        if (handleControl(control)) return StrategyAResult(success = false, message = "group_paused")

        val lease = runCatching {
            accountLeaseClient.leaseAccount(identity.rawName, identity.role, strategyName, APP_VERSION)
        }.onFailure { log("계정 lease 실패: ${it.message}") }.getOrNull()
        lease?.let { log("계정 lease 획득: ${it.accountAlias}") }

        runtimeState = DeviceRuntimeState.RUNNING_TASK
        heartbeat(DeviceRuntimeState.RUNNING_TASK)
        val result = try {
            run()
        } catch (error: Throwable) {
            lastError = error.message ?: error::class.java.simpleName
            runtimeState = DeviceRuntimeState.ERROR
            heartbeat(DeviceRuntimeState.ERROR)
            throw error
        }
        lastError = if (result.success) null else result.message

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
            log("쫄병봇 완료: ${taskCount}건")
        }
        runtimeState = if (result.success) DeviceRuntimeState.IDLE else DeviceRuntimeState.ERROR
        heartbeat(runtimeState)
        return result
    }

    private suspend fun heartbeat(state: DeviceRuntimeState): GroupControlResponse? {
        return try {
            val response = groupControlClient.heartbeat(
                DeviceHeartbeat(
                    deviceName = identity.rawName,
                    groupId = identity.groupId,
                    role = identity.role,
                    state = state,
                    taskCount = taskCount,
                    appVersion = BuildConfig.VERSION_NAME,
                    appVersionCode = BuildConfig.VERSION_CODE,
                    lastError = lastError,
                ),
            )
            lastDeviceCommandBlocked = deviceCommandHandler.handleDeviceCommand(response, state)
            response
        } catch (error: Throwable) {
            log("heartbeat 실패: ${error.message}")
            null
        }
    }

    private suspend fun handleControl(response: GroupControlResponse?): Boolean {
        if (lastDeviceCommandBlocked) return true
        if (response == null) return false
        return when {
            response.command == GroupCommand.PAUSE_FOR_ROTATION ||
                response.groupState == GroupState.DRAINING ||
                response.groupState == GroupState.ROTATING -> {
                runtimeState = DeviceRuntimeState.PAUSED
                heartbeat(DeviceRuntimeState.PAUSED)
                log("그룹 상태 ${response.groupState}: 새 작업 대기")
                true
            }
            response.command == GroupCommand.STOP ||
                response.groupState == GroupState.STOPPED ||
                response.groupState == GroupState.PAUSED ||
                response.groupState == GroupState.ROTATION_FAILED -> {
                runtimeState = DeviceRuntimeState.PAUSED
                heartbeat(DeviceRuntimeState.PAUSED)
                log("그룹 상태 ${response.groupState}: 작업 중지")
                true
            }
            else -> false
        }
    }

    companion object {
        private const val APP_VERSION = "0.1.0"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}
