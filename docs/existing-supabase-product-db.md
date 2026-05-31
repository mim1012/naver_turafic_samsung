# Existing Supabase Product DB Adapter

The existing Zero product queue should remain the source of truth for products.
The Android Samsung project should not create a second production product table
unless there is a clear migration reason.

## Existing Production Flow

Zero server already uses these production tables:

```text
sellermate_traffic_navershopping
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

For the Android bridge, keep these tables as the durable boundary. Recent
heartbeat state, per-command status, active task leases, and recent lease lists
must not be written to Supabase on every event. They belong in the bridge memory
snapshot only.

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
sellermate_traffic_navershopping.id       -> taskLeaseId = sb_{id}
sellermate_traffic_navershopping.keyword  -> keyword
sellermate_traffic_navershopping.link_url -> linkUrl
sellermate_traffic_navershopping.slot_id  -> sellermate_slot_naver.id
sellermate_slot_naver.keyword_name -> secondKeyword, productTitle
sellermate_slot_naver.mid          -> mid
```

On report:

```text
success -> sellermate_slot_naver.success_count + 1
failed  -> sellermate_slot_naver.fail_count + 1
both    -> best-effort insert into slot_rank_naverapp_history
```

Production Supabase mode should use the RPC contract in
`docs/supabase-rpc-task-queue.sql` instead of an app-level
`GET oldest row -> DELETE row` claim or `GET count -> PATCH count + 1` report.
The claim RPC must set `claim_expires_at`, enforce attempt limits, and provide a
requeue/expire path for crashed devices. The report RPC must be idempotent using
either an explicit idempotency key or a unique task/device/lease receipt so
Android retries do not double-increment counters or duplicate history.

## Runtime State Ownership

The admin snapshot is a best-effort realtime cache built from bridge/Vercel
memory. It is useful for current operator visibility, but it is not durable
Vercel state and is not shared reliably across instances. Vercel cold start,
deployment restart, scale-out, and multi-instance routing can lose or split this
memory.

Do not add per-heartbeat, per-command, or per-recent-lease Supabase writes.
Allowed durable writes are task claim/report results, `device_cookies`, account
lease/report tables when the production Vercel API is enabled, app release
metadata, and the existing product queue/result tables listed above.

`android_task_leases` from `docs/server-schema.sql` is not a production hot
lease/status table. If it is used later, treat it as append-only audit with a
retention policy, not as the source for realtime command or heartbeat state.

## History Retention

`slot_rank_naverapp_history` and future append-only rank, log, or device event
tables must have retention before production rollout. Prefer monthly or daily
time partitioning once row volume is high, keep only an operator-defined hot
window such as 7-30 days online, archive older partitions to cheaper storage or
summary tables, and delete or detach old partitions only after archive
verification.

Unbounded append-only history is the same failure class as prior log-table Disk
I/O exhaustion incidents. Any new history/log/device event table must include
its retention, partition, archive, and delete policy in the same change that
introduces the table. See `docs/supabase-retention-policy.sql` for an
operational template.

## Temporary Local Tables

`docs/server-schema.sql` contains `naver_strategy_products` and
`android_task_leases` as a standalone fallback schema. For production, prefer
the existing Zero/Supabase tables above.
