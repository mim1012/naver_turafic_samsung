package com.navertraffic.samsung.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun throwsBlockedExceptionForGroupBlockedTaskLease() {
        val json = """
            {
              "blocked": true,
              "reason": "group_state_blocked",
              "groupState": "ROTATION_FAILED",
              "message": "group z1 is ROTATION_FAILED; task lease blocked"
            }
        """.trimIndent()

        val error = assertThrows(StrategyTaskLeaseBlockedException::class.java) {
            AndroidServerApiJson.throwIfTaskLeaseBlocked(json)
        }

        assertEquals("group_state_blocked", error.reason)
        assertEquals("ROTATION_FAILED", error.groupState)
        assertEquals("group z1 is ROTATION_FAILED; task lease blocked", error.message)
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
    fun parsesGroupControlResponseWithStartBotCommand() {
        val json = """
            {
              "groupState": "READY",
              "command": "NONE",
              "deviceCommand": {
                "id": "cmd_start_1",
                "type": "START_BOT"
              },
              "policy": {
                "rotateOwner": "z1",
                "rotateEveryGroupTasks": 10
              }
            }
        """.trimIndent()

        val response = AndroidServerApiJson.parseGroupControlResponse(json)

        assertEquals(DeviceCommandType.START_BOT, response.deviceCommand?.type)
        assertEquals("cmd_start_1", response.deviceCommand?.commandId)
    }

    @Test
    fun parsesMalformedStrategyGLeaseAsGTaskForValidation() {
        val json = """
            {
              "taskLeaseId": "sb_10_20_lease",
              "keyword": "검색어",
              "keywordName": "상품명",
              "linkUrl": "https://smartstore.naver.com/main/products/",
              "mid": "",
              "productName": "상품명 풀네임 테스트",
              "catalogMid": "catalog-123"
            }
        """.trimIndent()

        val lease = AndroidServerApiJson.parseStrategyTaskLease(json)

        assertEquals("sb_10_20_lease", lease?.taskLeaseId)
        assertEquals("상품명", lease?.taskG?.keywordName)
        assertEquals("", lease?.taskG?.mid)
        assertEquals("상품명 풀네임 테스트", lease?.taskG?.productName)
        assertEquals("catalog-123", lease?.taskG?.catalogMid)
        assertEquals("mid is required for Strategy G", lease?.taskG?.validate())
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
    fun serializesWaitingLoginHeartbeat() {
        val body = AndroidServerApiJson.deviceHeartbeatBody(
            DeviceHeartbeat(
                deviceName = "z1-1",
                groupId = "z1",
                role = DeviceIdentity.Role.SOLDIER,
                state = DeviceRuntimeState.WAITING_LOGIN,
                taskCount = 0,
                appVersion = "0.1.11",
                appVersionCode = 12,
                lastError = "naver_login_checking",
            ),
        )

        assertEquals("WAITING_LOGIN", body.getString("state"))
        assertEquals("naver_login_checking", body.getString("lastError"))
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
              "strategyGroup": "B",
              "strategyVersion": "b1.0.0",
              "strategyConfig": {
                "entryFlow": "search_url_two_step",
                "uaProfile": "chrome_137_mobile",
                "keywordMode": "full_name",
                "searchExecution": "url_load",
                "midMatchMode": "mid"
              },
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
        assertEquals("B", lease?.assignedStrategyGroup)
        assertEquals("b1.0.0", lease?.strategyVersion)
        assertEquals("search_url_two_step", lease?.strategyConfig?.entryFlow)
        assertEquals("chrome_137_mobile", lease?.strategyConfig?.uaProfile)
        assertEquals("full_name", lease?.strategyConfig?.keywordMode)
        assertEquals("url_load", lease?.strategyConfig?.searchExecution)
        assertEquals("mid", lease?.strategyConfig?.midMatchMode)
    }

    @Test
    fun serializesStrategyTaskReportWithStrategyMetadata() {
        val body = AndroidServerApiJson.strategyTaskReportBody(
            StrategyTaskReport(
                taskLeaseId = "task_123",
                deviceName = "z1-1",
                result = StrategyTaskResult.SUCCESS,
                strategyGroup = "B",
                strategyVersion = "b1.0.0",
                entryFlow = "search_url_two_step",
                uaProfile = "chrome_137_mobile",
                keywordMode = "full_name",
                searchExecution = "url_load",
                midMatchMode = "mid",
            ),
        )

        assertEquals("task_123", body.getString("taskLeaseId"))
        assertEquals("B", body.getString("strategyGroup"))
        assertEquals("b1.0.0", body.getString("strategyVersion"))
        assertEquals("search_url_two_step", body.getString("entryFlow"))
        assertEquals("chrome_137_mobile", body.getString("uaProfile"))
        assertEquals("full_name", body.getString("keywordMode"))
        assertEquals("url_load", body.getString("searchExecution"))
        assertEquals("mid", body.getString("midMatchMode"))
    }
}
