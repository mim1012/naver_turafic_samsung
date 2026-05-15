# 브라우저 계층 구조 및 상세페이지 진입 메커니즘

> Strategy A가 브라우저를 어떤 레이어로 구성하고, 상세페이지에 어떻게 진입하는지 정리한 문서.

---

## 1. 전체 계층 구조

```
ADB Intent (externalBrowser / dryRun 플래그)
         │
         ▼
   MainActivity
   └─ BrowserSession 선택
         │
    ┌────┼─────────────┐
    │    │             │
    ▼    ▼             ▼
DryRun  WebView   External
Session Session   Browser Session
(NoOp)  (내장)   (포그라운드)
    │    │             │
    │    │        SamsungBrowser
    │    │        LaunchActivity
    │    │             │
    │    │        Samsung Internet
    │    │        (별도 프로세스)
    │    │             │
    └────┴─────────────┘
              │
    SamsungBrowserStrategyA
    (전략 A 오케스트레이터)
              │
    ┌─────────┼──────────┐
    │         │          │
    ▼         ▼          ▼
  1차 검색  2차 검색  상세페이지
  + 스크롤  + 스크롤   진입
                        │
              ┌──────────┴──────────┐
              │                     │
              ▼                     ▼
         MID 클릭             직접 URL 로드
    (카로셀 스캔 후 탭)       (task.linkUrl)
```

---

## 2. BrowserSession 3가지 구현체

### 선택 기준 (MainActivity)

```kotlin
val session = when {
    dryRun          -> DryRunBrowserSession(::appendLog)
    externalBrowser -> ExternalSamsungBrowserSession(applicationContext, ::appendLog)
    else            -> WebViewBrowserSession(webViewManager)
}
```

| 구현체 | 선택 조건 | 페이지 텍스트 검사 | 속도 |
|--------|-----------|------------------|------|
| `WebViewBrowserSession` | 기본값 | 가능 (`supportsPageInspection = true`) | 보통 |
| `ExternalSamsungBrowserSession` | `externalBrowser = true` | 불가 | 느림 (렌더링 대기 필요) |
| `DryRunBrowserSession` | `dryRun = true` | 불가 | 즉시 (NoOp) |

---

### 2-1. WebViewBrowserSession (내장 WebView)

APK 내부 WebView에서 직접 페이지를 렌더링한다. 모든 작업은 `SamsungWebViewManager`에 위임한다.

**MID 클릭**: JavaScript로 DOM을 스캔해 해당 요소 좌표를 계산 → `tapCssPoint()` 로 터치 발송.

**보호 감지**: `visibleText()`로 페이지 텍스트를 직접 읽어 캡차·로그인 프롬프트 감지 가능.

```kotlin
override suspend fun loadAndWait(url: String, timeoutMs: Long) {
    webViewManager.loadAndWait(url, timeoutMs)
}
override suspend fun clickMidLink(mid: String, titleHint: String?): Boolean {
    return webViewManager.clickMidLink(mid)
}
```

---

### 2-2. ExternalSamsungBrowserSession (외부 Samsung Internet)

APK는 백그라운드, Samsung Internet 브라우저가 포그라운드에서 페이지를 렌더링한다. 직접 DOM에 접근할 수 없으므로 Accessibility API로 상호작용한다.

**URL 로드 흐름**:
```
ExternalSamsungBrowserSession.loadAndWait()
  └─ Intent → SamsungBrowserLaunchActivity
       └─ Intent(ACTION_VIEW) → Samsung Internet 프로세스
```

**지연 설정**: Samsung Internet 렌더링이 끝날 때까지 기다려야 하므로 stepDelayMs = 1,200ms, productDelayMs = 2,500ms 사용.

**페이지 검사**: `supportsPageInspection = false` → 보호 신호 감지 불가. 오류 발생 시 다음 task로 넘어가는 방식으로 처리.

---

### 2-3. DryRunBrowserSession (테스트 모드)

모든 브라우저 작업을 스킵하고 URL만 로그에 기록한다. 실제 사이트 접속 없이 전략 흐름 검증용.

```kotlin
override suspend fun loadAndWait(url: String, timeoutMs: Long) {
    lastUrl = url
    log("DRY_RUN URL 기록: $url")
}
override suspend fun visibleText(): String = ""
```

---

## 3. SamsungBrowserLaunchActivity

외부 Samsung Internet을 Intent로 시작하는 **중개 Activity**. 화면 표시 없이 즉시 finish 한다.

```
AndroidManifest:
  android:noHistory="true"         ← 백스택에 남지 않음
  android:excludeFromRecents="true" ← 최근 앱 목록 미노출
```

**Samsung Internet Activity 해석 순서**:

1. PackageManager에서 `https://m.naver.com` VIEW 핸들러 조회 (동적 해석)
2. 없으면 알려진 Activity 이름 순서대로 fallback:
   - `com.sec.android.app.sbrowser.ActivityMCloud` (S7 등 구형)
   - `com.sec.android.app.sbrowser.SBrowserMainActivity` (신형)

```kotlin
startActivity(
    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        component = ComponentName("com.sec.android.app.sbrowser", activityName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
)
finish()
```

---

## 4. 상세페이지 진입 방식

### 방식 A: MID 클릭 (`useMidClick = true`)

2차 검색 결과 카로셀에서 MID가 일치하는 상품을 찾아 탭한 뒤, 추적 리다이렉트를 통해 상세페이지에 도달하는 방식.

```
2차 검색결과 페이지
  └─ clickMidLink(mid)
       ├─ WebView: buildMidScanJs() → JS로 DOM 스캔 → tapCssPoint()
       └─ External: BrowserAccessibilityService.clickProduct() → Gesture tap
           │
           ▼ 추적 리다이렉트 자동 실행
           └─ 상세페이지 도달
```

**MID 스캔 우선순위** (WebView JS):

| 우선순위 | 방법 | 매칭 조건 |
|---------|------|----------|
| 0 | `tracked-search-gate` | href에 `/p/crd/rd` 또는 `cr.shopping` + MID 포함 |
| 1 | `nv_mid` | href 파라미터 `nv_mid` 값이 MID |
| 2 | `searchGate` | href에 `searchGate` + MID 포함 |
| 3 | `data-shp-contents-id` | `data-shp-contents-id` 속성값이 MID |
| 4 | `aria-product-id` | aria-labelledby의 product ID |
| 5 | `data-attr-mid` | dataset에 MID 포함 |
| 6 | `direct-product` | href가 `/products/{MID}` 형태 |

**카로셀 탐색**: MID가 현재 페이지에 없으면 "다음" 버튼을 클릭해 최대 5페이지까지 탐색.

```kotlin
repeat(maxCarouselPages) { pageIdx ->
    val result = evaluateText(buildMidScanJs(mid))
    if (result.startsWith("found|")) {
        tapCssPoint(cssX, cssY)
        return true
    }
    clickShopCarouselNext(maxCarouselPages)  // 다음 카로셀 페이지
    delay(900)
}
```

---

### 방식 B: 직접 URL 로드

`task.linkUrl` 을 그대로 `loadAndWait()` 에 넘겨 상세페이지에 직접 접속하는 방식. MID가 없거나 `useMidClick = false` 일 때 사용.

```kotlin
browserSession.loadAndWait(task.linkUrl)
delay(productDelayMs)
```

---

## 5. Accessibility Service (외부 브라우저 전용)

`ExternalSamsungBrowserSession` 에서만 사용. 다른 프로세스(Samsung Internet)에 접근하기 위해 Android Accessibility API를 통해 노드 탐색과 Gesture 발송을 수행한다.

### 상품 클릭 흐름

```
clickProduct(titleHint)
  └─ 상품명을 단어 분리 (공백 기준, 2글자 이상)
       └─ repeat(8) { 스크롤하며 반복 }
            ├─ findBestNode(): AccessibilityNodeInfo 트리 순회
            │    └─ 단어 2개 이상 매칭되는 노드를 score 기준으로 선택
            └─ clickNode(): ACTION_CLICK → 실패 시 gesture tap
```

### 스와이프 (swipeDetail)

```
화면 높이 78% 지점 → 32% 지점으로 직선 스와이프
GestureDescription.Builder로 stroke 구성 → dispatchGesture()
```

### Gesture 발송 구조

```kotlin
val path = Path().apply { moveTo(x, startY); lineTo(x, endY) }
val gesture = GestureDescription.Builder()
    .addStroke(StrokeDescription(path, startMs = 0, durationMs))
    .build()
dispatchGesture(gesture, callback, null)
```

---

## 6. Strategy A 전체 실행 흐름

```
runDetailed(task)
  │
  ├─ 1. 1차 검색 loadAndWait(naverSearch(keyword))
  ├─ 2. 스크롤 ×2 + 보호 신호 감지
  │
  ├─ 3. 2차 검색 loadAndWait(naverSearch(secondKeyword))
  ├─ 4. 스크롤 ×2 + 보호 신호 감지
  │
  ├─ 5. 상세페이지 진입
  │     ├─ [MID 있음] clickMidLink(mid) → delay(productDelayMs)
  │     └─ [MID 없음] loadAndWait(linkUrl) → delay(productDelayMs)
  │
  ├─ 6. 상세페이지 스크롤 + 보호 신호 감지
  │
  └─ 7. 브라우저 리셋 → 네이버 메인홈 로드 (세션 유지)
```

**모드별 딜레이**:

| 모드 | stepDelayMs | productDelayMs |
|------|-------------|----------------|
| DryRun | 0 | 0 |
| ExternalBrowser | 1,200 | 2,500 |
| WebView (기본) | 3,000 | 5,000 |

---

## 7. Controller 계층 (Boss / Soldier)

기기 이름(`z1` vs `z1-1`, `z1-2`, ...)에 따라 역할이 결정된다. 두 컨트롤러 모두 내부에서 `SamsungBrowserStrategyA.runDetailed()` 를 동일하게 호출한다.

```
MainActivity
  └─ DeviceIdentity.parse(deviceName)
       ├─ isBoss → BossController
       └─ isSoldier → SoldierController
```

| 역할 | 추가 기능 |
|------|----------|
| **Boss** | task 완료 N회마다 `svc data disable/enable` 로 IP 로테이션 실행 |
| **Soldier** | 실행 전 heartbeat에서 `PAUSE_FOR_ROTATION` 신호 수신 시 대기 |

---

## 8. 핵심 파일 경로

| 역할 | 파일 |
|------|------|
| BrowserSession 선택 | `ui/MainActivity.kt` |
| 전략 A 오케스트레이터 | `strategy/SamsungBrowserStrategyA.kt` |
| 외부 브라우저 세션 | `strategy/ExternalSamsungBrowserSession.kt` |
| WebView 세션 | `strategy/WebViewBrowserSession.kt` |
| 중개 Activity | `strategy/SamsungBrowserLaunchActivity.kt` |
| WebView 관리 + MID 스캔 | `strategy/SamsungWebViewManager.kt` |
| Accessibility 자동화 | `strategy/BrowserAccessibilityService.kt` |
| Boss 컨트롤러 | `boss/BossController.kt` |
| Soldier 컨트롤러 | `soldier/SoldierController.kt` |
