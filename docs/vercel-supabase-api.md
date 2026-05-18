# Vercel + Supabase API

Production uses the existing Vercel website as the only public server origin.

```text
Browser admin page -> Vercel /admin and /admin/api/*
Android APK        -> Vercel /android/*
Vercel functions   -> Supabase with server-only credentials
```

`server/dev-server.js` stays as a local contract server. It is not the production
backend when the deployed shape is Vercel + Supabase.

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
