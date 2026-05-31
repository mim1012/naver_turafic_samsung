-- Android Naver keyword failure blacklist support.
-- Purpose: reduce repeated MID-not-found failures by cooling down only the
-- exact query phrase that failed for a slot/MID. Do not blacklist rate limits
-- or protection failures here; those are device/IP/account throttling signals.

create table if not exists public.android_keyword_failures (
  id uuid primary key default gen_random_uuid(),
  task_id text,
  lease_id text,
  slot_id bigint not null,
  strategy text not null default 'G',
  device_name text not null,
  failure_reason text not null,
  query_phrase text not null,
  message text,
  link_url text,
  final_url text,
  mid_found boolean,
  detail_status text,
  browser_layer text,
  idempotency_key text,
  created_at timestamptz not null default now()
);

create table if not exists public.android_keyword_blacklist (
  id uuid primary key default gen_random_uuid(),
  slot_id bigint not null,
  strategy text not null default 'G',
  query_phrase text not null,
  failure_reason text not null,
  fail_count integer not null default 1,
  first_failed_at timestamptz not null default now(),
  last_failed_at timestamptz not null default now(),
  cooldown_until timestamptz not null,
  source text not null default 'android_keyword_failure',
  browser_layer text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table if exists public.android_keyword_failures
  add column if not exists browser_layer text;

alter table if exists public.android_keyword_blacklist
  add column if not exists browser_layer text;

create unique index if not exists ux_android_keyword_failures_idempotency
  on public.android_keyword_failures (idempotency_key)
  where idempotency_key is not null;

create index if not exists idx_android_keyword_failures_slot_phrase_recent
  on public.android_keyword_failures (slot_id, query_phrase, created_at desc);

create unique index if not exists ux_android_keyword_blacklist_slot_strategy_phrase
  on public.android_keyword_blacklist (slot_id, strategy, query_phrase);

create index if not exists idx_android_keyword_blacklist_active
  on public.android_keyword_blacklist (slot_id, strategy, cooldown_until);

create or replace function public.record_android_keyword_failure(
  p_task_id text,
  p_lease_id text,
  p_device_name text,
  p_slot_id bigint,
  p_strategy text,
  p_failure_reason text,
  p_query_phrase text,
  p_message text default null,
  p_link_url text default null,
  p_final_url text default null,
  p_mid_found boolean default null,
  p_detail_status text default null,
  p_idempotency_key text default null,
  p_browser_layer text default null
) returns jsonb
language plpgsql
security definer
as $$
declare
  v_phrase text := nullif(trim(p_query_phrase), '');
  v_reason text := nullif(trim(p_failure_reason), '');
  v_strategy text := coalesce(nullif(trim(p_strategy), ''), 'G');
  v_count integer;
  v_threshold integer;
  v_cooldown interval;
begin
  if p_slot_id is null or v_phrase is null or v_reason is null then
    return jsonb_build_object('accepted', false, 'error', 'missing_required_fields');
  end if;

  -- Only repeated MID-not-found failures blacklist a phrase. 429/protection/detail
  -- failures are intentionally excluded from this RPC policy.
  if v_reason not in (
    'mid_product_not_found_after_exploration',
    'mid_product_not_found_after_timeout'
  ) then
    return jsonb_build_object('accepted', true, 'blacklisted', false, 'ignored_reason', v_reason);
  end if;

  insert into public.android_keyword_failures (
    task_id, lease_id, slot_id, strategy, device_name, failure_reason,
    query_phrase, message, link_url, final_url, mid_found, detail_status,
    browser_layer, idempotency_key
  ) values (
    p_task_id, p_lease_id, p_slot_id, v_strategy,
    coalesce(nullif(trim(p_device_name), ''), 'unknown'), v_reason,
    v_phrase, p_message, p_link_url, p_final_url, p_mid_found, p_detail_status,
    p_browser_layer, p_idempotency_key
  )
  on conflict do nothing;

  v_threshold := case
    when v_reason = 'mid_product_not_found_after_timeout' then 2
    else 3
  end;
  v_cooldown := case
    when v_reason = 'mid_product_not_found_after_timeout' then interval '6 hours'
    else interval '24 hours'
  end;

  select count(*)
    into v_count
  from public.android_keyword_failures
  where slot_id = p_slot_id
    and strategy = v_strategy
    and query_phrase = v_phrase
    and failure_reason = v_reason
    and created_at >= now() - interval '24 hours';

  if v_count < v_threshold then
    return jsonb_build_object('accepted', true, 'blacklisted', false, 'fail_count', v_count, 'threshold', v_threshold);
  end if;

  insert into public.android_keyword_blacklist (
    slot_id, strategy, query_phrase, failure_reason, fail_count,
    cooldown_until, browser_layer
  ) values (
    p_slot_id,
    v_strategy,
    v_phrase,
    v_reason,
    v_count,
    now() + v_cooldown,
    p_browser_layer
  )
  on conflict (slot_id, strategy, query_phrase)
  do update set
    failure_reason = excluded.failure_reason,
    fail_count = greatest(public.android_keyword_blacklist.fail_count + 1, excluded.fail_count),
    last_failed_at = now(),
    cooldown_until = greatest(public.android_keyword_blacklist.cooldown_until, excluded.cooldown_until),
    browser_layer = excluded.browser_layer,
    updated_at = now();

  return jsonb_build_object('accepted', true, 'blacklisted', true, 'fail_count', v_count, 'cooldown_until', now() + v_cooldown);
end;
$$;

-- Claim-query helper snippet for production claim_android_naver_task:
-- Add this predicate to candidate queue selection when the selected query phrase
-- is q.keyword_name / task keywordName:
--
-- and not exists (
--   select 1
--   from public.android_keyword_blacklist b
--   where b.slot_id = q.slot_id
--     and b.strategy = coalesce(q.strategy_group, q.strategy, p_strategy, 'G')
--     and b.query_phrase = q.keyword_name
--     and b.cooldown_until > now()
-- )
