-- Supabase RPC contract for Android Naver task claim/report.
--
-- This is documentation SQL for the production contract. Adjust table names
-- and column types to match the deployed Sellermate schema before running.
-- Do not use Supabase for per-heartbeat, per-command, or per-recent-lease
-- writes. The bridge/Vercel memory snapshot is the best-effort realtime cache.

-- Option A: add narrow queue-control columns to the existing queue.
alter table if exists public.sellermate_traffic_navershopping
  add column if not exists claim_state text not null default 'queued',
  add column if not exists strategy text not null default 'A',
  add column if not exists strategy_group text,
  add column if not exists claimed_by text,
  add column if not exists lease_id uuid,
  add column if not exists claimed_at timestamptz,
  add column if not exists claim_expires_at timestamptz,
  add column if not exists completed_at timestamptz,
  add column if not exists attempt_count integer not null default 0,
  add column if not exists max_attempts integer not null default 3,
  add column if not exists last_error text;

alter table if exists public.sellermate_traffic_navershopping
  add constraint sellermate_traffic_navershopping_claim_state_chk
  check (claim_state in ('queued', 'claimed', 'completed', 'expired'));

create index if not exists idx_android_naver_queue_claimable
  on public.sellermate_traffic_navershopping
  (claim_state, claim_expires_at, attempt_count, id)
  where claim_state in ('queued', 'claimed');

-- Option B: if the source queue cannot be altered, use this narrow durable
-- claims table. This is queue control state, not a heartbeat/status table.
create table if not exists public.android_task_claims (
  id uuid primary key default gen_random_uuid(),
  task_id text not null,
  traffic_id text not null,
  slot_id bigint not null,
  strategy text not null default 'A',
  device_name text not null,
  lease_id uuid not null default gen_random_uuid(),
  state text not null default 'claimed'
    check (state in ('queued', 'claimed', 'completed', 'expired')),
  claimed_at timestamptz not null default now(),
  claim_expires_at timestamptz not null,
  completed_at timestamptz,
  attempt_count integer not null default 1,
  max_attempts integer not null default 3,
  success boolean,
  link_url text,
  source text,
  browser_layer text,
  idempotency_key text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table if exists public.android_task_claims
  add column if not exists browser_layer text;

create unique index if not exists ux_android_task_claims_lease
  on public.android_task_claims (lease_id);

create unique index if not exists ux_android_task_claims_completed_task_device
  on public.android_task_claims (task_id, device_name)
  where state = 'completed';

create unique index if not exists ux_android_task_claims_idempotency_key
  on public.android_task_claims (idempotency_key)
  where idempotency_key is not null;

create index if not exists idx_android_task_claims_requeue
  on public.android_task_claims (state, claim_expires_at, attempt_count);

-- Atomically claim one durable task. A deployed implementation should use
-- SELECT ... FOR UPDATE SKIP LOCKED inside the function body so concurrent
-- devices cannot claim the same queued row.
create or replace function public.claim_android_naver_task(
  p_device_name text,
  p_strategy text,
  p_claim_ttl_seconds integer default 60
) returns jsonb
language plpgsql
security definer
as $$
declare
  v_task record;
  v_lease_id uuid := gen_random_uuid();
begin
  with candidate as (
    select q.id
    from public.sellermate_traffic_navershopping q
    where q.claim_state = 'queued'
      and q.attempt_count < q.max_attempts
      and not exists (
        select 1
        from public.android_keyword_blacklist b
        where b.slot_id = q.slot_id
          and b.strategy = coalesce(nullif(q.strategy_group, ''), nullif(q.strategy, ''), p_strategy, 'G')
          and b.query_phrase = q.keyword_name
          and b.cooldown_until > now()
      )
    order by q.id
    for update skip locked
    limit 1
  )
  update public.sellermate_traffic_navershopping q
  set claim_state = 'claimed',
      strategy = p_strategy,
      claimed_by = p_device_name,
      lease_id = v_lease_id,
      claimed_at = now(),
      claim_expires_at = now() + make_interval(secs => p_claim_ttl_seconds),
      attempt_count = q.attempt_count + 1
  from candidate
  where q.id = candidate.id
  returning q.id as task_id,
            q.id as traffic_id,
            q.slot_id,
            q.keyword,
            q.link_url,
            q.lease_id,
            q.claimed_at,
            q.claim_expires_at
    into v_task;

  if not found then
    return null;
  end if;

  return jsonb_build_object(
    'task_id', v_task.task_id::text,
    'lease_id', v_task.lease_id::text,
    'traffic_id', v_task.traffic_id::text,
    'slot_id', v_task.slot_id,
    'keyword', v_task.keyword,
    'keyword_name', (
      select s.keyword_name
      from public.sellermate_slot_naver s
      where s.id = v_task.slot_id
    ),
    'link_url', v_task.link_url,
    'mid', (
      select s.mid
      from public.sellermate_slot_naver s
      where s.id = v_task.slot_id
    ),
    'claimed_at', v_task.claimed_at,
    'expires_at', v_task.claim_expires_at
  );
end;
$$;

-- Idempotently report a claimed task. The same task/device/lease or the same
-- idempotency_key may be submitted repeatedly; counters must apply once.
-- Successful reports intentionally do not append history rows; the slot
-- counters are the durable authority. Failure reports keep one diagnostic
-- history row per accepted claim.
create or replace function public.report_android_naver_task(
  p_task_id text,
  p_lease_id text,
  p_device_name text,
  p_success boolean,
  p_link_url text,
  p_source text,
  p_idempotency_key text default null,
  p_browser_layer text default null
) returns jsonb
language plpgsql
security definer
as $$
declare
  v_task record;
  v_existing record;
begin
  select *
    into v_existing
  from public.android_task_claims
  where (p_idempotency_key is not null and idempotency_key = p_idempotency_key)
     or (task_id = p_task_id and device_name = p_device_name and state = 'completed')
  order by completed_at nulls last
  limit 1;

  if found then
    return jsonb_build_object(
      'accepted', true,
      'duplicate', true,
      'task_id', v_existing.task_id,
      'lease_id', v_existing.lease_id,
      'success', v_existing.success
    );
  end if;

  select *
    into v_task
  from public.sellermate_traffic_navershopping
  where id::text = p_task_id
    and lease_id::text = p_lease_id
    and claimed_by = p_device_name
    and claim_state = 'claimed'
  for update;

  if not found then
    return jsonb_build_object('accepted', false, 'error', 'claim_not_found');
  end if;

  update public.sellermate_slot_naver
  set success_count = success_count + case when p_success then 1 else 0 end,
      fail_count = fail_count + case when p_success then 0 else 1 end
  where id = v_task.slot_id;

  if not p_success then
    -- Best-effort failure history insert. Counter update is the durable authority.
    begin
      insert into public.slot_rank_naverapp_history (
        slot_id,
        link_url,
        source,
        success,
        created_at
      ) values (
        v_task.slot_id,
        p_link_url,
        p_source,
        p_success,
        now()
      );
    exception when others then
      null;
    end;
  end if;

  insert into public.android_task_claims (
    task_id,
    traffic_id,
    slot_id,
    strategy,
    device_name,
    lease_id,
    state,
    claim_expires_at,
    completed_at,
    success,
    link_url,
    source,
    browser_layer,
    idempotency_key
  ) values (
    p_task_id,
    p_task_id,
    v_task.slot_id,
    coalesce(v_task.strategy, 'A'),
    p_device_name,
    p_lease_id::uuid,
    'completed',
    v_task.claim_expires_at,
    now(),
    p_success,
    p_link_url,
    p_source,
    p_browser_layer,
    p_idempotency_key
  );

  update public.sellermate_traffic_navershopping
  set claim_state = 'completed',
      completed_at = now()
  where id = v_task.id;

  return jsonb_build_object(
    'accepted', true,
    'duplicate', false,
    'task_id', p_task_id,
    'lease_id', p_lease_id,
    'success', p_success
  );
exception
  when unique_violation then
    return jsonb_build_object(
      'accepted', true,
      'duplicate', true,
      'task_id', p_task_id,
      'lease_id', p_lease_id
    );
end;
$$;

-- Requeue or expire timed-out claims. Run from a scheduled job before/alongside
-- claim_android_naver_task. The attempt limit prevents infinite retry loops.
create or replace function public.requeue_expired_android_naver_tasks()
returns integer
language plpgsql
security definer
as $$
declare
  v_count integer;
begin
  update public.sellermate_traffic_navershopping
  set claim_state = case
        when attempt_count >= max_attempts then 'expired'
        else 'queued'
      end,
      claimed_by = null,
      lease_id = null,
      claimed_at = null,
      claim_expires_at = null,
      last_error = 'claim TTL expired'
  where claim_state = 'claimed'
    and claim_expires_at < now();

  get diagnostics v_count = row_count;
  return v_count;
end;
$$;
