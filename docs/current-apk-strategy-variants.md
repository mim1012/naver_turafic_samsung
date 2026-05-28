# Current APK Strategy Variants

기준 APK: `0.1.36`, `versionCode=37`

배포 경로:

```text
https://www.sellermate.ai.kr/android/app-release/latest
```

현재 latest endpoint는 GitHub Release `android-0.1.36`의 APK를 반환한다.

## Summary

현재 배포 APK에서 실제 실행 가능한 전략 분기는 `A`, `B`, `C`, `G`다.
`D`는 APK 안에 실행 로직이 없으므로 task가 내려오면 실패 보고된다.

| strategy_group | APK 지원 | 실행 전략 | 핵심 의미 |
| --- | --- | --- | --- |
| `A` | 지원 | `SamsungBrowserStrategyA` + `StrategyVariant.A` | URL 직접 로드 기준선 |
| `B` | 지원 | `SamsungBrowserStrategyA` + `StrategyVariant.B` | 검색창 입력 후 검색 제출 |
| `C` | 지원 | `SamsungBrowserStrategyA` + `StrategyVariant.C` | 현재 B와 동일 로직, 다른 cohort 라벨 |
| `G` 또는 미지정 | 지원 | `SamsungBrowserStrategyG` | 현재 기본 운영 전략 |
| `D` | 미지원 | 없음 | `unsupported_strategy_group:D`로 실패 보고 |

## Dispatch Rule

앱은 기본적으로 `runStrategyG()`로 시작한다. 다만 서버 task lease에
`strategyGroup`이 있으면 APK 내부에서 다음처럼 분기한다.

```text
strategyGroup=A -> StrategyVariant.A
strategyGroup=B -> StrategyVariant.B
strategyGroup=C -> StrategyVariant.C
strategyGroup 없음/LEGACY/UNASSIGNED -> 기본 G
그 외 값(D 등) -> unsupported_strategy_group
```

## Strategy G

현재 기본 운영 전략이다.

동작 흐름:

1. WebView를 Chrome 137 모드 UA로 설정한다.
2. 1차 검색어를 5단어 통합 검색어로 만든다.
3. 1차 검색은 검색 URL을 직접 로드한다.
4. 1차 결과에서 탐색 스크롤을 수행한다.
5. 2차 검색어를 선택한다.
6. 2차 검색은 검색창 값을 갈아끼운 뒤 Enter 제출한다.
7. 2차 검색 직후 쇼핑 영역까지 먼저 스크롤한다.
8. MID 상품을 찾으며 스크롤하고, 찾으면 클릭한다.
9. 상세 페이지 DOM을 확인한다.
10. 상세 페이지에서 아래, 위, 아래 오실레이션 스크롤을 수행한다.

특징:

- 기본 production 전략이다.
- 2차 검색어가 반복 실패하면 실패 phrase를 기억한다.
- 같은 MID에서 미노출이 누적되면 풀검색어 또는 상품명 기반 검색으로 전환한다.
- 상세 페이지 DOM 확인 실패나 429는 실패로 보고한다.

## Strategy A

기준선 전략이다.

동작 흐름:

1. 네이버 모바일 홈으로 이동한다.
2. 검색창을 탭한다.
3. 1차 자동완성 시뮬레이션을 수행한다.
4. 1차 검색 URL을 직접 로드한다.
5. 1차 검색 결과에서 스크롤한다.
6. 검색창을 다시 탭한다.
7. 2차 자동완성 시뮬레이션을 수행한다.
8. 2차 검색 URL을 직접 로드한다.
9. 2차 검색 결과에서 스크롤한다.
10. MID 상품을 찾아 클릭한다.
11. 추적 리다이렉트 후 상세 페이지에서 스크롤한다.
12. 네이버 홈으로 돌아간다.

특징:

- 검색 제출을 실제 버튼/Enter로 하지 않고 URL 직접 로드로 처리한다.
- 가장 단순한 기준선으로 쓰기 좋다.
- 사용자 입력 행동 신호는 B/C보다 약하다.

## Strategy B

Strategy A의 입력/검색 제출 방식만 바꾼 변형이다.

동작 흐름:

1. 네이버 모바일 홈으로 이동한다.
2. 검색창을 탭한다.
3. 1차 검색어를 검색창에 입력한다.
4. 1차 자동완성 시뮬레이션을 수행한다.
5. 검색 버튼을 탭해서 1차 검색을 제출한다.
6. 1차 검색 결과에서 스크롤한다.
7. 검색창을 다시 탭한다.
8. 2차 검색어를 검색창에 입력한다.
9. 2차 자동완성 시뮬레이션을 수행한다.
10. React 입력값 리셋 방지를 위해 2차 검색어를 한 번 더 입력한다.
11. 검색 버튼을 탭해서 2차 검색을 제출한다.
12. MID 상품을 찾아 클릭한다.
13. 상세 페이지 스크롤 후 네이버 홈으로 돌아간다.

특징:

- URL 직접 로드보다 실제 사용자 검색 행동에 가깝다.
- A 대비 핵심 차이는 `typeIntoSearchBar` + `tapSearchSubmitAndWait`이다.
- AI Reward 방식에 맞춘 실험군으로 볼 수 있다.

## Strategy C

현재 APK에서 C는 B와 동일한 실행 로직이다.

차이점:

- 코드상 별도의 C 전용 행동은 없다.
- `StrategyVariant.C`로 로그와 report에는 C로 남는다.
- 실제 검색 동작은 B와 같다.

해석:

- C는 전략 차이를 보기보다 상품군, 키워드군, 계정군, 기기군을 나눠 교차 검증하는 라벨로 쓰는 것이 맞다.
- B와 C의 순위 차이가 발생하면 전략 구현 차이보다는 배정된 상품/키워드/기기/계정 조건 차이일 가능성이 크다.

## Strategy D

현재 배포 APK에는 D 전략이 없다.

동작:

```text
strategyGroup=D -> unsupported_strategy_group:D
```

결과:

- task는 실행되지 않는다.
- 실패 report가 올라간다.
- 순위 상승 테스트군으로 사용할 수 없다.

D를 실제 실험군으로 쓰려면 APK에 다음 중 하나를 추가해야 한다.

- `StrategyVariant.D`
- 또는 별도 `SamsungBrowserStrategyD`
- 그리고 `TaskLease.assignedVariant()`에서 `D` 매핑 추가

## Test Group Recommendation

현재 배포 APK만 기준으로 A/B/C/D 실험을 하고 싶다면 `D` 대신 `G`를 네 번째 군으로 쓰는 것이 현실적이다.

| 실험군 | DB 값 | 의미 |
| --- | --- | --- |
| A군 | `strategy_group = 'A'` | URL 직접 로드 기준선 |
| B군 | `strategy_group = 'B'` | 검색창 입력 + 검색 제출 |
| C군 | `strategy_group = 'C'` | B 동일 로직의 별도 cohort |
| D군 대체 | `strategy_group is null` 또는 `G` | 기본 운영 G 전략 |

정확히 `A/B/C/D` 네 개 전략을 비교하려면 D 구현 후 새 APK를 배포해야 한다.
