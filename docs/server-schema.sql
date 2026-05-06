-- Draft schema for Android Samsung account leasing.
-- Keep encryption/decryption server-side. Do not expose raw credentials in logs.

create table if not exists naver_accounts (
  id uuid primary key default gen_random_uuid(),
  alias text not null unique,
  encrypted_login_id text not null,
  encrypted_password text not null,
  status text not null default 'available',
  assigned_device_name text,
  last_used_at timestamptz,
  last_login_at timestamptz,
  last_success_at timestamptz,
  fail_count integer not null default 0,
  protection_detected_at timestamptz,
  cooldown_until timestamptz,
  memo text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint naver_accounts_status_check check (
    status in (
      'available',
      'leased',
      'cooldown',
      'protected',
      'invalid',
      'manual_check_required',
      'disabled'
    )
  )
);

create table if not exists android_account_leases (
  id uuid primary key default gen_random_uuid(),
  account_id uuid not null references naver_accounts(id),
  device_name text not null,
  role text not null,
  strategy text not null,
  status text not null default 'active',
  leased_at timestamptz not null default now(),
  expires_at timestamptz not null,
  released_at timestamptz,
  release_reason text,
  created_at timestamptz not null default now(),
  constraint android_account_leases_role_check check (role in ('boss', 'soldier')),
  constraint android_account_leases_status_check check (status in ('active', 'released', 'expired', 'reported'))
);

create table if not exists android_account_reports (
  id uuid primary key default gen_random_uuid(),
  lease_id uuid references android_account_leases(id),
  account_id uuid references naver_accounts(id),
  device_name text not null,
  result text not null,
  signals jsonb not null default '[]'::jsonb,
  last_url text,
  message text,
  created_at timestamptz not null default now()
);

create table if not exists naver_strategy_products (
  id uuid primary key default gen_random_uuid(),
  strategy text not null default 'A',
  keyword text not null,
  second_keyword text not null,
  link_url text not null,
  mid text,
  product_title text,
  status text not null default 'available',
  assigned_device_name text,
  success_count integer not null default 0,
  fail_count integer not null default 0,
  last_used_at timestamptz,
  cooldown_until timestamptz,
  memo text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint naver_strategy_products_status_check check (
    status in ('available', 'leased', 'cooldown', 'disabled')
  )
);

create table if not exists android_task_leases (
  id uuid primary key default gen_random_uuid(),
  product_id uuid not null references naver_strategy_products(id),
  device_name text not null,
  role text not null,
  strategy text not null,
  status text not null default 'active',
  leased_at timestamptz not null default now(),
  reported_at timestamptz,
  result text,
  message text,
  created_at timestamptz not null default now(),
  constraint android_task_leases_role_check check (role in ('boss', 'soldier')),
  constraint android_task_leases_status_check check (status in ('active', 'reported', 'expired'))
);

create index if not exists idx_naver_accounts_status
  on naver_accounts(status, cooldown_until);

create index if not exists idx_android_account_leases_active
  on android_account_leases(device_name, status, expires_at);

create index if not exists idx_naver_strategy_products_status
  on naver_strategy_products(strategy, status, cooldown_until);

create index if not exists idx_android_task_leases_active
  on android_task_leases(device_name, status, strategy);
