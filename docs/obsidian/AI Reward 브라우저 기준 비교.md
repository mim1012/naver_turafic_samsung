---
title: AI Reward 브라우저 기준 비교
created: 2026-05-06
tags:
  - ai-reward
  - browser
  - comparison
---

# AI Reward 브라우저 기준 비교

## AI Reward 관측 기준

AI Reward 쪽에서 관측된 기준은 별도 Chromium 브라우저 앱 실행이다.

```text
com.goodreward.cashmoa
-> OfferWallActivity
-> com.rewardrop.offerwall.RdWebActivity
-> com.aibrowser.app/.main
-> org.chromium.chrome.browser.ChromeTabbedActivity
```

관측된 browser identity:

- Reward app: `com.goodreward.cashmoa`
- Browser app: `com.aibrowser.app`
- Browser activity: `org.chromium.chrome.browser.ChromeTabbedActivity`
- Chromium profile: `/data/data/com.aibrowser.app/app_chrome/Default`
- 관측 Chromium version: `139.0.7258.66`

## AI Reward의 쇼핑 흐름

```text
m.search.naver.com/search.naver
-> cr2.shopping.naver.com/adcr
-> cr3.shopping.naver.com/v2/bridge/searchGate
-> m.smartstore.naver.com/.../products/...
```

## Samsung 검증 흐름

현재 이 저장소의 2026-05-06 검증 흐름:

```text
PC
-> com.navertraffic.samsung/.strategy.SamsungBrowserLaunchActivity
-> com.sec.android.app.sbrowser/.SBrowserMainActivity
-> DevTools로 MID 확인
-> adb input tap으로 실제 좌표 터치
-> SmartStore 상품 상세
```

## 같은 부분

- Android 모바일 브라우저에서 실행한다.
- 모바일 네이버 검색 URL을 사용한다.
- 1차 검색 후 2차 검색을 실행한다.
- 실제 검색 결과 DOM에서 MID를 확인한다.
- MID가 포함된 tracked link 좌표를 터치한다.
- Naver tracking endpoint를 거쳐 SmartStore 상품 상세로 들어간다.
- 브라우저의 기존 쿠키/로그인 세션을 유지한다.

## 다른 부분

| 항목 | AI Reward | Samsung 검증 |
| --- | --- | --- |
| 브라우저 패키지 | `com.aibrowser.app` | `com.sec.android.app.sbrowser` |
| 실행 소유자 | Cashmoa offerwall | 테스트 앱 launcher |
| 브라우저 엔진 정체성 | 별도 Chromium app | Samsung Internet |
| UA/지문 | AI Reward Browser | Samsung Internet |
| 검증 방법 | runtime/history capture | DevTools + physical tap |

## 판단

현재 검증은 AI Reward browser identity 복제가 아니다.

검증된 것은 다음이다.

```text
검색 행동 + MID 탐색 + 실제 좌표 터치 + tracking endpoint + SmartStore 도달
```

따라서 비교 표현은 다음처럼 유지한다.

- 맞는 표현: “핵심 모바일 쇼핑 진입 경로는 동일 계열 endpoint를 탔다.”
- 피해야 할 표현: “AI Reward와 브라우저 지문까지 동일하다.”
- 피해야 할 표현: “AI Reward history 순서와 CDP network event 순서가 완전히 동일하다.”

## 관련 문서

- [[AI Reward Samsung 검증 인덱스]]
- [[2026-05-06 AI Reward Samsung MID 터치 검증]]
- [원문 비교 문서](../ai-reward-browser-comparison.md)
