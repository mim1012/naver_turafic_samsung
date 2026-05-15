# 0507 테스트 — APK 최초 실전 운영

날짜: 2026-05-07  
기기: Samsung Galaxy S7 (SM-G930S, Android 8.0)  
앱: `com.navertraffic.samsung` debug APK  
역할: `z1-1` (SoldierController)

---

## 이전 방식 vs 오늘 방식

| 항목 | 이전 (0506) | 오늘 (0507) |
|---|---|---|
| 실행 주체 | `adb-mid-exposure-check.mjs` (Node.js) | Android APK 자체 |
| 브라우저 | Samsung Internet 외부 실행 | **WebView 내장** |
| 터치 | `adb input tap x y` (CDP 좌표 계산) | `tapCssPoint()` → MotionEvent (WebView JS) |
| 네비게이션 | `am start ACTION_VIEW` + CDP redirect trace | `loadUrl()` + `WebViewClient.onPageFinished` |
| 로그인 | CDP `Network.setCookies` 또는 수동 | **쿠키 자동 복원** (`CookieManager` disk 영속) |
| 자동 시작 | ADB intent 수동 | **세션+크레덴셜 감지 시 자동 시작** |

---

## 브라우저 지문

```
User-Agent: Mozilla/5.0 (Linux; Android 8.0.0; SM-G930S) AppleWebKit/537.36
            (KHTML, like Gecko) Chrome/136.0.7103.127 Mobile Safari/537.36
```

- JavaScript: 활성화
- DOM Storage: 활성화  
- Hardware Acceleration: 활성화  
- Cookie: `CookieManager` disk 영속 (앱 재시작 후에도 NID_AUT + NID_SES 유지)
- WebContents Debugging: 활성화 (debug APK)

---

## 상품 정보

| 항목 | 값 |
|---|---|
| MID | `88411024518` |
| 상품명 | 오볼로 미니 캣타워 |
| 스토어 | o-volo |
| 상품 URL | `https://m.smartstore.naver.com/o-volo/products/10866518505` |

---

## 키워드 전략

### 1차 검색어
```
고양이 캣타워
```

### 2차 검색어 생성 방식
- 풀(pool): `오볼로 미니 낮은 소형 고양이 켓타워 먼치킨 뚱냥이 노묘` (꼬리키워드 제거 후 9개)
- 꼬리키워드: `캣타워`
- 조합 규칙: 풀에서 **4개 랜덤** 선택 + 꼬리키워드 → 5가지 고유 조합 생성

### 오늘 생성된 5개 조합 (예시)
```
[1] 오볼로 뚱냥이 노묘 켓타워 캣타워
[2] 소형 낮은 먼치킨 뚱냥이 캣타워
[3] 미니 노묘 먼치킨 뚱냥이 캣타워
[4] 먼치킨 고양이 낮은 노묘 캣타워
[5] 소형 먼치킨 오볼로 미니 캣타워
```
> 앱 실행 시 매번 랜덤 재생성. 위 조합은 21:34 세션 기준.

---

## Strategy A 흐름

```
1. 네이버 쿠키 세션 확인 → 로그인 생략 (NID_AUT + NID_SES 유지)
2. 1차 검색: https://m.search.naver.com/search.naver?where=m&query=고양이+캣타워
3. 2차 검색: https://m.search.naver.com/search.naver?where=m&query={조합 키워드}
4. 플러스스토어 캐러셀에서 MID(88411024518) 상품 탐색
   - tracked-search-gate 링크 감지 → tapCssPoint() 터치
   - 최대 5페이지까지 캐러셀 순회
5. 추적 리다이렉트 대기 (productDelayMs=5000ms)
   → https://m.smartstore.naver.com/o-volo/products/10866518505?nl-query=...&NaPm=...
6. 상세 페이지 스와이프 (detailSwipeMs=2000ms)
7. resetSurface() → about:blank
8. 네이버 메인홈 로드: https://m.naver.com/
9. [다음 반복으로]
```

**MID 미발견 시**: 해당 2차 검색어 조합을 `excludedCombos`에 등록, 잔여 조합으로만 순환

---

## 실행 설정

| 항목 | 값 |
|---|---|
| 반복 횟수 | 50회 |
| 조합 수 | 5개 순환 (콤보당 약 10회) |
| stepDelayMs | 3,000ms |
| productDelayMs | 5,000ms |
| detailSwipeMs | 2,000ms |
| 서버 연동 | 없음 (로컬 smoke 모드) |
| 백그라운드 유지 | `BotKeepAliveService` (Foreground Service, dataSync) |

---

## 확인된 추적 파라미터 (실측)

오늘 실행에서 상품 상세 로드 시 확인된 NaPm 추적 파라미터:

```
nl-query=오볼로+뚱냥이+노묘+켓타워+캣타워
nl-ts-pid=jQyZ%2FsqXKcossjI8AZw-333938
NaPm=ct%3Dmovgne7s%7Cci%3Df170511e...%7Ctr%3Dsls%7Csn%3D7220747
```

- `tr=sls`: 쇼핑 검색 경로(search list) 추적 확인
- `sn=7220747`: 스토어 번호
- 검색 → MID 터치 → 추적 리다이렉트 → 상품 상세 전체 흐름 정상

---

## 이슈 및 수정 내역

| 이슈 | 원인 | 수정 |
|---|---|---|
| MID 클릭 후 상품 상세 미진입 | 클릭 후 `resetSurface()` + 직접 URL 로드 → 추적 누락 | MID 클릭 후 tracking redirect 자연 대기로 변경 |
| `SamsungBrowserLaunchActivity` 크래시 | `SBrowserMainActivity` 하드코딩 — 이 기기 버전에 없음 | `PackageManager.queryIntentActivities()` → `ActivityMCloud` fallback 자동 감지 |
| 2차 검색어 풀에 꼬리키워드 중복 | 풀 단어에 `캣타워` 포함 상태에서 꼬리 추가 | `generateCombinations()` 풀에서 꼬리키워드 사전 제거 |
