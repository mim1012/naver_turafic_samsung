# AI Reward 기준 격차 분석 및 0508 실행계획

> 작성일: 2026-05-08  
> 분석 기준: `D:\Project\Ai reward\` 프로젝트 실측 데이터

---

## 1. AI Reward 실제 동작 기준 (정답지)

### HTTP 요청 헤더
```
User-Agent:         Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36
                    (KHTML, like Gecko) SamsungBrowser/29.0 Chrome/136.0.0.0
                    Mobile Safari/537.36
sec-ch-ua:          "Chromium";v="136", "Samsung Internet";v="29", "Not.A/Brand";v="99"
sec-ch-ua-mobile:   ?1
sec-ch-ua-platform: "Android"
Accept-Language:    ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7
```

### 검색 URL 구조
```
1차: https://m.search.naver.com/search.naver?sm=mtp_hty.top&where=m&query=<keyword>
2차: https://m.search.naver.com/search.naver?sm=mtb_hty.top&where=m&ssc=tab.m.all&oquery=<1차키워드>&query=<2차키워드>
```

### 필수 리다이렉트 체인
```
클릭 → p-crd-rd → cr2/adcr → cr3/searchGate → m.smartstore.naver.com/products/{MID}
```

---

## 2. 현재 구현과의 격차

| 항목 | AI Reward | 현재 (0507) | 상태 |
|------|-----------|------------|------|
| UA | SamsungBrowser/29.0 Chrome/136 | 기기 기본값 | 누락 |
| sec-ch-ua | Samsung Internet 브랜드 | 없음 | 누락 |
| 1차 검색 sm | `sm=mtp_hty.top` | 없음 | 누락 |
| 2차 검색 sm | `sm=mtb_hty.top` + oquery + ssc | 없음 | 누락 |
| 초기 랜딩 | https://snsz.kr 경유 | 없음 | 누락 |
| 리다이렉트 체인 | 전체 경유 | 0506 실측 0% | 미달 |

---

## 3. 0508 실행계획 — 한 번에 전부 맞춘다

정답지가 이미 있는 상황이므로 변수를 쪼갤 이유가 없다.  
위 격차 항목을 전부 반영한 뒤 체인 완성율을 측정한다.

### 3-1. SamsungBrowserStrategyA.kt — 검색 URL 분리

```kotlin
private fun buildFirstSearchUrl(query: String): String {
    val q = URLEncoder.encode(query, "UTF-8")
    return "https://m.search.naver.com/search.naver?sm=mtp_hty.top&where=m&query=$q"
}

private fun buildSecondSearchUrl(firstKeyword: String, secondKeyword: String): String {
    val oq = URLEncoder.encode(firstKeyword, "UTF-8")
    val q  = URLEncoder.encode(secondKeyword, "UTF-8")
    return "https://m.search.naver.com/search.naver?sm=mtb_hty.top&where=m&ssc=tab.m.all&oquery=$oq&query=$q"
}
```

`runDetailed()` 에서 1차/2차 검색 호출을 각각 분리된 함수로 교체.

### 3-2. SamsungWebViewManager.kt — UA 오버라이드

```kotlin
webView.settings.userAgentString =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) SamsungBrowser/29.0 Chrome/136.0.0.0 Mobile Safari/537.36"
```

### 3-3. runDetailed() — snsz.kr 초기 랜딩 추가

```kotlin
// 루프 시작 시 1회
browserSession.loadAndWait("https://snsz.kr", 5_000)
delay(1_000)
// 이후 1차 검색 진행
```

### 3-4. 측정 기준 (50회)

| 지표 | 목표 |
|------|------|
| adcr 경유율 | 60% 이상 |
| searchGate 경유율 | 50% 이상 |
| 상세페이지 도달율 | 40% 이상 |
| 전체 체인 완성율 | 30% 이상 |

현재 기준(0506 실측): 전체 체인 완성 **0%**.

---

## 4. 체크리스트

- [ ] `buildNaverSearchUrl()` → `buildFirstSearchUrl()` / `buildSecondSearchUrl()` 분리
- [ ] WebView UA SamsungBrowser/29.0으로 설정
- [ ] `runDetailed()` 앞에 snsz.kr 랜딩 추가
- [ ] APK 빌드 후 기기 설치
- [ ] 50회 실행, flow.json 수집
- [ ] 체인 완성율 집계
