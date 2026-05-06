# PlaceBot Source Map

Reference project: `D:\Project\naver_place_2\android\PlaceBot`

Useful pieces:

- `DeviceIdentity.kt`
  - Role parsing from `z1`, `z1-1`.

- `BossController.kt`
  - Work loop without IP rotation.

- `SoldierController.kt`
  - Work loop with N-task rotation.

- `IpRotationManager.kt`
  - Root `svc data` rotation.

- `WifiConnector.kt`
  - Optional hotspot connection helper.

Do not copy:

- Supabase keys from Gradle files.
- Place-specific task repository as-is.
- Generated `build/` output.
