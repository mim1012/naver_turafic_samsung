# Vercel + Supabase API

Production uses the existing Vercel website as the only public server origin.

```text
Browser admin page -> Vercel /admin and /admin/api/*
Android APK        -> Vercel /android/*
Vercel functions   -> Supabase with server-only credentials
```

`server/dev-server.js` stays as a local contract server. It is not the production
backend when the deployed shape is Vercel + Supabase.

Vercel function memory is only a best-effort realtime cache for admin snapshots.
It is not durable shared state: cold start, deployment restart, scale-out, and
multi-instance routing can lose or split recent heartbeat, command, and lease
views. Do not write per-heartbeat, per-command, or per-recent-lease status to
Supabase to compensate for that limitation.

## Vercel Environment Variables

Set these on the existing Vercel project:

```text
SUPABASE_URL=https://<project>.supabase.co
SUPABASE_SERVICE_ROLE_KEY=<server-only-service-role-key>
ACCOUNT_ENCRYPTION_KEY=<server-only-secret>
ANDROID_DEVICE_API_TOKEN=<optional-device-token>
```

Do not use `NEXT_PUBLIC_` for any of these. Browser code and APK code must never receive
the Supabase service-role key or account encryption key.

## Route Ownership

Recommended Vercel routes:

```text
/admin
/admin/api/snapshot
/admin/api/accounts
/admin/api/groups/state
/android/accounts/lease
/android/accounts/report
/android/accounts/release
/android/tasks/lease
/android/tasks/report
/android/heartbeat
/android/group/rotation-report
/android/cookies/save
/android/cookies/load
/android/app-release/latest
```

`/admin/api/*` validates the existing website's administrator session on every request.
Do not rely only on middleware/proxy-level auth for admin actions.

`/android/*` should require a device token or a stronger per-device registration scheme
before opening the system beyond local testing. The Android client already supports an
optional bearer token through the `apiKey` launch extra/config value.

## Supabase Data Model

Use `docs/server-schema.sql` as the contract. The important tables are:

```text
naver_accounts            account pool, optional assigned_device_name and group_id
android_account_leases    active/released account leases per device
android_account_reports   protection/failure reports
device_cookies            reusable Naver session cookies per device/account
device_group              boss/soldier rotation state
naver_strategy_products   task queue when not using existing Zero tables
android_task_leases       task lease/report audit
app_releases              APK update metadata
```

Production durable writes are limited to task claim/report results, reusable
`device_cookies`, account lease/report rows when those routes are enabled, app
release metadata, and the existing product tables:
`sellermate_traffic_navershopping`, `sellermate_slot_naver`, and
`slot_rank_naverapp_history`. `android_task_leases` is optional append-only
audit with retention, not a hot realtime lease table.

`device_group` is coarse group-control state for compatibility and fallback
flows. It is not a command event log, heartbeat log, or recent-lease store.

## Runtime Snapshot Ownership

`/admin/api/snapshot` should read current device/group/lease display state from
the Vercel/bridge memory snapshot. Treat that snapshot as a best-effort
realtime cache only:

```text
heartbeat status       -> memory only, no per-heartbeat Supabase write
recent command status  -> memory only, no per-command Supabase write
active task leases     -> memory only for display/correlation
recent lease list      -> bounded memory ring, no per-recent-lease Supabase write
```

Durable correctness belongs to Supabase RPCs and product/account/cookie/release
tables, not to the realtime snapshot. If the dashboard needs faster perceived
updates, prefer SSE/WebSocket or diff responses before increasing polling. Do
not use Supabase as a high-frequency status store.

## Task Queue RPC Contract

Production task claim/report should call Supabase RPC functions through Vercel
server-only credentials. See `docs/supabase-rpc-task-queue.sql` for the SQL
contract.

```text
claim_android_naver_task(
  p_device_name text,
  p_strategy text,
  p_claim_ttl_seconds integer default 60
)

returns task_id, lease_id, traffic_id, slot_id, keyword, keyword_name,
        link_url, mid, claimed_at, expires_at
```

The claim function must atomically transition one queued task to `claimed` using
a transaction-safe pattern such as `SELECT ... FOR UPDATE SKIP LOCKED`. It must
set `claim_expires_at`, increment `attempt_count`, and refuse tasks over the
attempt limit. Durable states are:

```text
queued     ready to claim
claimed    assigned and not past claim_expires_at
completed  final success/failure accepted
expired    attempt limit reached or explicitly expired
```

```text
report_android_naver_task(
  p_task_id text,
  p_lease_id text,
  p_device_name text,
  p_success boolean,
  p_link_url text,
  p_source text,
  p_idempotency_key text default null
)
```

The report function must be idempotent. A duplicate report with the same
idempotency key, or the same unique task/device/lease receipt, returns the
already accepted result and does not increment `sellermate_slot_naver` counters
or insert `slot_rank_naverapp_history` again. The RPC should update
`success_count` or `fail_count` atomically and treat the history insert as
best-effort evidence, not the authoritative counter.

If the existing traffic queue can be altered, add narrow claim columns:
`claim_state`, `claimed_by`, `lease_id`, `claimed_at`, `claim_expires_at`,
`completed_at`, `attempt_count`, and `max_attempts`. If it cannot be altered,
use a narrow `android_task_claims` table with unique constraints on
`lease_id`, completed `(task_id, device_name)`, and non-null `idempotency_key`.
This table is durable queue control state, not heartbeat or dashboard state.

Run a scheduled `requeue_expired_android_naver_tasks` job/RPC so `claimed` rows
past `claim_expires_at` return to `queued` or move to `expired` after the
attempt limit. This requeue path prevents Android crash, network loss, or worker
death from silently losing work.

Account selection order should be:

1. `assigned_device_name = deviceName`
2. `group_id = groupId`
3. unassigned global account

Cookie storage must use the same account identity returned from
`/android/accounts/lease`: `/android/cookies/save` and `/android/cookies/load`
include `accountAlias`, and the database key is `(device_name, account_alias)`.
This prevents a device from reusing a previous Naver account's cookies after the
administrator reassigns that device to another account.

That gives the requested "bot name identifies the device account" behavior while still
allowing a group pool fallback.

## Next.js Route Handler Pattern

Keep the Supabase client lazy so builds do not fail when environment variables are only
available at runtime:

```ts
// lib/android-admin/supabase.ts
import { createClient } from "@supabase/supabase-js";

let client: ReturnType<typeof createClient> | null = null;

export function getSupabaseAdmin() {
  if (!client) {
    client = createClient(
      process.env.SUPABASE_URL!,
      process.env.SUPABASE_SERVICE_ROLE_KEY!,
      { auth: { persistSession: false } },
    );
  }
  return client;
}
```

Example account lease handler:

```ts
// app/android/accounts/lease/route.ts
import { NextRequest } from "next/server";
import { getSupabaseAdmin } from "@/lib/android-admin/supabase";

export const runtime = "nodejs";

export async function POST(request: NextRequest) {
  const token = request.headers.get("authorization")?.replace(/^Bearer\s+/i, "");
  if (process.env.ANDROID_DEVICE_API_TOKEN && token !== process.env.ANDROID_DEVICE_API_TOKEN) {
    return Response.json({ error: "unauthorized" }, { status: 401 });
  }

  const body = await request.json();
  const deviceName = String(body.deviceName || "");
  const groupId = deviceName.includes("-") ? deviceName.split("-")[0] : deviceName;
  const supabase = getSupabaseAdmin();
  const columns = "id,alias,encrypted_login_id,encrypted_password,assigned_device_name,group_id";

  const exact = await supabase
    .from("naver_accounts")
    .select(columns)
    .eq("status", "available")
    .eq("assigned_device_name", deviceName)
    .limit(1)
    .maybeSingle();

  const groupPool = exact.data ? null : await supabase
    .from("naver_accounts")
    .select(columns)
    .eq("status", "available")
    .is("assigned_device_name", null)
    .eq("group_id", groupId)
    .limit(1)
    .maybeSingle();

  const globalPool = exact.data || groupPool?.data ? null : await supabase
    .from("naver_accounts")
    .select(columns)
    .eq("status", "available")
    .is("assigned_device_name", null)
    .is("group_id", null)
    .limit(1)
    .maybeSingle();

  const error = exact.error ?? groupPool?.error ?? globalPool?.error;
  if (error) return Response.json({ error: "account_query_failed" }, { status: 500 });

  const account = exact.data ?? groupPool?.data ?? globalPool?.data;

  if (!account) return Response.json({});

  // Decrypt on the server here. Never send encrypted fields directly.
  const loginId = decryptAccountSecret(account.encrypted_login_id);
  const password = decryptAccountSecret(account.encrypted_password);

  const { data: lease } = await supabase
    .from("android_account_leases")
    .insert({
      account_id: account.id,
      device_name: deviceName,
      role: body.role,
      strategy: body.strategy,
      expires_at: new Date(Date.now() + 30 * 60 * 1000).toISOString(),
    })
    .select("id,expires_at")
    .single();

  return Response.json({
    leaseId: lease?.id,
    accountAlias: account.alias,
    loginId,
    password,
    expiresAt: lease?.expires_at,
  });
}
```

`decryptAccountSecret` is intentionally omitted from the example because the encryption
format must match the ingestion path used by the admin account form.

## Android Setup

Use the Vercel origin as `serverUrl`:

```powershell
adb shell am start -n com.navertraffic.samsung/.ui.MainActivity `
  -e deviceName z1-1 `
  -e serverUrl https://your-site.vercel.app `
  -e apiKey <ANDROID_DEVICE_API_TOKEN> `
  -e autoRun true `
  -e loopCount 200
```

The same URL can also be entered once in the APK's server URL field. The app stores it
with the bot name.

Direct Android-to-Supabase mode is for local development or staged fallback only.
Production devices should use `serverUrl` so the APK never receives Supabase
service-role credentials and task claim/report semantics stay behind the Vercel
RPC boundary.

## Retention Policy

`slot_rank_naverapp_history` and future append-only rank, log, or device event
tables need a retention policy before production rollout. Keep a hot operational
window such as 7-30 days, partition high-volume history by time, archive older
rows or detached partitions to cheaper storage/summary tables, verify the
archive, then delete or detach old data. See
`docs/supabase-retention-policy.sql`.

Do not add new append-only history/log/device event tables without specifying
retention, partition, archive, and delete behavior in the same change.
