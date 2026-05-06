# Group Control

Groups are identified by the `groupId` parsed from device names.

Examples:

- `z1`: boss and rotate owner for group `z1`.
- `z1-1`: soldier in group `z1`.
- `z1-2`: soldier in group `z1`.

## Group States

```text
READY
DRAINING
ROTATING
ROTATION_FAILED
PAUSED
STOPPED
```

`DRAINING` means the group has reached the rotation threshold, but one or more
devices are still finishing current work. The server must stop issuing new task
leases and wait until all devices are no longer `RUNNING_TASK`.

## Heartbeat

`POST /android/heartbeat`

```json
{
  "deviceName": "z1-1",
  "groupId": "z1",
  "role": "soldier",
  "state": "IDLE",
  "taskCount": 12,
  "currentIp": "1.2.3.4",
  "lastError": null
}
```

Response:

```json
{
  "groupState": "READY",
  "command": null,
  "policy": {
    "rotateOwner": "z1",
    "rotateEveryGroupTasks": 10,
    "drainTimeoutSec": 120,
    "pauseSoldiersDuringRotation": true,
    "pauseOnRotationFail": true
  }
}
```

## Drain Command

When the group reaches `rotateEveryGroupTasks`, soldiers should receive:

```json
{
  "groupState": "DRAINING",
  "command": "PAUSE_FOR_ROTATION",
  "commandId": "cmd_123"
}
```

The boss should not toggle data yet while any device is still `RUNNING_TASK`.

## Rotate Command

After every device is idle or otherwise not running a task, only the boss should receive:

```json
{
  "groupState": "ROTATING",
  "command": "ROTATE_GROUP_IP",
  "commandId": "cmd_123"
}
```

Soldiers should receive:

```json
{
  "groupState": "ROTATING",
  "command": "PAUSE_FOR_ROTATION",
  "commandId": "cmd_123"
}
```

## Rotation Report

`POST /android/group/rotation-report`

```json
{
  "commandId": "cmd_123",
  "deviceName": "z1",
  "groupId": "z1",
  "beforeIp": "1.2.3.4",
  "afterIp": "5.6.7.8",
  "success": true,
  "message": null
}
```

## Server Rule

The server should not lease new work while the group is `DRAINING` or `ROTATING`.
If the drain period exceeds `drainTimeoutSec`, mark the group `ROTATION_FAILED`
and keep new leases paused until an operator or recovery policy resumes the group.
