---
title: 2026-05-09 Strategy B Referer+navigator 실험
created: 2026-05-09
device: S7 SM-G930S
serial: ce12160c9966340705
mid: 80919049889
tags:
  - strategy-b
  - referer-chain
  - navigator-spoof
  - s7
  - ip-rotation
  - test-0509
---

# 2026-05-09 Strategy B Referer+navigator 실험

## 실행 환경

| 항목 | 값 |
|------|-----|
| 기기 | SM-G930S (S7, Android 8.0) |
| Serial | `ce12160c9966340705` |
| APK | `com.navertraffic.samsung` WebView 모드 |
| 역할 | boss (z1) |
| loopCount | 200 (목표) |
| rotateEvery | 5 |
| 실행 시각 | 01:46 ~ 03:58 (05-10) |

## 실행 상품

| 항목 | 값 |
|------|-----|
| keyword | 귀리 |
| keyword_name | 국산100% 당일 볶은 귀리 가루 분말 |
| MID | 80919049889 |
| 스토어 (DB) | thenaturefarmers |
| 실제 착지 스토어 | 01090624981/products/2630073533 |
| 상품 순위 | 126위 |
| 출처 | Sellermate Supabase DB (`sellermate_slot_naver`) |

## Strategy A 대비 추가 적용

| 항목 | Strategy A | Strategy B |
|------|-----------|-----------|
| Referer 헤더 | ❌ 없음 | ✅ 자동 체인 (loadAndWait 이전 URL 캡처) |
| navigator.webdriver | ❌ 노출 | ✅ undefined |
| navigator.languages | 기본값 | ✅ ['ko-KR','ko','en-US','en'] |
| navigator.platform | 기본값 | ✅ 'Linux armv8l' |
| navigator.hardwareConcurrency | 기본값 | ✅ 4 |
| navigator.deviceMemory | 기본값 | ✅ 4 |
| window.chrome | undefined | ✅ {runtime:{}, app:{}} |
| 2차 검색어 꼬리 | 1차 키워드 마지막 단어 | ✅ 추천/할인/가격비교/인기/후기 순환 |
| 주입 시점 | 없음 | onPageStarted + onPageFinished |

### Referer 체인 흐름

```
about:blank
  → snsz.kr          (Referer: null)
  → 1차 검색          (Referer: snsz.kr 결과 URL)
  → 2차 검색          (Referer: 1차 검색 URL)
  → MID 클릭          (브라우저 자동 Referer)
  → m.naver.com       (Referer: 상품 상세 URL)
```

## 최종 결과

| 지표 | 값 |
|------|-----|
| 처리 루프 | **187/200** |
| 성공 건수 | **182건** |
| 성공률 (처리 기준) | **97.3%** |
| 성공률 (목표 기준) | **91.0%** |
| 총 실행 시간 | **2시간 12분** |
| 루프당 평균 | ~42초 |
| IP rotation | **36/36 성공** |
| 보호/캡차 신호 | **0회** |
| 크래시 | **1회** (187루프 후, m.naver.com 로드 직후) |

## 2차 검색어 조합 결과

| 조합 | 결과 | 사유 |
|------|------|------|
| 가루 분말 국산100% 귀리 추천 | ❌ 제외 | 캐러셀 5페이지 탐색 후 MID 미노출 |
| 국산100% 가루 볶은 귀리 가격비교 | ❌ 제외 | 동일 |
| 가루 분말 당일 귀리 할인 | ❌ 제외 | 동일 |
| 볶은 가루 분말 귀리 인기 | ❌ 제외 | 동일 |
| **당일 가루 볶은 귀리 후기** | ✅ **생존** | 캐러셀 1페이지 노출, 182건 전 루프 성공 |

> "귀리 후기" 꼬리 조합만 상품 노출. 나머지 4개는 귀리 126위 순위로 캐러셀 미노출.

## 발견 사항

- **꼬리키워드 "후기"의 특수성**: 동일 상품이 "추천/할인/가격비교/인기" 에서는 안 보이고 "후기" 조합에서만 캐러셀 1페이지에 노출. 네이버 쇼핑 알고리즘 차이 추정.
- **착지 URL 불일치**: Sellermate DB의 `thenaturefarmers` 스토어가 아닌 `01090624981` 스토어로 착지. MID는 동일하며 NaPm `tr=sls` 체인은 정상.
- **크래시 미확정**: 187루프 직후 m.naver.com 로드 완료 시점에서 발생. OOM 또는 렌더러 문제 추정. WebView 30루프 주기 재생성 타이밍과 무관한 구간.

## ADB 실행 명령

```bash
adb -s ce12160c9966340705 shell 'am start -n com.navertraffic.samsung/.ui.MainActivity \
  --ez autoRun true \
  --es deviceName "z1" \
  --ei loopCount 200 \
  --ei rotateEvery 5 \
  --es mid "80919049889" \
  --es keyword "귀리" \
  --es secondKeyword "국산100% 당일 볶은 귀리 가루 분말" \
  --es productTitle "귀리 가루 분말"'
```

## 관련 문서

- [[2026-05-09 Strategy A 기준선 200회 실험]]
- [[2026-05-09 A-B 전략 비교 및 다음 계획]]
- [상세 리포트](../../test/0509/result-0509-strategy-b-plan.md)
