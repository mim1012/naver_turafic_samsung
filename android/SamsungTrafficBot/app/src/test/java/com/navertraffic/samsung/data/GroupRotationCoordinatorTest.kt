package com.navertraffic.samsung.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRotationCoordinatorTest {
    private val policy = GroupPolicy(
        rotateOwner = "z1",
        rotateEveryGroupTasks = 10,
        drainTimeoutSec = 120,
    )

    @Test
    fun startsDrainingWhenGroupTaskThresholdIsReached() {
        val decision = GroupRotationCoordinator.decide(
            groupState = GroupState.READY,
            groupCompletedTasksSinceRotation = 10,
            devices = listOf(
                heartbeat("z1", DeviceIdentity.Role.BOSS, DeviceRuntimeState.IDLE),
                heartbeat("z1-1", DeviceIdentity.Role.SOLDIER, DeviceRuntimeState.IDLE),
            ),
            policy = policy,
            drainingElapsedSec = 0,
        )

        assertEquals(GroupState.DRAINING, decision.nextGroupState)
        assertEquals(GroupCommand.PAUSE_FOR_ROTATION, decision.soldierCommand)
        assertEquals(GroupCommand.NONE, decision.bossCommand)
        assertFalse(decision.allowNewTaskLeases)
    }

    @Test
    fun staysDrainingWhileAnySoldierIsRunning() {
        val decision = GroupRotationCoordinator.decide(
            groupState = GroupState.DRAINING,
            groupCompletedTasksSinceRotation = 10,
            devices = listOf(
                heartbeat("z1", DeviceIdentity.Role.BOSS, DeviceRuntimeState.IDLE),
                heartbeat("z1-1", DeviceIdentity.Role.SOLDIER, DeviceRuntimeState.RUNNING_TASK),
            ),
            policy = policy,
            drainingElapsedSec = 30,
        )

        assertEquals(GroupState.DRAINING, decision.nextGroupState)
        assertEquals(GroupCommand.PAUSE_FOR_ROTATION, decision.soldierCommand)
        assertEquals(GroupCommand.NONE, decision.bossCommand)
        assertFalse(decision.allowNewTaskLeases)
    }

    @Test
    fun commandsBossRotationWhenAllDevicesAreIdleAfterDrain() {
        val decision = GroupRotationCoordinator.decide(
            groupState = GroupState.DRAINING,
            groupCompletedTasksSinceRotation = 10,
            devices = listOf(
                heartbeat("z1", DeviceIdentity.Role.BOSS, DeviceRuntimeState.IDLE),
                heartbeat("z1-1", DeviceIdentity.Role.SOLDIER, DeviceRuntimeState.IDLE),
                heartbeat("z1-2", DeviceIdentity.Role.SOLDIER, DeviceRuntimeState.IDLE),
            ),
            policy = policy,
            drainingElapsedSec = 45,
        )

        assertEquals(GroupState.ROTATING, decision.nextGroupState)
        assertEquals(GroupCommand.ROTATE_GROUP_IP, decision.bossCommand)
        assertEquals(GroupCommand.PAUSE_FOR_ROTATION, decision.soldierCommand)
        assertFalse(decision.allowNewTaskLeases)
    }

    @Test
    fun waitingDevicesDoNotBlockRotationDrain() {
        val decision = GroupRotationCoordinator.decide(
            groupState = GroupState.DRAINING,
            groupCompletedTasksSinceRotation = 10,
            devices = listOf(
                heartbeat("z1", DeviceIdentity.Role.BOSS, DeviceRuntimeState.WAITING_TASK),
                heartbeat("z1-1", DeviceIdentity.Role.SOLDIER, DeviceRuntimeState.WAITING_LOGIN),
            ),
            policy = policy,
            drainingElapsedSec = 45,
        )

        assertEquals(GroupState.ROTATING, decision.nextGroupState)
        assertEquals(GroupCommand.ROTATE_GROUP_IP, decision.bossCommand)
        assertEquals(GroupCommand.PAUSE_FOR_ROTATION, decision.soldierCommand)
        assertFalse(decision.allowNewTaskLeases)
    }

    @Test
    fun failsRotationWhenDrainTimeoutExpires() {
        val decision = GroupRotationCoordinator.decide(
            groupState = GroupState.DRAINING,
            groupCompletedTasksSinceRotation = 10,
            devices = listOf(
                heartbeat("z1", DeviceIdentity.Role.BOSS, DeviceRuntimeState.IDLE),
                heartbeat("z1-1", DeviceIdentity.Role.SOLDIER, DeviceRuntimeState.RUNNING_TASK),
            ),
            policy = policy,
            drainingElapsedSec = 121,
        )

        assertEquals(GroupState.ROTATION_FAILED, decision.nextGroupState)
        assertEquals(GroupCommand.NONE, decision.bossCommand)
        assertEquals(GroupCommand.PAUSE_FOR_ROTATION, decision.soldierCommand)
        assertFalse(decision.allowNewTaskLeases)
    }

    @Test
    fun allowsLeasesWhenReadyAndBelowThreshold() {
        val decision = GroupRotationCoordinator.decide(
            groupState = GroupState.READY,
            groupCompletedTasksSinceRotation = 9,
            devices = listOf(
                heartbeat("z1", DeviceIdentity.Role.BOSS, DeviceRuntimeState.IDLE),
                heartbeat("z1-1", DeviceIdentity.Role.SOLDIER, DeviceRuntimeState.IDLE),
            ),
            policy = policy,
            drainingElapsedSec = 0,
        )

        assertEquals(GroupState.READY, decision.nextGroupState)
        assertEquals(GroupCommand.NONE, decision.bossCommand)
        assertEquals(GroupCommand.NONE, decision.soldierCommand)
        assertTrue(decision.allowNewTaskLeases)
    }

    private fun heartbeat(
        deviceName: String,
        role: DeviceIdentity.Role,
        state: DeviceRuntimeState,
    ): DeviceHeartbeat {
        return DeviceHeartbeat(
            deviceName = deviceName,
            groupId = "z1",
            role = role,
            state = state,
            taskCount = 0,
            appVersion = "test",
        )
    }
}
