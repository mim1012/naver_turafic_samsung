---
title: AI Reward Samsung 검증 인덱스
created: 2026-05-06
tags:
  - ai-reward
  - samsung-internet
  - naver-shopping
  - verification
---

# AI Reward Samsung 검증 인덱스

## 핵심 문서

- [[2026-05-06 AI Reward Samsung MID 터치 검증]]
- [[AI Reward 브라우저 기준 비교]]
- [AI Reward runtime snapshot plan](../ai-reward-runtime-snapshot-plan.md)

## 0509 실험 — Strategy A/B (S7)

- [[2026-05-09 Strategy A 기준선 200회 실험]] — 미니캣타워, 200/200, IP rotation 40/40
- [[2026-05-09 Strategy B Referer navigator 실험]] — 귀리, Referer+navigator 스푸핑, 182/200
- [[2026-05-09 A-B 전략 비교 및 다음 계획]] — 비교 결론 및 다음 실험 계획

## 현재 결론

Samsung Internet 기반 모바일 실행은 AI Reward와 브라우저 패키지/지문은 다르다.

다만 2026-05-06 S10 1회성 검증에서 다음 행동 경로는 확인됐다.

1. 모바일 네이버 1차 검색
2. 모바일 네이버 2차 검색
3. 실제 렌더링된 검색 결과에서 `MID=82095489871` 동적 탐색
4. MID가 포함된 tracked anchor 좌표 터치
5. Naver 쇼핑 tracking endpoint 통과
6. SmartStore 상품 상세 도달

## 50회 반복 결과

| 기기 | 브라우저 | 기준 | 결과 |
| --- | --- | --- | --- |
| S10 `SM-G977N` | Samsung Internet | app-owned launch + strict tracked flow | 39/50 pass |
| S7 `SM-G930S` | Chrome | direct Chrome launch + strict tracked flow | 45/50 pass |

S10은 초반 11회가 MID/상세 진입 전 단계에서 실패했고, 12회차부터 50회차까지 연속 통과했다.

S7은 5회 실패했지만 모두 SmartStore 상품 상세까지는 도달했다. 실패 이유는 strict 기준상 `m.search.naver.com/p/crd/rd`와 `cr2.shopping.naver.com/adcr`가 캡처되지 않았기 때문이다.

## 검증된 핵심 URL

```text
m.search.naver.com/search.naver
m.search.naver.com/p/crd/rd
cr3.shopping.naver.com/v2/bridge/searchGate
cr2.shopping.naver.com/adcr
m.smartstore.naver.com/.../products/4550969819
```

## 주의할 점

- `com.sec.android.app.sbrowser`는 `com.aibrowser.app`가 아니다.
- UA, Client Hints, 브라우저 지문은 AI Reward와 동일하게 맞춘 상태가 아니다.
- 현재 비교의 의미는 browser identity 복제가 아니라 mobile behavior/path parity 검증이다.
- CDP 네트워크 이벤트 순서는 AI Reward history 캡처 순서와 1:1로 같다고 단정하지 않는다.
- UA, Client Hints, viewport, WebGL/GPU 값은 비교 증거로만 수집한다. `test/0506` strict pass 기준은 shopping path endpoint와 최종 상품 도달이다.

## 실행 스크립트

```powershell
.\test\0506\run-app-owned-devtools-mid-once.ps1
```

해당 스크립트는 S10 기본값 `172.30.1.78:35013`을 사용한다.

## 관련 원문

- [docs/ai-reward-browser-comparison.md](../ai-reward-browser-comparison.md)
- [docs/ai-reward-runtime-snapshot-plan.md](../ai-reward-runtime-snapshot-plan.md)
- [test/0506/README.md](../../test/0506/README.md)
- [test/0506/app-owned-devtools-mid-once-20260506-131205.flow.txt](../../test/0506/app-owned-devtools-mid-once-20260506-131205.flow.txt)
- [S10 50회 summary](../../test/0506/app-owned-devtools-mid-50-20260506-154908.summary.txt)
- [S7 50회 summary](../../test/0506/s7-chrome-mid-50-20260506-161515.summary.txt)
