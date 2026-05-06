---
title: 2026-05-06 AI Reward Samsung MID 터치 검증
created: 2026-05-06
device: S10 SM-G977N
serial: 172.30.1.78:35013
mid: 82095489871
tags:
  - ai-reward
  - samsung-internet
  - mid
  - test-0506
  - naver-shopping
---

# 2026-05-06 AI Reward Samsung MID 터치 검증

## 목적

AI Reward와 완전히 같은 브라우저 identity를 만들려는 검증이 아니다.

목적은 Samsung Internet 모바일 실행에서 다음이 가능한지 확인하는 것이다.

1. 1차 검색
2. 2차 검색
3. 실제 모바일 검색 결과에서 MID 동적 탐색
4. 탐색한 MID anchor의 실제 좌표 터치
5. `/searchGate` 포함 tracking endpoint 도달
6. SmartStore 상품 상세 도달

## 실행 환경

- Device: S10 `SM-G977N`
- ADB serial: `172.30.1.78:35013`
- Browser: Samsung Internet `com.sec.android.app.sbrowser`
- DevTools socket: `Terrace_devtools_remote`
- Test folder: `test/0506`
- Runner: `tools/adb-mid-exposure-check.mjs`
- Script: `test/0506/run-app-owned-devtools-mid-once.ps1`

## 검색 조건

- 1차 검색어: `양갈비`
- 2차 검색어: `최상급 어린양으로 만든 양꼬치 양갈비 양고기`
- Target MID: `82095489871`
- Final product: `m.smartstore.naver.com/thenaturefarmers/products/4550969819`

## 실행 명령

```powershell
.\test\0506\run-app-owned-devtools-mid-once.ps1
```

스크립트 기본값은 다음을 사용한다.

```powershell
$env:ADB_SERIAL = "172.30.1.78:35013"
$env:BROWSER_PACKAGE = "com.sec.android.app.sbrowser"
$env:DEVTOOLS_SOCKET = "Terrace_devtools_remote"
$env:APP_OWNED_LAUNCH = "1"
$env:TRACE_REDIRECTS = "1"
$env:REQUIRE_TRACKED_FLOW = "1"
```

## 최신 성공 증거

최신 검증 run:

- [log](../../test/0506/app-owned-devtools-mid-once-20260506-131205.log)
- [flow txt](../../test/0506/app-owned-devtools-mid-once-20260506-131205.flow.txt)
- [flow json](../../test/0506/app-owned-devtools-mid-once-20260506-131205.flow.json)
- [activity](../../test/0506/app-owned-devtools-mid-once-20260506-131205.activity.txt)
- [err log](../../test/0506/app-owned-devtools-mid-once-20260506-131205.err.log)

핵심 결과:

```text
hasSearch=true
hasClickBeacon=true
hasCr2=true
hasCr3SearchGate=true
hasSmartStore=true
complete: success=1/1
```

## MID 탐색과 터치

검색 결과 DOM에서 MID가 포함된 anchor를 동적으로 찾았다.

```text
MID(82095489871) 발견: tracked-search-gate, tap=(539,1003)
```

터치 대상 anchor:

```text
https://cr3.shopping.naver.com/v2/bridge/searchGate?nv_mid=82095489871...
```

이후 `adb input tap 539 1003`에 해당하는 실제 화면 좌표 터치가 수행됐다.

## 관측된 핵심 flow

최신 `flow.txt` 기준:

```text
m.search.naver.com/search.naver
m.search.naver.com/search.naver
cr3.shopping.naver.com/v2/bridge/searchGate
m.search.naver.com/p/crd/rd
cr2.shopping.naver.com/adcr
m.smartstore.naver.com/main/products/4550969819
m.smartstore.naver.com/thenaturefarmers/products/4550969819
```

최종 URL:

```text
https://m.smartstore.naver.com/thenaturefarmers/products/4550969819
```

## AI Reward와의 비교

AI Reward 기준 flow:

```text
m.search.naver.com/search.naver
-> cr2.shopping.naver.com/adcr
-> cr3.shopping.naver.com/v2/bridge/searchGate
-> m.smartstore.naver.com/.../products/...
```

Samsung 최신 검증 flow:

```text
m.search.naver.com/search.naver
-> cr3.shopping.naver.com/v2/bridge/searchGate
-> m.search.naver.com/p/crd/rd
-> cr2.shopping.naver.com/adcr
-> m.smartstore.naver.com/.../products/4550969819
```

판단:

- 핵심 endpoint는 모두 도달했다.
- `/searchGate`까지 포함해서 Naver tracking 경로를 탔다.
- SmartStore 상품 상세 도달도 확인됐다.
- 다만 CDP network event 순서는 AI Reward history 순서와 완전 동일하다고 단정하지 않는다.

## 구현 변경 요약

`tools/adb-mid-exposure-check.mjs`에 추가된 기능:

- `FLOW_OUTPUT`
- `FLOW_JSON_OUTPUT`
- `REQUIRE_TRACKED_FLOW`
- `TRACE_ALL_NETWORK`
- 핵심 URL classifier
- 핵심 flow artifact 저장
- 필수 endpoint 누락 시 실패 처리

`test/0506/run-app-owned-devtools-mid-once.ps1`에 추가된 기능:

- 실행마다 `.flow.txt`와 `.flow.json` 저장
- `REQUIRE_TRACKED_FLOW=1` 기본 적용
- activity/log/error/flow 경로 출력

## 남은 리스크

- Samsung Internet은 AI Reward Browser가 아니다.
- UA와 브라우저 지문은 동일하지 않다.
- Cashmoa offerwall ownership은 재현하지 않았다.
- 현재 launcher는 debug APK의 `SamsungBrowserLaunchActivity`를 사용한다.
- CDP 관측 순서와 브라우저 history 저장 순서는 다를 수 있다.

## 다음 반복 테스트 기준

반복 테스트는 매 run마다 아래 조건이 모두 true인지 확인한다.

```text
hasSearch=true
hasClickBeacon=true
hasCr2=true
hasCr3SearchGate=true
hasSmartStore=true
```

하나라도 false면 해당 run은 AI Reward path parity 검증 실패로 본다.

## APK-only 실행 스크립트

스크립트만 바꾸는 조건으로 APK 설치 후 실행하는 스크립트를 추가했다.

```powershell
.\test\0506\run-apk-yanggalbi-samsung-50.ps1
.\test\0506\run-apk-yanggalbi-s7-samsung-50.ps1
```

이 방식은 `MainActivity`를 다음 extra로 실행한다.

```text
autoRun=true
externalBrowser=true
loopCount=50
dryRun=false
```

중요한 제한:

- APK 코드 변경 없이 가능한 범위다.
- 현재 APK fallback task는 0506 양갈비 상품으로 고정되어 있다.
- 스크립트만으로 0505 Kellsen MID/검색어를 APK에 직접 주입할 수 없다.
- `externalBrowser=true`는 APK 내부 `SamsungBrowserLaunchActivity`를 사용하므로 Samsung Internet을 연다.
- Node/DevTools runner가 만들던 `.flow.json` strict endpoint 증거는 APK-only 방식에서 생성되지 않는다.
- APK-only 방식의 증거는 `logcat`과 `dumpsys activity`다.

## 서버 task lease 방식

AI Reward처럼 앱이 작업을 서버에서 내려받는 방식은 이미 APK에 구현되어 있다.

APK 실행 시 `serverUrl` extra를 넘기면 다음 흐름을 탄다.

```text
APK MainActivity
-> POST /android/tasks/lease
-> keyword, secondKeyword, linkUrl, mid, productTitle 수신
-> Strategy A 실행
-> POST /android/tasks/report
```

서버 task 방식 스크립트:

```powershell
.\test\0506\run-apk-server-yanggalbi-s10-50.ps1
.\test\0506\run-apk-server-yanggalbi-s7-50.ps1
.\test\0506\run-apk-server-kellsen-s10-50.ps1
```

로컬 서버 product 파일:

```text
test/0506/products-server-yanggalbi.json
test/0506/products-server-kellsen.json
test/0506/products-server-0505-0506.json
```

중요한 현재 동작:

- APK는 앱 시작 시 task를 한 번 lease한다.
- 현재 `loopCount=50`이면 같은 lease task를 50회 반복한다.
- 매 회차마다 서버에서 새 task를 받게 하려면 APK 코드 변경이 필요하다.
- 스크립트만 바꾸는 범위에서는 서버에서 받은 단일 task 반복 실행까지 가능하다.

## 50회 반복 테스트 결과

### S10 Samsung Internet

- Script: `test/0506/run-app-owned-devtools-mid-50.ps1`
- Device: S10 `SM-G977N`
- Serial: `172.30.1.78:35013`
- Browser: `com.sec.android.app.sbrowser`
- Launch: `APP_OWNED_LAUNCH=1`
- Summary: [app-owned-devtools-mid-50-20260506-154908.summary.txt](../../test/0506/app-owned-devtools-mid-50-20260506-154908.summary.txt)
- CSV: [app-owned-devtools-mid-50-20260506-154908.summary.csv](../../test/0506/app-owned-devtools-mid-50-20260506-154908.summary.csv)

결과:

```text
runs=50
pass=39
fail=11
```

해석:

- 1회차부터 11회차까지 실패했다.
- 실패 회차는 `hasSearch=true`만 남고 MID click 이후 flow가 없었다.
- 12회차부터 50회차까지는 strict 기준을 연속 통과했다.

### S7 Chrome

- Script: `test/0506/run-s7-chrome-mid-50.ps1`
- Device: S7 `SM-G930S`
- Serial: `ce12160c9966340705`
- Browser: `com.android.chrome`
- Launch: direct Chrome launch
- Summary: [s7-chrome-mid-50-20260506-161515.summary.txt](../../test/0506/s7-chrome-mid-50-20260506-161515.summary.txt)
- CSV: [s7-chrome-mid-50-20260506-161515.summary.csv](../../test/0506/s7-chrome-mid-50-20260506-161515.summary.csv)

결과:

```text
runs=50
pass=45
fail=5
```

실패 회차:

```text
10, 11, 17, 28, 29
```

실패 패턴:

```text
hasSearch=true
hasClickBeacon=false
hasCr2=false
hasCr3SearchGate=true
hasSmartStore=true
```

해석:

- S7 실패 5회는 최종 SmartStore 상품 상세까지는 도달했다.
- 다만 strict AI Reward path 기준에서 요구한 `m.search.naver.com/p/crd/rd`와 `cr2.shopping.naver.com/adcr`가 캡처되지 않았다.
- 따라서 S7은 상품 도달 안정성은 50/50에 가깝지만, tracking endpoint parity strict 기준은 45/50이다.

## 관련 문서

- [[AI Reward Samsung 검증 인덱스]]
- [[AI Reward 브라우저 기준 비교]]
- [test/0506 README](../../test/0506/README.md)
