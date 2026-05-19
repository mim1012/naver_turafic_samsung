package com.navertraffic.samsung.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidServerApiClientJsonTest {
    @Test
    fun parsesAccountLeaseResponse() {
        val json = """
            {
              "leaseId": "lease_123",
              "accountAlias": "naver_a",
              "loginId": "user",
              "password": "secret",
              "expiresAt": "2026-05-03T12:00:00Z"
            }
        """.trimIndent()

        val lease = AndroidServerApiJson.parseAccountLease(json)

        assertEquals("lease_123", lease?.leaseId)
        assertEquals("naver_a", lease?.accountAlias)
        assertEquals("user", lease?.loginId)
        assertEquals("secret", lease?.password)
        assertEquals("2026-05-03T12:00:00Z", lease?.expiresAt)
    }

    @Test
    fun parsesEmptyLeaseResponseAsNull() {
        assertNull(AndroidServerApiJson.parseAccountLease("{}"))
    }

    @Test
    fun parsesCurrentAccountWithoutLeaseId() {
        val json = """
            {
              "accountAlias": "z1-1-account",
              "loginId": "server-user",
              "password": "server-secret",
              "source": "assigned_device"
            }
        """.trimIndent()

        val account = AndroidServerApiJson.parseCurrentAccount(json)

        assertEquals("", account?.leaseId)
        assertEquals("z1-1-account", account?.accountAlias)
        assertEquals("server-user", account?.loginId)
        assertEquals("server-secret", account?.password)
    }

    @Test
    fun parsesGroupControlResponseWithPolicy() {
        val json = """
            {
              "groupState": "DRAINING",
              "command": "PAUSE_FOR_ROTATION",
              "commandId": "cmd_123",
              "policy": {
                "rotateOwner": "z1",
                "rotateEveryGroupTasks": 10,
                "drainTimeoutSec": 120,
                "pauseSoldiersDuringRotation": true,
                "pauseOnRotationFail": true
              }
            }
        """.trimIndent()

        val response = AndroidServerApiJson.parseGroupControlResponse(json)

        assertEquals(GroupState.DRAINING, response.groupState)
        assertEquals(GroupCommand.PAUSE_FOR_ROTATION, response.command)
        assertEquals("cmd_123", response.commandId)
        assertEquals("z1", response.policy.rotateOwner)
        assertEquals(10, response.policy.rotateEveryGroupTasks)
        assertEquals(120, response.policy.drainTimeoutSec)
    }

    @Test
    fun parsesGroupControlResponseWithDeviceCommandObject() {
        val json = """
            {
              "groupState": "READY",
              "command": "NONE",
              "deviceCommand": {
                "id": "cmd_update_1",
                "type": "UPDATE_APP"
              },
              "policy": {
                "rotateOwner": "z1",
                "rotateEveryGroupTasks": 10
              }
            }
        """.trimIndent()

        val response = AndroidServerApiJson.parseGroupControlResponse(json)

        assertEquals(DeviceCommandType.UPDATE_APP, response.deviceCommand?.type)
        assertEquals("cmd_update_1", response.deviceCommand?.commandId)
    }

    @Test
    fun parsesGroupControlResponseWithCommandPayloadObject() {
        val json = """
            {
              "groupState": "READY",
              "command": "NONE",
              "commandPayload": {
                "commandId": "cmd_restart_1",
                "command": "RESTART_APP"
              },
              "policy": {
                "rotateOwner": "z1",
                "rotateEveryGroupTasks": 10
              }
            }
        """.trimIndent()

        val response = AndroidServerApiJson.parseGroupControlResponse(json)

        assertEquals(DeviceCommandType.RESTART_APP, response.deviceCommand?.type)
        assertEquals("cmd_restart_1", response.deviceCommand?.commandId)
    }

    @Test
    fun parsesGroupControlResponseWithRebootDeviceCommand() {
        val json = """
            {
              "groupState": "READY",
              "command": "NONE",
              "deviceCommand": {
                "id": "cmd_reboot_1",
                "type": "REBOOT_DEVICE"
              },
              "policy": {
                "rotateOwner": "z1",
                "rotateEveryGroupTasks": 10
              }
            }
        """.trimIndent()

        val response = AndroidServerApiJson.parseGroupControlResponse(json)

        assertEquals(DeviceCommandType.REBOOT_DEVICE, response.deviceCommand?.type)
        assertEquals("cmd_reboot_1", response.deviceCommand?.commandId)
    }

    @Test
    fun serializesHeartbeatWithLowercaseRoleAndUppercaseState() {
        val body = AndroidServerApiJson.deviceHeartbeatBody(
            DeviceHeartbeat(
                deviceName = "z1-1",
                groupId = "z1",
                role = DeviceIdentity.Role.SOLDIER,
                state = DeviceRuntimeState.RUNNING_TASK,
                taskCount = 3,
                appVersion = "0.1.8",
                appVersionCode = 9,
                currentIp = null,
                lastError = null,
            ),
        )

        assertEquals("soldier", body.getString("role"))
        assertEquals("RUNNING_TASK", body.getString("state"))
        assertEquals("0.1.8", body.getString("appVersion"))
        assertEquals(9, readInt(body.text, "versionCode", -1))
        assertEquals("0.1.8", body.getString("versionName"))
        assertEquals("z1-1", body.getString("deviceName"))
    }

    @Test
    fun serializesDeviceCommandReport() {
        val body = AndroidServerApiJson.deviceCommandReportBody(
            DeviceCommandReport(
                commandId = "cmd_1",
                deviceName = "z1-1",
                groupId = "z1",
                command = DeviceCommandType.UPDATE_APP,
                success = false,
                message = "sha256 mismatch",
            ),
        )

        assertEquals("cmd_1", body.getString("commandId"))
        assertEquals("z1-1", body.getString("deviceName"))
        assertEquals("z1", body.getString("groupId"))
        assertEquals("UPDATE_APP", body.getString("command"))
        assertEquals(true, body.text.contains(""""success":false"""))
        assertEquals("sha256 mismatch", body.getString("message"))
    }

    @Test
    fun serializesCookieBodiesWithAccountAlias() {
        val save = AndroidServerApiJson.cookieSaveBody(
            deviceName = "z1-1",
            accountAlias = "naver_a",
            cookies = "NID_AUT=aaa; NID_SES=111",
        )
        val load = AndroidServerApiJson.cookieLoadBody(
            deviceName = "z1-1",
            accountAlias = "naver_a",
        )

        assertEquals("z1-1", save.getString("deviceName"))
        assertEquals("naver_a", save.getString("accountAlias"))
        assertEquals("NID_AUT=aaa; NID_SES=111", save.getString("cookies"))
        assertEquals("z1-1", load.getString("deviceName"))
        assertEquals("naver_a", load.getString("accountAlias"))
    }

    @Test
    fun serializesManualCookieAccountAsNull() {
        val body = AndroidServerApiJson.cookieLoadBody("z1-1", null)

        assertEquals(true, body.text.contains(""""accountAlias":null"""))
    }

    @Test
    fun parsesStrategyTaskLeaseResponse() {
        val json = """
            {
              "taskLeaseId": "task_123",
              "keyword": "1차",
              "secondKeyword": "2차",
              "linkUrl": "https://smartstore.naver.com/sunsaem/products/83539482665",
              "mid": "83539482665",
              "productTitle": "차이팟"
            }
        """.trimIndent()

        val lease = AndroidServerApiJson.parseStrategyTaskLease(json)

        assertEquals("task_123", lease?.taskLeaseId)
        assertEquals("1차", lease?.task?.keyword)
        assertEquals("2차", lease?.task?.secondKeyword)
        assertEquals("83539482665", lease?.task?.mid)
        assertEquals("차이팟", lease?.task?.productTitle)
    }
}
