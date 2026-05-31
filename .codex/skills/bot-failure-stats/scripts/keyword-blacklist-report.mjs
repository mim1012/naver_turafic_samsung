#!/usr/bin/env node
import fs from 'node:fs';

const args = process.argv.slice(2);
const limitArg = args.find((arg) => arg.startsWith('--limit='));
const limit = Math.max(1, Number(limitArg?.split('=')[1] || 20));
const envArgIndex = args.indexOf('--env');
const envPaths = envArgIndex >= 0 && args[envArgIndex + 1]
  ? [args[envArgIndex + 1]]
  : ['../adpang-production/.env.local', '.env.local'];

for (const path of envPaths) {
  if (!fs.existsSync(path)) continue;
  for (const line of fs.readFileSync(path, 'utf8').split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
    if (!match) continue;
    let value = match[2].trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    process.env[match[1]] = value;
  }
  break;
}

const base = (process.env.SUPABASE_REST_URL || `${process.env.SUPABASE_URL || process.env.NEXT_PUBLIC_SUPABASE_URL || ''}/rest/v1`).replace(/\/$/, '');
const key = process.env.SUPABASE_SERVICE_ROLE_KEY || process.env.SUPABASE_ANON_KEY || process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;
if (!base || !key) throw new Error('Missing Supabase env');

async function fetchRows(table, select, query, max = 50000, pageSize = 1000) {
  const out = [];
  for (let from = 0; from < max; from += pageSize) {
    const url = `${base}/${table}?select=${encodeURIComponent(select)}&${query}`;
    const res = await fetch(url, {
      headers: {
        apikey: key,
        Authorization: `Bearer ${key}`,
        Range: `${from}-${from + pageSize - 1}`,
      },
    });
    const text = await res.text();
    let data;
    try { data = JSON.parse(text); } catch { data = text; }
    if (!res.ok) throw new Error(`${res.status} ${table}: ${typeof data === 'string' ? data : JSON.stringify(data)}`);
    if (!Array.isArray(data) || data.length === 0) break;
    out.push(...data);
    if (data.length < pageSize) break;
  }
  return out;
}

function groupByPhrase(rows) {
  const map = new Map();
  for (const row of rows) {
    const key = row.query_phrase || '(empty)';
    if (!map.has(key)) {
      map.set(key, {
        queryPhrase: key,
        strategies: new Set(),
        maxFailCount: 0,
        rows: 0,
        latestCooldownUntil: row.cooldown_until,
        latestUpdatedAt: row.updated_at,
      });
    }
    const item = map.get(key);
    item.strategies.add(row.strategy || 'UNKNOWN');
    item.maxFailCount = Math.max(item.maxFailCount, Number(row.fail_count || 0));
    item.rows += 1;
    if (String(row.cooldown_until || '') > String(item.latestCooldownUntil || '')) item.latestCooldownUntil = row.cooldown_until;
    if (String(row.updated_at || '') > String(item.latestUpdatedAt || '')) item.latestUpdatedAt = row.updated_at;
  }
  return [...map.values()]
    .map((item) => ({
      ...item,
      strategies: [...item.strategies].sort().join(','),
    }))
    .sort((a, b) => b.maxFailCount - a.maxFailCount || a.queryPhrase.localeCompare(b.queryPhrase));
}

const now = new Date();
const since24 = new Date(now.getTime() - 24 * 3600 * 1000);
const activeQuery = `cooldown_until=gt.${encodeURIComponent(now.toISOString())}&order=updated_at.desc`;
const recentFailureQuery = `created_at=gte.${encodeURIComponent(since24.toISOString())}&order=created_at.desc`;

const [activeBlacklist, recentFailures] = await Promise.all([
  fetchRows(
    'android_keyword_blacklist',
    'slot_id,strategy,query_phrase,failure_reason,fail_count,cooldown_until,last_failed_at,updated_at',
    activeQuery,
  ),
  fetchRows(
    'android_keyword_failures',
    'slot_id,strategy,query_phrase,failure_reason,device_name,created_at',
    recentFailureQuery,
  ).catch(() => []),
]);

const activeByPhrase = groupByPhrase(activeBlacklist);
const recentByPhrase = groupByPhrase(recentFailures.map((row) => ({ ...row, fail_count: 1, cooldown_until: null, updated_at: row.created_at })));

console.log(JSON.stringify({
  generatedAt: now.toISOString(),
  activeBlacklist: {
    rows: activeBlacklist.length,
    uniquePhrases: activeByPhrase.length,
    top: activeByPhrase.slice(0, limit),
  },
  recentKeywordFailures24h: {
    rows: recentFailures.length,
    uniquePhrases: recentByPhrase.length,
    top: recentByPhrase.slice(0, limit),
  },
}, null, 2));
