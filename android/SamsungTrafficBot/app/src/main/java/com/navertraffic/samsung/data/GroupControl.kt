package com.navertraffic.samsung.data

enum class DeviceRuntimeState {
    IDLE,
    RUNNING_TASK,
    ROTATING,
    PAUSED,
    ERROR,
}

enum class GroupState {
    READY,
    DRAINING,
    ROTATING,
    ROTATION_FAILED,
    PAUSED,
    STOPPED,
}

enum class GroupCommand {
    NONE,
    ROTATE_GROUP_IP,
    PAUSE_FOR_ROTATION,
    RESUME,
    STOP,
}

data class DeviceHeartbeat(
    val deviceName: String,
    val groupId: String,
    val role: DeviceIdentity.Role,
    val state: DeviceRuntimeState,
    val taskCount: Int,
    val appVersion: String,
    val currentIp: String? = null,
    val lastError: String? = null,
)

data class GroupPolicy(
    val rotateOwner: String,
    val rotateEveryGroupTasks: Int,
    val drainTimeoutSec: Int = 120,
    val pauseSoldiersDuringRotation: Boolean = true,
    val pauseOnRotationFail: Boolean = true,
)

data class GroupControlResponse(
    val groupState: GroupState,
    val command: GroupCommand = GroupCommand.NONE,
    val commandId: String? = null,
    val policy: GroupPolicy,
)

data class RotationReport(
    val commandId: String?,
    val deviceName: String,
    val groupId: String,
    val beforeIp: String?,
    val afterIp: String?,
    val success: Boolean,
    val message: String? = null,
)

data class GroupRotationDecision(
    val nextGroupState: GroupState,
    val bossCommand: GroupCommand,
    val soldierCommand: GroupCommand,
    val allowNewTaskLeases: Boolean,
)

object GroupRotationCoordinator {
    fun decide(
        groupState: GroupState,
        groupCompletedTasksSinceRotation: Int,
        devices: List<DeviceHeartbeat>,
        policy: GroupPolicy,
        drainingElapsedSec: Int,
    ): GroupRotationDecision {
        return when (groupState) {
            GroupState.READY -> {
                if (groupCompletedTasksSinceRotation >= policy.rotateEveryGroupTasks.coerceAtLeast(1)) {
                    draining()
                } else {
                    ready()
                }
            }
            GroupState.DRAINING -> {
                when {
                    drainingElapsedSec > policy.drainTimeoutSec -> rotationFailed()
                    devices.all { it.state != DeviceRuntimeState.RUNNING_TASK } -> rotating()
                    else -> draining()
                }
            }
            GroupState.ROTATING -> GroupRotationDecision(
                nextGroupState = GroupState.ROTATING,
                bossCommand = GroupCommand.NONE,
                soldierCommand = GroupCommand.PAUSE_FOR_ROTATION,
                allowNewTaskLeases = false,
            )
            GroupState.ROTATION_FAILED -> GroupRotationDecision(
                nextGroupState = GroupState.ROTATION_FAILED,
                bossCommand = GroupCommand.NONE,
                soldierCommand = GroupCommand.PAUSE_FOR_ROTATION,
                allowNewTaskLeases = false,
            )
            GroupState.PAUSED,
            GroupState.STOPPED -> GroupRotationDecision(
                nextGroupState = groupState,
                bossCommand = GroupCommand.NONE,
                soldierCommand = GroupCommand.STOP,
                allowNewTaskLeases = false,
            )
        }
    }

    private fun ready() = GroupRotationDecision(
        nextGroupState = GroupState.READY,
        bossCommand = GroupCommand.NONE,
        soldierCommand = GroupCommand.NONE,
        allowNewTaskLeases = true,
    )

    private fun draining() = GroupRotationDecision(
        nextGroupState = GroupState.DRAINING,
        bossCommand = GroupCommand.NONE,
        soldierCommand = GroupCommand.PAUSE_FOR_ROTATION,
        allowNewTaskLeases = false,
    )

    private fun rotating() = GroupRotationDecision(
        nextGroupState = GroupState.ROTATING,
        bossCommand = GroupCommand.ROTATE_GROUP_IP,
        soldierCommand = GroupCommand.PAUSE_FOR_ROTATION,
        allowNewTaskLeases = false,
    )

    private fun rotationFailed() = GroupRotationDecision(
        nextGroupState = GroupState.ROTATION_FAILED,
        bossCommand = GroupCommand.NONE,
        soldierCommand = GroupCommand.PAUSE_FOR_ROTATION,
        allowNewTaskLeases = false,
    )
}
