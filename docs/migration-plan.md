# Migration Plan

## Non-Goals

- Do not modify `D:\Project\electrone_navershopping` for this line.
- Do not port every existing flow.
- Do not copy credentials or historical secrets.

## Milestone 1

- Build a separate Android app skeleton.
- Implement boss/soldier role parsing.
- Implement Strategy A task model.
- Run Strategy A through an internal Samsung-compatible WebView controller.
- Add boss-owned group IP rotation.

## Milestone 2

- Add durable task queue input.
- Add account lease API.
- Add group heartbeat and rotation commands.
- Add logs/results.
- Add Naver login session check.

## Milestone 3

- Decide whether real Samsung Internet automation requires:
  - Android intents only.
  - Accessibility/UIAutomator.
  - WebView-compatible approach.
  - Samsung Internet remote debugging or a custom bridged APK.

## Current Decision

Use a zero-like internal WebView controller first:

- Mobile Samsung-style UA.
- `X-Requested-With: com.sec.android.app.sbrowser`.
- CookieManager support for leased Naver cookies.
- Protection detection after each navigation step.

The app package remains `com.navertraffic.samsung` for now to avoid install conflicts with the real Samsung Internet app. A separate build flavor can later use `com.sec.android.app.sbrowser` only on dedicated test devices.
