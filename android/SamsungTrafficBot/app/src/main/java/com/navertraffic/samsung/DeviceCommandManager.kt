package com.navertraffic.samsung

import android.content.Context
import android.content.Intent
import com.navertraffic.samsung.data.DeviceCommand
import com.navertraffic.samsung.data.DeviceCommandHandler
import com.navertraffic.samsung.data.DeviceCommandReport
import com.navertraffic.samsung.data.DeviceCommandType
import com.navertraffic.samsung.data.DeviceIdentity
import com.navertraffic.samsung.data.DeviceRuntimeState
import com.navertraffic.samsung.data.GroupControlClient
import com.navertraffic.samsung.data.GroupControlResponse
import com.navertraffic.samsung.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeviceCommandManager(
    context: Context,
    private val identity: DeviceIdentity,
    private val groupControlClient: GroupControlClient,
    private val serverUrl: String,
    private val apiKey: String?,
    private val log: (String) -> Unit,
) : DeviceCommandHandler {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("device_commands", Context.MODE_PRIVATE)

    @Volatile
    private var isUpdating = false

    override suspend fun handleDeviceCommand(
        response: GroupControlResponse?,
        state: DeviceRuntimeState,
    ): Boolean {
        response?.deviceCommand?.let { rememberCommand(it) }
        val pending = pendingCommand() ?: return false
        if (isCompleted(pending.localId)) return pending.type == DeviceCommandType.STOP || pending.type == DeviceCommandType.PAUSE

        return when (pending.type) {
            DeviceCommandType.START_BOT -> {
                completeAndReport(command = pending, success = true, message = "start command accepted")
                launchAutoRun()
                false
            }
            DeviceCommandType.UPDATE_APP -> {
                if (!state.canRunDeferredCommand()) {
                    log("업데이트 명령 pending: 현재 상태 $state")
                    false
                } else {
                    runUpdate(pending)
                    false
                }
            }
            DeviceCommandType.RESTART_APP -> {
                if (!state.canRunDeferredCommand()) {
                    log("재시작 명령 pending: 현재 상태 $state")
                    false
                } else {
                    runRestart(pending)
                    false
                }
            }
            DeviceCommandType.REBOOT_DEVICE -> {
                if (!state.canRunDeferredCommand()) {
                    log("휴대폰 재부팅 명령 pending: 현재 상태 $state")
                    false
                } else {
                    runDeviceReboot(pending)
                    false
                }
            }
            DeviceCommandType.STOP,
            DeviceCommandType.PAUSE -> {
                completeAndReport(
                    command = pending,
                    success = true,
                    message = "${pending.type.name} accepted",
                    clearPending = false,
                )
                log("기기 명령 ${pending.type.name}: 작업 대기/중지")
                true
            }
        }
    }

    private fun rememberCommand(command: DeviceCommand) {
        val localId = command.localId()
        if (isCompleted(localId)) return
        if (prefs.getString(KEY_PROCESSING_ID, null) == localId) return
        prefs.edit()
            .putString(KEY_PROCESSING_ID, localId)
            .putString(KEY_PENDING_ID, command.commandId)
            .putString(KEY_PENDING_TYPE, command.type.name)
            .putString(KEY_PENDING_PAYLOAD, command.payload)
            .apply()
        log("기기 명령 수신: ${command.type.name} (${command.commandId ?: localId})")
    }

    private suspend fun runUpdate(command: PendingCommand) {
        if (isUpdating) {
            log("업데이트 이미 실행 중: ${command.commandId ?: command.localId}")
            return
        }
        isUpdating = true
        try {
            val result = AppUpdateManager(appContext, log).checkAndUpdateFromServerDetailed(
                serverUrl = serverUrl,
                apiKey = apiKey,
                restartAfterInstall = false,
            )
            val success = result.success
            completeAndReport(command, success, result.message)
            if (success && result.installed) {
                val (_, restartMessage) = restartApp()
                log("업데이트 보고 완료 후 재시작: $restartMessage")
            }
        } catch (error: Throwable) {
            completeAndReport(command, success = false, message = error.message ?: error::class.java.simpleName)
        } finally {
            isUpdating = false
        }
    }

    private suspend fun runRestart(command: PendingCommand) {
        val (success, message) = restartApp()
        completeAndReport(command, success = success, message = message)
    }

    private suspend fun runDeviceReboot(command: PendingCommand) {
        val (success, message) = scheduleDeviceReboot()
        completeAndReport(command, success = success, message = message)
    }

    private fun launchAutoRun() {
        runCatching {
            appContext.startActivity(
                Intent(appContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("autoRun", true)
                },
            )
        }.onSuccess {
            log("작업 시작/재개 명령: autoRun 시작 요청")
        }.onFailure {
            log("작업 시작/재개 명령 실패: ${it.message ?: it::class.java.simpleName}")
        }
    }

    private suspend fun restartApp(): Pair<Boolean, String> {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val proc = Runtime.getRuntime().exec(
                    arrayOf(
                        "su",
                        "-c",
                        "sh -c 'sleep 1; am force-stop com.navertraffic.samsung; sleep 1; am start -n com.navertraffic.samsung/.ui.MainActivity' >/dev/null 2>&1 &",
                    ),
                )
                val stdout = proc.inputStream.bufferedReader().readText().trim()
                val stderr = proc.errorStream.bufferedReader().readText().trim()
                val exit = proc.waitFor()
                Triple(exit, stdout, stderr)
            }
        }
        return result.fold(
            onSuccess = { (exit, stdout, stderr) ->
                val success = exit == 0
                val message = if (success) {
                    "restart command executed"
                } else {
                    "root restart failed exit=$exit stdout=$stdout stderr=$stderr"
                }
                success to message
            },
            onFailure = { error ->
                false to "root restart failed: ${error.message ?: error::class.java.simpleName}"
            },
        )
    }

    private suspend fun scheduleDeviceReboot(): Pair<Boolean, String> {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val proc = Runtime.getRuntime().exec(
                    arrayOf(
                        "su",
                        "-c",
                        "sh -c 'sleep 8; reboot || svc power reboot || setprop sys.powerctl reboot' >/dev/null 2>&1 &",
                    ),
                )
                val stdout = proc.inputStream.bufferedReader().readText().trim()
                val stderr = proc.errorStream.bufferedReader().readText().trim()
                val exit = proc.waitFor()
                Triple(exit, stdout, stderr)
            }
        }
        return result.fold(
            onSuccess = { (exit, stdout, stderr) ->
                val success = exit == 0
                val message = if (success) {
                    "device reboot scheduled"
                } else {
                    "root reboot failed exit=$exit stdout=$stdout stderr=$stderr"
                }
                success to message
            },
            onFailure = { error ->
                false to "root reboot failed: ${error.message ?: error::class.java.simpleName}"
            },
        )
    }

    private suspend fun completeAndReport(
        command: PendingCommand,
        success: Boolean,
        message: String,
        clearPending: Boolean = true,
    ) {
        val editor = prefs.edit()
            .putString(completedKey(command.localId), message)
        if (clearPending) {
            editor
                .remove(KEY_PROCESSING_ID)
                .remove(KEY_PENDING_ID)
                .remove(KEY_PENDING_TYPE)
                .remove(KEY_PENDING_PAYLOAD)
        }
        editor.apply()

        runCatching {
            groupControlClient.reportDeviceCommand(
                DeviceCommandReport(
                    commandId = command.commandId,
                    deviceName = identity.rawName,
                    groupId = identity.groupId,
                    command = command.type,
                    success = success,
                    message = message,
                ),
            )
        }.onFailure { log("기기 명령 report 실패: ${it.message}") }
    }

    private fun pendingCommand(): PendingCommand? {
        val type = prefs.getString(KEY_PENDING_TYPE, null)
            ?.let { name -> DeviceCommandType.entries.firstOrNull { it.name == name } }
            ?: return null
        val localId = prefs.getString(KEY_PROCESSING_ID, null) ?: return null
        return PendingCommand(
            localId = localId,
            commandId = prefs.getString(KEY_PENDING_ID, null),
            type = type,
        )
    }

    private fun isCompleted(localId: String): Boolean = prefs.contains(completedKey(localId))

    private fun completedKey(localId: String): String = "completed_$localId"

    private fun DeviceCommand.localId(): String {
        return commandId?.takeIf { it.isNotBlank() }
            ?: "${type.name}:${payload.orEmpty().hashCode()}"
    }

    private fun DeviceRuntimeState.canRunDeferredCommand(): Boolean {
        return this == DeviceRuntimeState.IDLE ||
            this == DeviceRuntimeState.PAUSED ||
            this == DeviceRuntimeState.ERROR
    }

    private data class PendingCommand(
        val localId: String,
        val commandId: String?,
        val type: DeviceCommandType,
    )

    private companion object {
        private const val KEY_PROCESSING_ID = "processing_command_id"
        private const val KEY_PENDING_ID = "pending_command_id"
        private const val KEY_PENDING_TYPE = "pending_command_type"
        private const val KEY_PENDING_PAYLOAD = "pending_command_payload"
    }
}
