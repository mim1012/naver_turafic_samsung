# Existing Supabase Product DB Adapter

The existing Zero product queue should remain the source of truth for products.
The Android Samsung project should not create a second production product table
unless there is a clear migration reason.

## Existing Production Flow

Zero server already uses these production tables:

```text
traffic-navershopping-app
sellermate_slot_naver
landing_redirects
slot_rank_naverapp_history
```

Observed flow from `D:\Project\zero\apps\server\app\api\v1\traffic.py`:

```text
POST /zero/api/v1/traffic/claim-work
  -> SELECT oldest row from traffic-navershopping-app
  -> read keyword/link_url/slot_id
  -> SELECT mid, keyword_name from sellermate_slot_naver by slot_id
  -> DELETE consumed traffic-navershopping-app row
  -> create/update landing_redirects
  -> return product work

POST /zero/api/v1/traffic/complete
  -> increment slot_naverapp.success_count
  -> insert slot_rank_naverapp_history

POST /zero/api/v1/traffic/fail
  -> increment slot_naverapp.fail_count
  -> insert slot_rank_naverapp_history
```

## Mapping To Android Strategy A

The bridge server maps Zero fields into Android fields:

```text
traffic_id       -> taskLeaseId = zero_{traffic_id}
short_keyword    -> keyword
keyword_name      -> secondKeyword
target_url       -> linkUrl
nv_mid           -> mid
keyword_name      -> productTitle
```

For Strategy A this means:

```text
1차 검색어 = short_keyword
2차 검색어 = keyword_name
진입 URL = target_url
MID = nv_mid
```

## Runtime Configuration

Set this environment variable on the bridge server:

```powershell
$env:ZERO_TRAFFIC_API_URL="http://127.0.0.1:8000/zero/api/v1/traffic"
node server\dev-server.js
```

When set, `/android/tasks/lease` calls Zero `/claim-work` first. If Zero returns
no task, the bridge falls back to local `products.local.json` only for testing.

The bridge can also read the Supabase REST API directly:

```powershell
$env:SUPABASE_REST_URL="https://cwsdvgkjptuvbdtxcejt.supabase.co/rest/v1"
$env:SUPABASE_ANON_KEY="..."
$env:SUPABASE_SLOT_TABLE="sellermate_slot_naver"
node server\dev-server.js
```

Direct Supabase mapping:

```text
traffic-navershopping-app.id       -> taskLeaseId = sb_{id}
traffic-navershopping-app.keyword  -> keyword
traffic-navershopping-app.link_url -> linkUrl
traffic-navershopping-app.slot_id  -> sellermate_slot_naver.id
sellermate_slot_naver.keyword_name -> secondKeyword, productTitle
sellermate_slot_naver.mid          -> mid
```

On report:

```text
success -> sellermate_slot_naver.success_count + 1
failed  -> sellermate_slot_naver.fail_count + 1
both    -> best-effort insert into slot_rank_naverapp_history
```

## Temporary Local Tables

`docs/server-schema.sql` contains `naver_strategy_products` and
`android_task_leases` as a standalone fallback schema. For production, prefer
the existing Zero/Supabase tables above.
