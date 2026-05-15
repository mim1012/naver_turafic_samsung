# 2026-05-11 A/B/C 전략 비교 테스트

## 테스트 목적
AI Reward 방식 근접도에 따른 MID 클릭 성공률 비교

## 변형 정의

| 변형 | 검색창 탭 | 붙여넣기 입력 | 검색 실행 방식 | AI Reward 근접도 |
|------|-----------|--------------|----------------|-----------------|
| **A** | ✅ | ❌ | URL 직접 로드 | 낮음 (기준선) |
| **B** | ✅ | ✅ commitText | 검색버튼 탭 | 높음 (AI Reward 방식) |
| **C** | ✅ | ✅ commitText | 검색버튼 탭 | 높음 (B와 동일 전략) |

- A vs B: 같은 상품(킨맥 파우치), 전략 차이만 → 순수 전략 효과 측정
- B vs C: 같은 전략, 다른 키워드/상품 → 키워드/순위 효과 측정

## 상품 배정

| 변형 | 상품 | MID | keyword | 현재순위 |
|------|------|-----|---------|----------|
| **A** | 킨맥 360쉴드 노트북 파우치 S, 투톤네이비 | `89408249403` | 노트북 파우치 | 299위 |
| **B** | 프리미엄 가죽 노트북 가방 파우치 (맥북/그램/갤럭시북4) | `82263426809` | 노트북 파우치 | 287위 |
| **C** | 남성 자외선 차단 여름 선글라스 보잉 블랙 미러 | `90991583792` | 여름 선글라스 | 349위 |

## ADB 실행 명령

### A — 킨맥 노트북 파우치
```bash
adb -s ce12160c9966340705 shell 'am start -n com.navertraffic.samsung/.ui.MainActivity \
  --ez autoRun true --es deviceName "z1" --ei loopCount 50 --ei rotateEvery 5 \
  --es strategyVariant "A" \
  --es mid "89408249403" \
  --es keyword "노트북 파우치" \
  --es secondKeyword "킨맥 360쉴드 노트북 파우치 가방 S 13 투톤네이비" \
  --es productTitle "킨맥파우치"'
```

### B — 프리미엄 가죽 노트북 파우치
```bash
adb -s ce12160c9966340705 shell 'am start -n com.navertraffic.samsung/.ui.MainActivity \
  --ez autoRun true --es deviceName "z1" --ei loopCount 50 --ei rotateEvery 5 \
  --es strategyVariant "B" \
  --es mid "82263426809" \
  --es keyword "노트북 파우치" \
  --es secondKeyword "프리미엄 가죽 노트북 가방 파우치 맥북 그램 삼성 갤럭시북4" \
  --es productTitle "가죽파우치"'
```

### C — 여름 선글라스
```bash
adb -s ce12160c9966340705 shell 'am start -n com.navertraffic.samsung/.ui.MainActivity \
  --ez autoRun true --es deviceName "z1" --ei loopCount 50 --ei rotateEvery 5 \
  --es strategyVariant "C" \
  --es mid "90991583792" \
  --es keyword "여름 선글라스" \
  --es secondKeyword "남성 자외선 차단 여름 선글라스 보잉 블랙 미러" \
  --es productTitle "여름선글라스"'
```

## 결과 기록

| 변형 | 루프 | 성공 | 성공률 | MID 발견률 | 보호신호 | 실행시간 |
|------|------|------|--------|-----------|----------|----------|
| A | /50 | | | | | |
| B | /50 | | | | | |
| C | /50 | | | | | |
