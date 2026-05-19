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
    fun serializesHeartbeatWithLowercaseRoleAndUppercaseState() {
        val body = AndroidServerApiJson.deviceHeartbeatBody(
            DeviceHeartbeat(
                deviceName = "z1-1",
                groupId = "z1",
                role = DeviceIdentity.Role.SOLDIER,
                state = DeviceRuntimeState.RUNNING_TASK,
                taskCount = 3,
                appVersion = "0.1.7",
                currentIp = null,
                lastError = null,
            ),
        )

        assertEquals("soldier", body.getString("role"))
        assertEquals("RUNNING_TASK", body.getString("state"))
        assertEquals("0.1.7", body.getString("appVersion"))
        assertEquals("z1-1", body.getString("deviceName"))
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
