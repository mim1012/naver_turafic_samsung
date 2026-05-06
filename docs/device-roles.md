# Device Roles

The Android/Samsung line uses the same naming convention as the PlaceBot reference, with simpler labels:

- `z1`: boss device.
- `z1-1`: soldier device 1 under group `z1`.
- `z1-2`: soldier device 2 under group `z1`.

## Boss

- Keeps hotspot enabled manually.
- Runs Strategy A tasks.
- Owns group-level IP rotation.
- Toggles its own mobile data to rotate the public IP for all hotspot clients.

## Soldier

- Connects to the boss hotspot manually or through a connector later.
- Runs Strategy A tasks.
- Does not rotate its own mobile data.
- Pauses while the boss rotates group IP.

## Rotation Command

Boss rotation uses root:

```sh
su -c "svc data disable"
su -c "svc data enable"
```

If root is unavailable on the boss, group IP rotation cannot run. The server should mark the group as `rotation_failed` and pause or reroute work according to policy.
