-- Draft schema for Android Samsung account leasing.
-- Keep encryption/decryption server-side. Do not expose raw credentials in logs.

create extension if not exists pgcrypto;

create table if not exists android_devices (
  device_name text primary key,
  group_id text not null,
  role text not null,
  display_name text,
  app_version text,
  state text not null default 'IDLE',
  task_count integer not null default 0,
  current_ip text,
  last_error text,
  last_seen_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint android_devices_role_check check (role in ('boss', 'soldier')),
  constraint android_devices_state_check check (
    state in ('IDLE', 'WAITING_TASK', 'WAITING_LOGIN', 'RUNNING_TASK', 'ROTATING', 'PAUSED', 'ERROR')
  )
);

create table if not exists naver_accounts (
  id uuid primary key default gen_random_uuid(),
  alias text not null unique,
  encrypted_login_id text not null,
  encrypted_password text not null,
  status text not null default 'available',
  assigned_device_name text,
  group_id text,
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

alter table if exists naver_accounts
  add column if not exists group_id text;

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

create table if not exists device_cookies (
  device_name text not null,
  account_id uuid references naver_accounts(id),
  account_alias text not null default '',
  cookies text not null,
  updated_at timestamptz not null default now(),
  primary key (device_name, account_alias)
);

alter table device_cookies
  drop constraint if exists device_cookies_pkey;

update device_cookies
  set account_alias = ''
  where account_alias is null;

alter table device_cookies
  alter column account_alias set default '',
  alter column account_alias set not null,
  add primary key (device_name, account_alias);

create table if not exists device_group (
  group_id text primary key,
  state text not null default 'READY',
  command_id text,
  rotate_owner text,
  completed_tasks_since_rotation integer not null default 0,
  drain_started_at timestamptz,
  rotation_started_at timestamptz,
  last_rotation_at timestamptz,
  last_error text,
  updated_at timestamptz not null default now(),
  constraint device_group_state_check check (
    state in ('READY', 'DRAINING', 'ROTATING', 'ROTATION_FAILED', 'PAUSED', 'STOPPED')
  )
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

create table if not exists app_releases (
  id uuid primary key default gen_random_uuid(),
  version_code integer not null unique,
  version_name text not null,
  apk_url text not null,
  sha256 text,
  enabled boolean not null default true,
  release_notes text,
  created_at timestamptz not null default now()
);

create index if not exists idx_android_devices_group
  on android_devices(group_id, role, last_seen_at);

create index if not exists idx_naver_accounts_status
  on naver_accounts(status, cooldown_until);

create index if not exists idx_naver_accounts_assignment
  on naver_accounts(assigned_device_name, group_id, status);

create index if not exists idx_android_account_leases_active
  on android_account_leases(device_name, status, expires_at);

create index if not exists idx_device_cookies_account
  on device_cookies(account_id, updated_at);

create index if not exists idx_device_cookies_device_updated
  on device_cookies(device_name, updated_at desc);

create index if not exists idx_naver_strategy_products_status
  on naver_strategy_products(strategy, status, cooldown_until);

create index if not exists idx_android_task_leases_active
  on android_task_leases(device_name, status, strategy);

create index if not exists idx_app_releases_enabled
  on app_releases(enabled, version_code desc);
