# Naver Traffic Android Samsung

Clean Android/Samsung Internet line for Naver traffic Strategy A.

This project intentionally does not modify the existing PC/Patchright runner in `D:\Project\electrone_navershopping`.

## Goal

Build `android-samsung-login -A` as a separate Android workflow:

1. Use real Android devices.
2. Keep Naver login state in Samsung Internet or Samsung-browser-compatible Android session.
3. Run Strategy A: first keyword search, second keyword search, product entry.
4. Support boss/soldier roles.
5. Rotate group IP only on boss devices by mobile data OFF/ON after the server drains the group.

## Current Scaffold

- `android/SamsungTrafficBot`
  - Android app skeleton.
  - Package: `com.navertraffic.samsung`.
  - Role parsing, boss/soldier controllers, Samsung browser launcher, and IP rotation skeleton.

- `docs`
  - Design notes for Strategy A, device roles, account leasing, IP rotation, and migration.
  - Group control model for boss-owned IP rotation.

- `server`
  - Dependency-free development server for Android API contract testing.
  - Implements account lease/report/release, heartbeat, and rotation-report endpoints in memory.
  - Can bridge existing Zero traffic API when `ZERO_TRAFFIC_API_URL` is set.

- `references`
  - Source maps to existing projects that should be referenced, not edited.

## Reference Sources

- `D:\Project\naver_place_2\android\PlaceBot`
  - Boss/soldier role split.
  - Root data toggle via `su -c "svc data disable/enable"`.

- `D:\Project\Navertrafic`
  - Samsung Internet package, WebView/cookie/header analysis.

- `D:\Project\electrone_navershopping`
  - Current Strategy A data shape and GUI task format.

## First Milestone

Create a minimal Android app that can:

1. Save device name: `z1` for boss, `z1-1` for soldier.
2. Use a Samsung-browser-compatible Android WebView session.
3. Execute one Strategy A URL sequence.
4. Call server heartbeat/account lease/report APIs when a server URL is configured.
5. Rotate boss group IP only after soldiers are no longer running tasks.

## Account Policy

Real Naver accounts are leased by a server-side account manager. The Android app should not store raw credentials locally or print them in logs.

See `docs/account-lease.md` and `docs/server-schema.sql`.

## Development Server

Create `server/accounts.local.json` from `server/accounts.example.json` for local testing.
Create `server/products.local.json` from `server/products.example.json` for product task testing.
Both local files are gitignored.

Run:

```powershell
node server\dev-server.js
```

To use the existing Zero/Supabase product queue instead of `products.local.json`:

```powershell
$env:ZERO_TRAFFIC_API_URL="http://127.0.0.1:8000/zero/api/v1/traffic"
node server\dev-server.js
```

To read the existing Supabase product queue directly:

```powershell
$env:SUPABASE_REST_URL="https://cwsdvgkjptuvbdtxcejt.supabase.co/rest/v1"
$env:SUPABASE_ANON_KEY="..."
$env:SUPABASE_SLOT_TABLE="sellermate_slot_naver"
node server\dev-server.js
```

Then enter the server URL in the Android app, for example:

```text
http://192.168.43.1:8080
```

Verification:

```powershell
node --test server\dev-server.test.js
```
