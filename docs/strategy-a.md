# Strategy A

Strategy A is the first Android/Samsung target.

## Flow

1. Initialize the internal Samsung-compatible WebView.
2. Navigate to mobile Naver integrated search with the first keyword.
3. Wait.
4. Navigate to mobile Naver integrated search with the second keyword.
5. Wait.
6. Enter the target product URL or bridge URL.
7. Stay on the product page.
8. Check visible text for protection/login/captcha signals.

## Task Fields

The Android task shape should start from the current PC runner fields:

```json
{
  "keyword": "삼성 갤럭시 이어폰",
  "secondKeyword": "프리미엄 블루투스 이어팟 차이팟 무선이어폰 충전케이스무료",
  "linkUrl": "https://smartstore.naver.com/sunsaem/products/83539482665",
  "mid": "83539482665",
  "productTitle": "프리미엄 블루투스 이어팟 차이팟 무선이어폰 충전케이스무료"
}
```

## Rule

For Strategy A, `secondKeyword` is required. If missing, skip the task as invalid.

## AI Reward Comparison

Use `docs/ai-reward-browser-comparison.md` when comparing this Android/Samsung line with the observed AI Reward Browser runtime.

The comparison baseline is this Android/Samsung folder, not the PC runner. For evidence-level comparison, run `tools/adb-mid-exposure-check.mjs` with `TRACE_REDIRECTS=1` so the Samsung flow records whether the click passes through Naver shopping bridge URLs such as `cr2.shopping.naver.com/adcr` and `cr3.shopping.naver.com/v2/bridge/searchGate`.
