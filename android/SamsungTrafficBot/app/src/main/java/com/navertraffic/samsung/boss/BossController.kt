package com.navertraffic.samsung.boss

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
import com.navertraffic.samsung.data.RotationReport
import com.navertraffic.samsung.soldier.IpRotationManager
import com.navertraffic.samsung.strategy.BotStrategy
import com.navertraffic.samsung.strategy.StrategyAResult
import com.navertraffic.samsung.strategy.StrategyATask
import com.navertraffic.samsung.strategy.StrategyGTask
import com.navertraffic.samsung.strategy.isRecoverableTaskFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BossController(
    private val identity: DeviceIdentity,
    private val botStrategy: BotStrategy,
    private val ipRotationManager: IpRotationManager,
    private val accountLeaseClient: AccountLeaseClient = NoopAccountLeaseClient(),
    private val groupControlClient: GroupControlClient = NoopGroupControlClient(),
    private val deviceCommandHandler: DeviceCommandHandler = NoopDeviceCommandHandler,
    private val rotateEveryGroupTasks: Int,
    private val rotationDrainWaitMs: Long = 90_000L,
    private val log: (String) -> Unit,
    private val beforeRotate: (suspend () -> Unit)? = null,
) {
    private var taskCount = 0
    @Volatile
    private var runtimeState = DeviceRuntimeState.IDLE
    @Volatile
    private var lastError: String? = null
    @Volatile
    private var lastDeviceCommandBlocked = false
    @Volatile
    private var rotationInProgress = false
    @Volatile
    private var lastHeartbeatSentAtMs = 0L
    @Volatile
    private var lastHeartbeatSentState: DeviceRuntimeState? = null
    @Volatile
    private var lastHeartbeatSentError: String? = null

    fun startHeartbeatMonitor(
        scope: CoroutineScope,
        intervalMs: Long = HEARTBEAT_INTERVAL_MS,
    ): Job = scope.launch {
        log("대장봇 heartbeat 모니터 시작: ${intervalMs / 1000}초 주기")
        while (isActive) {
            val response = heartbeat(runtimeState)
            handleControl(response)
            delay(intervalMs)
        }
    }

    suspend fun reportRuntimeState(
        state: DeviceRuntimeState,
        message: String? = null,
    ): GroupControlResponse? {
        runtimeState = state
        lastError = message
        if (shouldSuppressDuplicateHeartbeat(state, message)) return null
        val response = heartbeat(state)
        handleControl(response)
        return response
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
        log("대장봇 실행: 그룹 IP owner (${rotateEveryGroupTasks}건마다 회전)")
        runtimeState = DeviceRuntimeState.WAITING_TASK
        val control = heartbeat(DeviceRuntimeState.WAITING_TASK)
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
            log("대장봇 완료: ${taskCount}건")
            if (taskCount % rotateEveryGroupTasks.coerceAtLeast(1) == 0) {
                rotateGroupIp()
            }
        }
        runtimeState = when {
            result.success -> DeviceRuntimeState.IDLE
            result.isRecoverableTaskFailure() -> DeviceRuntimeState.WAITING_TASK
            else -> DeviceRuntimeState.ERROR
        }
        heartbeat(runtimeState)
        return result
    }

    suspend fun rotateGroupIp(commandId: String? = null) {
        if (rotationInProgress) {
            log("대장봇 IP 로테이션 이미 진행 중: 중복 명령 무시")
            return
        }
        rotationInProgress = true
        try {
            log("대장봇 IP 로테이션: DRAINING 신호 (쫄병 ${rotationDrainWaitMs / 1000}초 대기)")
            runtimeState = DeviceRuntimeState.ROTATING
            heartbeat(DeviceRuntimeState.ROTATING)
            runCatching { groupControlClient.startRotation(identity.groupId) }
                .onFailure { log("rotation 시작 신호 실패: ${it.message}") }
            beforeRotate?.invoke()
            val drainStepMs = 10_000L
            var remaining = rotationDrainWaitMs
            while (remaining > 0) {
                val step = minOf(drainStepMs, remaining)
                delay(step)
                remaining -= step
                if (remaining > 0) log("대장봇 DRAINING 대기 중: ${remaining / 1000}초 남음")
            }
            val (beforeIp, afterIp) = runCatching { ipRotationManager.rotate(log) }
                .onFailure { log("대장봇 그룹 IP 로테이션 실패: ${it.message}") }
                .getOrDefault(Pair(null, null))
            val success = beforeIp != null && afterIp != null && beforeIp != afterIp
            val report = RotationReport(
                commandId = commandId,
                deviceName = identity.rawName,
                groupId = identity.groupId,
                beforeIp = beforeIp,
                afterIp = afterIp,
                success = success,
                message = if (success) null else "ip unchanged or fetch failed",
            )
            var reported = false
            for (attempt in 1..3) {
                if (reported) break
                try {
                    groupControlClient.reportRotation(report)
                    reported = true
                } catch (error: Throwable) {
                    log("로테이션 report 실패($attempt/3): ${error.message}")
                    delay(3_000L * attempt)
                }
            }
            if (!reported) {
                lastError = "rotation_report_failed"
                runtimeState = DeviceRuntimeState.ROTATING
                heartbeat(DeviceRuntimeState.ROTATING)
                return
            }
            lastError = null
            runtimeState = DeviceRuntimeState.IDLE
            val response = heartbeat(DeviceRuntimeState.IDLE)
            handleControl(response)
        } finally {
            rotationInProgress = false
        }
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
            recordHeartbeatSent(state)
            lastDeviceCommandBlocked = deviceCommandHandler.handleDeviceCommand(response, state)
            response
        } catch (error: Throwable) {
            log("heartbeat 실패: ${error.message}")
            null
        }
    }

    private fun shouldSuppressDuplicateHeartbeat(
        state: DeviceRuntimeState,
        message: String?,
    ): Boolean {
        if (state !in DUPLICATE_SUPPRESSIBLE_STATES) return false
        val now = System.currentTimeMillis()
        return lastHeartbeatSentState == state &&
            lastHeartbeatSentError == message &&
            now - lastHeartbeatSentAtMs < DUPLICATE_STATE_HEARTBEAT_MIN_INTERVAL_MS
    }

    private fun recordHeartbeatSent(state: DeviceRuntimeState) {
        lastHeartbeatSentAtMs = System.currentTimeMillis()
        lastHeartbeatSentState = state
        lastHeartbeatSentError = lastError
    }

    private suspend fun handleControl(response: GroupControlResponse?): Boolean {
        if (lastDeviceCommandBlocked) return true
        if (response == null) return false
        return when {
            response.command == GroupCommand.ROTATE_GROUP_IP -> {
                rotateGroupIp(response.commandId)
                true
            }
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
        private val APP_VERSION: String
            get() = BuildConfig.VERSION_NAME
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val DUPLICATE_STATE_HEARTBEAT_MIN_INTERVAL_MS = 60_000L
        private val DUPLICATE_SUPPRESSIBLE_STATES = setOf(
            DeviceRuntimeState.IDLE,
            DeviceRuntimeState.WAITING_TASK,
            DeviceRuntimeState.PAUSED,
        )
    }
}
