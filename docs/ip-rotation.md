# Group IP Rotation

The Android/Samsung line uses group-level IP rotation.

- Boss: hotspot provider and group IP rotation owner.
- Soldier: connected to the boss hotspot, no local IP rotation.

If soldiers use the boss hotspot, changing the boss mobile data connection changes the public IP for the whole group.

## Boss Sequence

```text
group task count reaches rotateEveryGroupTasks
server marks group z1 as DRAINING
server stops new task leases for the group
soldiers finish current work and pause before leasing new work
when every device is no longer RUNNING_TASK:
  server marks group z1 as ROTATING
boss executes:
  su -c "svc data disable"
  wait 5 seconds
  su -c "svc data enable"
  wait 6 seconds
boss verifies public IP
boss reports ROTATION_DONE
server marks group z1 as READY
soldiers resume
```

## Soldier Behavior

```text
heartbeat
if groupState == DRAINING:
  finish current task, then pause / do not lease new task
if groupState == ROTATING:
  pause / do not lease new task
if groupState == READY:
  lease and run task
```

## Server Policy

```json
{
  "groupId": "z1",
  "rotateOwner": "z1",
  "rotateEveryGroupTasks": 10,
  "drainTimeoutSec": 120,
  "pauseSoldiersDuringRotation": true,
  "verifyIpAfterRotation": true,
  "pauseOnRotationFail": true
}
```

If `drainTimeoutSec` expires before all devices leave `RUNNING_TASK`, the server
should mark the group as `ROTATION_FAILED` and keep leasing paused. This prevents
the boss from changing the hotspot IP while a soldier is still mid-task.

## Device Caveat

Some devices may drop hotspot clients when mobile data is toggled. If that happens, soldiers need a reconnect loop before resuming work.
