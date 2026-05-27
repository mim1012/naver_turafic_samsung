const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");

const DEFAULT_POLICY = {
  rotateOwner: "z1",
  rotateEveryGroupTasks: Number(process.env.ROTATE_EVERY_GROUP_TASKS || 10),
  drainTimeoutSec: Number(process.env.DRAIN_TIMEOUT_SEC || 120),
  pauseSoldiersDuringRotation: true,
  pauseOnRotationFail: true,
};

const DEFAULT_SUPABASE_TRAFFIC_TABLE = "sellermate_traffic_navershopping";
const DEFAULT_SUPABASE_SLOT_TABLE = "sellermate_slot_naver";
const DEFAULT_STRATEGY = "G";
const DEFAULT_ANDROID_RELEASE_REPO = "mim1012/naver_turafic_samsung";
const TASK_LEASE_CAP = 500;
const ACCOUNT_REPORT_CAP = 100;
const DEVICE_OFFLINE_MS = 30_000;
const DEVICE_EVICT_MS = 10 * 60_000;
const CHALLENGE_CAP = 200;
const DEVICE_COMMAND_REPORT_CAP = 200;
const DEVICE_COMMAND_TTL_MS = 30 * 60 * 1000;
const DEVICE_COMMAND_TYPES = new Set([
  "START_BOT",
  "UPDATE_APP",
  "RESTART_APP",
  "REBOOT_DEVICE",
  "STOP",
  "PAUSE",
]);

function createState(options = {}) {
  return {
    startedAt: Date.now(),
    accountsFile: options.accountsFile || path.join(__dirname, "accounts.local.json"),
    accountCryptoKey: normalizeAccountCryptoKey(options.accountCryptoKey || process.env.ACCOUNT_ENCRYPTION_KEY || ""),
    accounts: options.accounts || loadAccounts(options.accountsFile),
    products: options.products || [],
    appReleases: options.appReleases || [],
    zeroTrafficApiUrl: options.zeroTrafficApiUrl || process.env.ZERO_TRAFFIC_API_URL || "",
    supabaseRestUrl: options.supabaseRestUrl || process.env.SUPABASE_REST_URL || "",
    supabaseAnonKey: options.supabaseAnonKey || options.supabaseKey ||
      process.env.SUPABASE_SERVICE_ROLE_KEY || process.env.SUPABASE_ANON_KEY || "",
    supabaseTrafficTable: options.supabaseTrafficTable || process.env.SUPABASE_TRAFFIC_TABLE || DEFAULT_SUPABASE_TRAFFIC_TABLE,
    supabaseSlotTable: options.supabaseSlotTable || process.env.SUPABASE_SLOT_TABLE || DEFAULT_SUPABASE_SLOT_TABLE,
    supabaseTaskRpcEnabled: parseBooleanOption(options.supabaseTaskRpcEnabled, process.env.SUPABASE_TASK_RPC_ENABLED),
    androidReleaseRepo: options.androidReleaseRepo === undefined
      ? (process.env.ANDROID_RELEASE_REPO || DEFAULT_ANDROID_RELEASE_REPO)
      : options.androidReleaseRepo,
    githubReleaseApiUrl: options.githubReleaseApiUrl === undefined
      ? (process.env.GITHUB_RELEASE_API_URL || "")
      : options.githubReleaseApiUrl,
    adminApiToken: options.adminApiToken || process.env.ADMIN_API_TOKEN || "",
    adminAllowedOrigins: options.adminAllowedOrigins || parseCsv(process.env.ADMIN_ALLOWED_ORIGINS || ""),
    leases: new Map(),
    taskLeases: new Map(),
    groups: new Map(),
    cookieStore: new Map(),
    accountReports: [],
    deviceCommandReports: [],
    challenges: new Map(),
    policy: { ...DEFAULT_POLICY, ...(options.policy || {}) },
  };
}

function createServer(state = createState()) {
  return http.createServer(async (req, res) => {
    try {
      const requestUrl = new URL(req.url, "http://localhost");
      const pathname = requestUrl.pathname;

      if (req.method === "GET" && pathname === "/health") {
        return sendJson(res, 200, { ok: true });
      }

      if (req.method === "GET" && pathname === "/favicon.ico") {
        res.writeHead(204);
        return res.end();
      }

      if (pathname.startsWith("/admin/api/")) {
        const corsHeaders = adminCorsHeaders(state, req);
        if (req.method === "OPTIONS") {
          res.writeHead(204, corsHeaders);
          return res.end();
        }
        if (!isAdminApiAuthorized(state, req)) {
          return sendJson(res, 401, { error: "unauthorized" }, corsHeaders);
        }
        if (req.method === "GET" && pathname === "/admin/api/snapshot") {
          return sendJson(res, 200, adminSnapshot(state), corsHeaders);
        }
        if (req.method === "GET" && pathname === "/admin/api/challenges") {
          return sendJson(res, 200, adminChallengeList(state), corsHeaders);
        }
        if (req.method === "GET" && /^\/admin\/api\/challenges\/[^/]+\/screenshot$/.test(pathname)) {
          const challengeId = pathname.split("/")[4];
          return sendJson(res, 200, adminChallengeScreenshot(state, challengeId), corsHeaders);
        }
        if (req.method === "POST") {
          const body = await readJson(req);
          switch (pathname) {
            case "/admin/api/accounts":
              return sendJson(res, 200, addAdminAccount(state, body), corsHeaders);
            case "/admin/api/groups/state":
              return sendJson(res, 200, setAdminGroupState(state, body), corsHeaders);
            case "/admin/api/groups/command":
              return sendJson(res, 200, setAdminGroupCommand(state, body), corsHeaders);
            default: {
              const answerMatch = pathname.match(/^\/admin\/api\/challenges\/([^/]+)\/answer$/);
              if (answerMatch) {
                return sendJson(res, 200, answerChallenge(state, answerMatch[1], body), corsHeaders);
              }
              return sendJson(res, 404, { error: "not_found" }, corsHeaders);
            }
          }
        }
        return sendJson(res, 405, { error: "method_not_allowed" }, corsHeaders);
      }

      if (req.method === "GET" && (pathname === "/" || pathname === "/admin" || pathname.startsWith("/admin/"))) {
        return serveAdminAsset(res, pathname);
      }

      if (req.method === "GET" && pathname === "/android/app-release/latest") {
        return sendJson(res, 200, await latestAppRelease(state));
      }

      if (req.method !== "POST") {
        return sendJson(res, 405, { error: "method_not_allowed" });
      }

      const body = await readJson(req);
      switch (pathname) {
        case "/android/accounts/lease":
          return sendJson(res, 200, leaseAccount(state, body));
        case "/android/accounts/report":
          return sendJson(res, 200, reportAccount(state, body));
        case "/android/accounts/release":
          return sendJson(res, 200, releaseAccount(state, body));
        case "/android/tasks/lease":
          return sendJson(res, 200, await leaseTask(state, body));
        case "/android/tasks/report":
          return sendJson(res, 200, await reportTask(state, body));
        case "/android/heartbeat":
          return sendJson(res, 200, heartbeat(state, body));
        case "/android/group/rotation-report":
          return sendJson(res, 200, rotationReport(state, body));
        case "/android/cookies/save":
          return sendJson(res, 200, await saveCookies(state, body));
        case "/android/cookies/load":
          return sendJson(res, 200, await loadCookies(state, body));
        case "/android/challenges/submit":
          return sendJson(res, 200, submitChallenge(state, body));
        case "/android/challenges/poll":
          return sendJson(res, 200, pollChallenge(state, body));
        case "/android/challenges/complete":
          return sendJson(res, 200, completeChallenge(state, body));
        case "/android/commands/report":
          return sendJson(res, 200, reportDeviceCommand(state, body));
        default:
          return sendJson(res, 404, { error: "not_found" });
      }
    } catch (error) {
      return sendJson(res, 500, { error: "server_error", message: error.message });
    }
  });
}

async function leaseTask(state, body) {
  if (state.zeroTrafficApiUrl) {
    const zeroLease = await leaseTaskFromZero(state, body);
    if (zeroLease) return zeroLease;
  }
  if (state.supabaseRestUrl && state.supabaseAnonKey) {
    const supabaseLease = await leaseTaskFromSupabase(state, body);
    if (supabaseLease) return supabaseLease;
  }

  const deviceName = String(body.deviceName || "");
  const strategy = normalizeStrategy(body.strategy);
  const now = Date.now();
  const existing = Array.from(state.taskLeases.values()).find(
    (lease) => lease.deviceName === deviceName && lease.status === "active",
  );
  if (existing) return taskLeaseResponse(existing.product, existing);

  const product = state.products.find(
    (item) =>
      (item.status || "available") === "available" &&
      normalizeStrategy(item.strategy) === strategy &&
      (!item.assignedDeviceName || item.assignedDeviceName === deviceName),
  );
  if (!product) return {};

  product.status = "leased";
  product.assignedDeviceName = deviceName;
  product.lastUsedAt = new Date(now).toISOString();

  const lease = {
    id: `task_${crypto.randomUUID()}`,
    product,
    deviceName,
    strategy,
    status: "active",
    leasedAt: new Date(now).toISOString(),
  };
  rememberTaskLease(state, lease);
  return taskLeaseResponse(product, lease);
}

async function reportTask(state, body) {
  const taskLeaseId = String(body.taskLeaseId || "");
  if (taskLeaseId.startsWith("zero_") && state.zeroTrafficApiUrl) {
    return reportTaskToZero(state, body);
  }
  if (taskLeaseId.startsWith("sb_") && state.supabaseRestUrl && state.supabaseAnonKey) {
    return reportTaskToSupabase(state, body);
  }

  const lease = body.taskLeaseId ? state.taskLeases.get(String(body.taskLeaseId)) : null;
  if (lease) {
    lease.status = "reported";
    lease.result = String(body.result || "");
    lease.message = body.message || null;
    lease.product.lastResult = lease.result;
    lease.product.lastUsedAt = new Date().toISOString();
    if (lease.result === "success") {
      lease.product.status = "available";
      lease.product.successCount = Number(lease.product.successCount || 0) + 1;
    } else {
      lease.product.status = "available";
      lease.product.failCount = Number(lease.product.failCount || 0) + 1;
    }
  }
  return { ok: true };
}

async function leaseTaskFromSupabase(state, body) {
  if (state.supabaseTaskRpcEnabled) {
    return leaseTaskFromSupabaseRpc(state, body);
  }

  const strategy = normalizeStrategy(body.strategy);
  const deviceName = String(body.deviceName || "");

  // 1. 트래픽 큐에서 1건 가져오기 (id 오름차순)
  const trafficRows = await supabaseRequestJson(
    state,
    "GET",
    `/${state.supabaseTrafficTable}?select=id,keyword,keyword_name,link_url,slot_id&order=id.asc&limit=1`,
  );
  if (!Array.isArray(trafficRows) || trafficRows.length === 0) return null;

  const traffic = trafficRows[0];
  const trafficId = traffic.id;
  const slotId = traffic.slot_id;
  if (!trafficId || !slotId) return null;

  // 2. 트래픽 행 DELETE (클레임)
  await supabaseRequestJson(
    state,
    "DELETE",
    `/${state.supabaseTrafficTable}?id=eq.${encodeURIComponent(trafficId)}`,
    null,
    true,
  ).catch(() => {});

  // 3. 슬롯에서 mid 조회
  const slotRows = await supabaseRequestJson(
    state,
    "GET",
    `/${state.supabaseSlotTable}?select=mid&id=eq.${encodeURIComponent(slotId)}&limit=1`,
  ).catch(() => []);
  const mid = Array.isArray(slotRows) && slotRows[0] ? (slotRows[0].mid || "") : "";

  const lease = {
    id: `sb_${trafficId}_${slotId}`,
    supabaseSlotId: slotId,
    product: {
      keyword: traffic.keyword || "",
      secondKeyword: strategy === "G" ? null : (traffic.keyword_name || ""),
      keywordName: strategy === "G" ? (traffic.keyword_name || "") : null,
      linkUrl: traffic.link_url || "",
      mid,
      productTitle: traffic.keyword_name || "",
      productName: traffic.product_name || traffic.productName || traffic.product_title || "",
      catalogMid: traffic.catalog_mid || traffic.catalogMid || "",
    },
    deviceName,
    strategy,
    status: "active",
  };
  rememberTaskLease(state, lease);
  return taskLeaseResponse(lease.product, lease);
}

async function reportTaskToSupabase(state, body) {
  if (state.supabaseTaskRpcEnabled) {
    return reportTaskToSupabaseRpc(state, body);
  }

  const taskLeaseId = String(body.taskLeaseId || "");
  const lease = state.taskLeases.get(taskLeaseId);
  const slotId = lease?.supabaseSlotId || 0;
  const result = String(body.result || "");
  const success = result === "success";

  if (slotId) {
    const rows = await supabaseRequestJson(
      state,
      "GET",
      `/${state.supabaseSlotTable}?select=success_count,fail_count&id=eq.${encodeURIComponent(slotId)}&limit=1`,
    );
    const slot = Array.isArray(rows) && rows[0] ? rows[0] : {};
    const patch = success
      ? { success_count: Number(slot.success_count || 0) + 1 }
      : { fail_count: Number(slot.fail_count || 0) + 1 };
    await supabaseRequestJson(
      state,
      "PATCH",
      `/${state.supabaseSlotTable}?id=eq.${encodeURIComponent(slotId)}`,
      patch,
    );
  }

  await supabaseRequestJson(
    state,
    "POST",
    "/slot_rank_naverapp_history",
    {
      slot_status_id: slotId || null,
      source_table: state.supabaseSlotTable,
      source_row_id: slotId || null,
      customer_id: null,
      keyword: lease?.product?.keyword || null,
      link_url: lease?.product?.linkUrl || null,
      keyword_name: lease?.product?.productTitle || null,
      created_at: new Date().toISOString(),
    },
    true,
  ).catch(() => ({ ok: false }));

  if (lease) lease.status = "reported";
  return { ok: true };
}

async function leaseTaskFromSupabaseRpc(state, body) {
  const strategy = normalizeStrategy(body.strategy);
  const deviceName = String(body.deviceName || "");
  const response = await supabaseRequestJson(
    state,
    "POST",
    "/rpc/claim_android_naver_task",
    {
      p_device_name: deviceName,
      p_strategy: strategy,
    },
  );
  const claimed = firstRpcRow(response);
  if (!claimed) return null;

  const taskId = claimed.task_id ?? claimed.taskId ?? claimed.traffic_id ?? claimed.id ?? "";
  const leaseId = claimed.lease_id ?? claimed.leaseId ?? "";
  const slotId = claimed.slot_id ?? claimed.slotId ?? claimed.slot_status_id ?? "";
  if (!taskId && !leaseId) return null;

  const productTitle = claimed.product_title ?? claimed.productTitle ?? claimed.keyword_name ?? "";
  const productName = claimed.product_name ?? claimed.productName ?? productTitle;
  const lease = {
    id: `sb_${leaseId || taskId}`,
    supabaseTaskId: taskId,
    supabaseLeaseId: leaseId,
    supabaseSlotId: slotId,
    supabaseIdempotencyKey: claimed.idempotency_key ?? claimed.idempotencyKey ?? "",
    product: {
      keyword: claimed.keyword ?? claimed.short_keyword ?? claimed.shortKeyword ?? "",
      secondKeyword: strategy === "G" ? null : (claimed.second_keyword ?? claimed.secondKeyword ?? claimed.keyword_name ?? productTitle),
      keywordName: strategy === "G" ? (claimed.keyword_name ?? claimed.keywordName ?? productTitle) : null,
      linkUrl: claimed.link_url ?? claimed.linkUrl ?? claimed.target_url ?? "",
      mid: claimed.mid ?? claimed.nv_mid ?? claimed.product_mid ?? "",
      productTitle,
      productName,
      catalogMid: claimed.catalog_mid ?? claimed.catalogMid ?? "",
    },
    deviceName,
    strategy,
    status: "active",
    leasedAt: new Date().toISOString(),
  };
  rememberTaskLease(state, lease);
  return taskLeaseResponse(lease.product, lease);
}

async function reportTaskToSupabaseRpc(state, body) {
  const taskLeaseId = String(body.taskLeaseId || "");
  const lease = state.taskLeases.get(taskLeaseId);
  const result = String(body.result || "");
  const success = result === "success";
  const strategy = normalizeStrategy(body.strategy || lease?.strategy);
  await supabaseRequestJson(
    state,
    "POST",
    "/rpc/report_android_naver_task",
    {
      p_task_id: lease?.supabaseTaskId || body.taskId || null,
      p_lease_id: lease?.supabaseLeaseId || body.leaseId || null,
      p_device_name: String(body.deviceName || lease?.deviceName || ""),
      p_success: success,
      p_link_url: body.linkUrl || lease?.product?.linkUrl || null,
      p_source: supabaseTaskSource(strategy),
      p_idempotency_key: body.idempotencyKey || lease?.supabaseIdempotencyKey || taskLeaseId || null,
    },
    true,
  );
  if (lease) {
    lease.status = "reported";
    lease.result = result;
    lease.message = body.message || null;
  }
  return { ok: true };
}

async function leaseTaskFromZero(state, body) {
  const payload = {
    device_id: String(body.deviceName || ""),
  };
  const claimed = await postJson(`${state.zeroTrafficApiUrl.replace(/\/$/, "")}/claim-work`, payload);
  if (!claimed || !claimed.traffic_id) return null;

  const zeroStrategy = normalizeStrategy(body.strategy);
  const zeroKeywordName = claimed.keyword_name || "";
  const lease = {
    id: `zero_${claimed.traffic_id}`,
    zeroTrafficId: claimed.traffic_id,
    zeroSlotId: claimed.slot_id || 0,
    product: {
      keyword: claimed.short_keyword || claimed.product_name || "",
      secondKeyword: zeroStrategy === "G" ? null : (zeroKeywordName || claimed.product_name || ""),
      keywordName: zeroStrategy === "G" ? zeroKeywordName : null,
      linkUrl: claimed.target_url || claimed.link_url || "",
      mid: claimed.nv_mid || "",
      productTitle: claimed.product_name || "",
      productName: claimed.product_name || "",
      catalogMid: claimed.catalog_mid || claimed.catalogMid || "",
    },
    deviceName: String(body.deviceName || ""),
    strategy: zeroStrategy,
    status: "active",
  };
  rememberTaskLease(state, lease);
  return taskLeaseResponse(lease.product, lease);
}

async function reportTaskToZero(state, body) {
  const taskLeaseId = String(body.taskLeaseId || "");
  const lease = state.taskLeases.get(taskLeaseId);
  const trafficId = lease?.zeroTrafficId || Number(taskLeaseId.replace(/^zero_/, ""));
  const slotId = lease?.zeroSlotId || 0;
  const result = String(body.result || "");
  const base = state.zeroTrafficApiUrl.replace(/\/$/, "");

  const source = lease?.strategy === "G" ? "android-samsung-login-G" : "android-samsung-login-A";
  if (result === "success") {
    await postJson(`${base}/complete`, {
      traffic_id: trafficId,
      slot_id: slotId,
      device_id: String(body.deviceName || ""),
      metadata: { source },
    });
  } else {
    await postJson(`${base}/fail`, {
      traffic_id: trafficId,
      slot_id: slotId,
      device_id: String(body.deviceName || ""),
      error_message: String(body.message || "android strategy task failed"),
      metadata: { source },
    });
  }
  if (lease) lease.status = "reported";
  return { ok: true };
}

function leaseAccount(state, body) {
  const deviceName = String(body.deviceName || "");
  const requestedGroupId = String(body.groupId || inferGroupId(deviceName) || "").trim();
  const now = Date.now();
  const existing = Array.from(state.leases.values()).find(
    (lease) => lease.deviceName === deviceName && lease.expiresAtMs > now && lease.status === "active",
  );
  if (existing) return leaseResponse(state, existing.account, existing);

  const account = state.accounts.find(
    (item) =>
      (item.status || "available") === "available" &&
      item.assignedDeviceName === deviceName,
  ) || state.accounts.find(
    (item) =>
      (item.status || "available") === "available" &&
      !item.assignedDeviceName &&
      item.groupId &&
      item.groupId === requestedGroupId,
  ) || state.accounts.find(
    (item) =>
      (item.status || "available") === "available" &&
      !item.assignedDeviceName &&
      !item.groupId,
  );
  if (!account) return {};

  account.status = "leased";
  account.assignedDeviceName = deviceName;
  account.lastUsedAt = new Date(now).toISOString();

  const lease = {
    id: `lease_${crypto.randomUUID()}`,
    account,
    deviceName,
    role: String(body.role || ""),
    strategy: normalizeStrategy(body.strategy),
    status: "active",
    expiresAtMs: now + 15 * 60 * 1000,
  };
  state.leases.set(lease.id, lease);
  return leaseResponse(state, account, lease);
}

function inferGroupId(deviceName) {
  const match = /^([A-Za-z]+\d+)(?:-\d+)?$/.exec(String(deviceName || "").trim());
  return match ? match[1].toLowerCase() : "";
}

function reportAccount(state, body) {
  const lease = body.leaseId ? state.leases.get(String(body.leaseId)) : null;
  const result = String(body.result || "");
  const signals = Array.isArray(body.signals) ? body.signals : [];
  const deviceName = String(body.deviceName || lease?.deviceName || "");
  state.accountReports.unshift({
    leaseId: body.leaseId || null,
    accountAlias: lease?.account?.alias || null,
    deviceName,
    result,
    signals,
    lastUrl: body.lastUrl || null,
    message: body.message || null,
    createdAt: new Date().toISOString(),
  });
  trimArray(state.accountReports, ACCOUNT_REPORT_CAP);
  if (lease) {
    lease.status = "reported";
    if (result === "protected") {
      lease.account.status = "protected";
      lease.account.protectionDetectedAt = new Date().toISOString();
    } else if (result === "manual_check_required") {
      lease.account.status = "manual_check_required";
    } else if (result === "success") {
      lease.account.status = "available";
      lease.account.lastSuccessAt = new Date().toISOString();
    }
  }
  if (deviceName && isLoginBlockedReport(result, signals)) {
    for (const group of state.groups.values()) {
      const device = group.devices.get(deviceName);
      if (device) {
        device.state = "ERROR";
        device.lastError = body.message || signals.join(",") || result;
        device.updatedAt = Date.now();
      }
    }
  }
  return { ok: true };
}

function releaseAccount(state, body) {
  const lease = state.leases.get(String(body.leaseId || ""));
  if (lease) {
    lease.status = "released";
    lease.releaseReason = String(body.reason || "");
    if (lease.account.status === "leased") lease.account.status = "available";
  }
  return { ok: true };
}

function heartbeat(state, body) {
  const groupId = String(body.groupId || "default");
  const group = getGroup(state, groupId);
  evictStaleDevices(state);
  const deviceName = String(body.deviceName || "");
  const role = String(body.role || "soldier");
  const currentCount = Number(body.taskCount || 0);
  const previous = group.devices.get(deviceName);
  if (previous && currentCount > previous.taskCount) {
    group.completedSinceRotation += currentCount - previous.taskCount;
  }

  group.devices.set(deviceName, {
    deviceName,
    groupId,
    role,
    state: String(body.state || "IDLE"),
    taskCount: currentCount,
    currentIp: body.currentIp || null,
    lastError: body.lastError || null,
    appVersion: body.appVersion || null,
    batteryLevel: body.batteryLevel || null,
    model: body.model || null,
    carrier: body.carrier || null,
    updatedAt: Date.now(),
  });

  updateGroupState(state, group);
  return controlResponse(state, group, role, deviceName);
}

async function saveCookies(state, body) {
  const deviceName = String(body.deviceName || "");
  const accountAlias = normalizeCookieAccountAlias(body);
  const cookies = String(body.cookies || "");
  if (!deviceName || !cookies) return { ok: false, error: "missing_fields" };
  const now = new Date().toISOString();
  state.cookieStore.set(cookieStoreKey(deviceName, accountAlias), { cookies, accountAlias, savedAt: now });
  if (state.supabaseRestUrl && state.supabaseAnonKey) {
    await supabaseRequestJson(
      state, "POST", "/device_cookies?on_conflict=device_name,account_alias",
      { device_name: deviceName, account_alias: accountAlias, cookies, updated_at: now },
      true,
    ).catch(() => supabaseRequestJson(
      state, "POST", "/device_cookies?on_conflict=device_name",
      { device_name: deviceName, account_alias: accountAlias, cookies, updated_at: now },
      true,
    )).catch((e) => console.error("cookie save failed:", e.message));
  }
  return { ok: true };
}

async function loadCookies(state, body) {
  const deviceName = String(body.deviceName || "");
  const accountAlias = normalizeCookieAccountAlias(body);
  if (state.supabaseRestUrl && state.supabaseAnonKey) {
    const rows = await supabaseRequestJson(
      state, "GET",
      `/device_cookies?device_name=eq.${encodeURIComponent(deviceName)}&account_alias=eq.${encodeURIComponent(accountAlias)}&limit=1`,
    ).catch(() => null);
    const row = Array.isArray(rows) && rows[0] ? rows[0] : null;
    if (row) {
      state.cookieStore.set(cookieStoreKey(deviceName, accountAlias), {
        cookies: row.cookies,
        accountAlias: row.account_alias || accountAlias,
        savedAt: row.updated_at,
      });
      return { cookies: row.cookies, accountAlias: row.account_alias || accountAlias, savedAt: row.updated_at };
    }
  }
  const entry = state.cookieStore.get(cookieStoreKey(deviceName, accountAlias));
  if (!entry) return { cookies: null };
  return { cookies: entry.cookies, accountAlias: entry.accountAlias, savedAt: entry.savedAt };
}

function normalizeCookieAccountAlias(body) {
  return String(body.accountAlias || body.account_alias || "").trim();
}

function cookieStoreKey(deviceName, accountAlias) {
  return `${deviceName}\u0000${accountAlias}`;
}

async function latestAppRelease(state) {
  const candidates = [];
  if (state.supabaseRestUrl && state.supabaseAnonKey) {
    const rows = await supabaseRequestJson(
      state, "GET",
      "/app_releases?select=version_code,version_name,apk_url,sha256&enabled=eq.true&order=version_code.desc&limit=1",
    ).catch(() => null);
    const row = Array.isArray(rows) && rows[0] ? rows[0] : null;
    if (row) candidates.push(normalizeRelease(row));
  }

  const release = state.appReleases
    .filter((item) => item.enabled !== false)
    .sort((a, b) => releaseVersionCode(b) - releaseVersionCode(a))[0];
  if (release) candidates.push(normalizeRelease(release));

  const githubRelease = await latestGitHubAndroidRelease(state).catch(() => null);
  if (githubRelease) candidates.push(githubRelease);

  return candidates
    .filter((item) => item.version_code > 0 && item.apk_url)
    .sort((a, b) => b.version_code - a.version_code)[0] || {};
}

function releaseVersionCode(release) {
  return Number(release.versionCode || release.version_code || 0);
}

function normalizeRelease(release) {
  return {
    version_code: releaseVersionCode(release),
    version_name: release.versionName || release.version_name || "",
    apk_url: release.apkUrl || release.apk_url || "",
    sha256: release.sha256 || null,
  };
}

async function latestGitHubAndroidRelease(state) {
  if (!state.androidReleaseRepo) return null;
  const apiUrl = state.githubReleaseApiUrl ||
    `https://api.github.com/repos/${state.androidReleaseRepo}/releases?per_page=20`;
  const response = await fetch(apiUrl, {
    headers: {
      Accept: "application/vnd.github+json",
      "User-Agent": "naver-traffic-android-samsung-update-check",
    },
  });
  if (!response.ok) return null;
  const releases = await response.json();
  if (!Array.isArray(releases)) return null;
  const candidates = releases
    .filter((release) => !release.draft && !release.prerelease)
    .filter((release) => /^android-\d+\.\d+\.\d+$/.test(String(release.tag_name || "")))
    .map(releaseFromGitHub)
    .filter(Boolean);
  return candidates.sort((a, b) => b.version_code - a.version_code)[0] || null;
}

function releaseFromGitHub(release) {
  const assets = Array.isArray(release.assets) ? release.assets : [];
  const apk = assets.find((asset) => /\.apk$/i.test(String(asset.name || "")));
  if (!apk) return null;
  const versionName = String(release.tag_name || "").replace(/^android-/, "");
  const versionCode = Number(
    /(?:^|[-_])v(\d+)(?:[-_.]|$)/i.exec(String(apk.name || ""))?.[1] ||
    /(?:^|[-_])v(\d+)(?:[-_.]|$)/i.exec(String(release.name || ""))?.[1] ||
    0,
  );
  if (!versionCode) return null;
  return {
    version_code: versionCode,
    version_name: versionName,
    apk_url: apk.browser_download_url || "",
    sha256: String(apk.digest || "").replace(/^sha256:/i, "") || null,
  };
}

function rotationReport(state, body) {
  const group = getGroup(state, String(body.groupId || "default"));
  if (body.success) {
    group.state = "READY";
    group.completedSinceRotation = 0;
    group.drainingStartedAt = null;
  } else {
    group.state = "ROTATION_FAILED";
  }
  group.lastRotationReport = { ...body, reportedAt: new Date().toISOString() };
  return { ok: true, groupState: group.state };
}

function updateGroupState(state, group) {
  const now = Date.now();
  if (group.state === "READY" && group.completedSinceRotation >= state.policy.rotateEveryGroupTasks) {
    group.state = "DRAINING";
    group.drainingStartedAt = now;
  }

  if (group.state === "DRAINING") {
    const elapsedSec = Math.floor((now - group.drainingStartedAt) / 1000);
    const devices = Array.from(group.devices.values());
    if (elapsedSec > state.policy.drainTimeoutSec) {
      group.state = "ROTATION_FAILED";
    } else if (devices.length > 0 && devices.every((device) => device.state !== "RUNNING_TASK")) {
      group.state = "ROTATING";
    }
  }
}

function controlResponse(state, group, role, deviceName) {
  const command = commandFor(group.state, role);
  return {
    groupState: group.state,
    command,
    commandId: command === "NONE" ? null : group.commandId,
    deviceCommand: pendingDeviceCommandFor(group, deviceName),
    policy: { ...state.policy, rotateOwner: group.groupId },
  };
}

function commandFor(groupState, role) {
  if (groupState === "ROTATING") return role === "boss" ? "ROTATE_GROUP_IP" : "PAUSE_FOR_ROTATION";
  if (groupState === "DRAINING") return "PAUSE_FOR_ROTATION";
  if (["PAUSED", "STOPPED", "ROTATION_FAILED"].includes(groupState)) return "STOP";
  return "NONE";
}

function pendingDeviceCommandFor(group, deviceName) {
  const command = group.deviceCommand;
  if (!command || !deviceName) return null;
  if (Date.now() > command.expiresAtMs) {
    group.deviceCommand = null;
    return null;
  }
  if (command.reports?.has(deviceName)) return null;
  return {
    commandId: command.commandId,
    type: command.type,
    payload: command.payload,
  };
}

function getGroup(state, groupId) {
  if (!state.groups.has(groupId)) {
    state.groups.set(groupId, {
      groupId,
      state: "READY",
      commandId: `cmd_${crypto.randomUUID()}`,
      deviceCommand: null,
      completedSinceRotation: 0,
      drainingStartedAt: null,
      devices: new Map(),
    });
  }
  return state.groups.get(groupId);
}

function rememberTaskLease(state, lease) {
  state.taskLeases.set(lease.id, lease);
  while (state.taskLeases.size > TASK_LEASE_CAP) {
    const oldestKey = state.taskLeases.keys().next().value;
    state.taskLeases.delete(oldestKey);
  }
}

function trimArray(items, maxSize) {
  if (items.length > maxSize) items.length = maxSize;
}

function evictStaleDevices(state, now = Date.now()) {
  for (const group of state.groups.values()) {
    for (const [deviceName, device] of group.devices.entries()) {
      if (now - device.updatedAt > DEVICE_EVICT_MS) {
        group.devices.delete(deviceName);
      }
    }
  }
}

function isDeviceOnline(device, now = Date.now()) {
  return now - device.updatedAt <= DEVICE_OFFLINE_MS;
}

function trimChallenges(state) {
  if (state.challenges.size <= CHALLENGE_CAP) return;
  const sorted = Array.from(state.challenges.entries())
    .sort((a, b) => a[1].createdAt.localeCompare(b[1].createdAt));
  sorted.slice(0, state.challenges.size - CHALLENGE_CAP).forEach(([key]) => state.challenges.delete(key));
}

function taskLeaseResponse(product, lease) {
  const base = {
    taskLeaseId: lease.id,
    keyword: product.keyword,
    linkUrl: product.linkUrl,
    mid: product.mid || null,
    productTitle: product.productTitle || null,
    productName: product.productName || null,
    catalogMid: product.catalogMid || null,
  };
  if (normalizeStrategy(lease.strategy) === "G") {
    return {
      ...base,
      keywordName: product.keywordName || product.secondKeyword || "",
      secondKeyword: null,
    };
  }
  return {
    ...base,
    secondKeyword: product.secondKeyword || product.keywordName || "",
  };
}

function leaseResponse(state, account, lease) {
  return {
    leaseId: lease.id,
    accountAlias: account.alias,
    loginId: readAccountSecret(state, account, "loginId"),
    password: readAccountSecret(state, account, "password"),
    expiresAt: new Date(lease.expiresAtMs).toISOString(),
  };
}

function loadAccounts(accountsFile) {
  const localPath = accountsFile || path.join(__dirname, "accounts.local.json");
  if (!fs.existsSync(localPath)) return [];
  const parsed = JSON.parse(fs.readFileSync(localPath, "utf8"));
  return Array.isArray(parsed.accounts) ? parsed.accounts : [];
}

function parseCsv(value) {
  return String(value || "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

function parseBooleanOption(optionValue, envValue) {
  const value = optionValue === undefined ? envValue : optionValue;
  if (typeof value === "boolean") return value;
  return ["1", "true", "yes", "on"].includes(String(value || "").trim().toLowerCase());
}

function firstRpcRow(response) {
  if (Array.isArray(response)) return response[0] || null;
  return response || null;
}

function supabaseTaskSource(strategy) {
  return normalizeStrategy(strategy) === "G" ? "android-samsung-login-G" : "android-samsung-login-A";
}

function adminCorsHeaders(state, req) {
  const origin = req.headers.origin;
  if (!origin) return {};
  const allowed = state.adminAllowedOrigins;
  if (allowed.length === 0) return {};
  if (!allowed.includes("*") && !allowed.includes(origin)) return {};
  return {
    "Access-Control-Allow-Origin": allowed.includes("*") ? "*" : origin,
    "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type,Authorization,X-Admin-Token",
    "Access-Control-Max-Age": "600",
    Vary: "Origin",
  };
}

function isAdminApiAuthorized(state, req) {
  if (!state.adminApiToken) return true;
  const authorization = String(req.headers.authorization || "");
  const bearer = authorization.startsWith("Bearer ") ? authorization.slice("Bearer ".length) : "";
  const headerToken = String(req.headers["x-admin-token"] || "");
  return safeEqual(state.adminApiToken, bearer) || safeEqual(state.adminApiToken, headerToken);
}

function safeEqual(expected, actual) {
  const expectedBuffer = Buffer.from(String(expected));
  const actualBuffer = Buffer.from(String(actual));
  if (expectedBuffer.length !== actualBuffer.length) return false;
  return crypto.timingSafeEqual(expectedBuffer, actualBuffer);
}

const CHALLENGE_EXPIRE_MS = 10 * 60 * 1000; // 10분

function submitChallenge(state, body) {
  const id = `chal_${crypto.randomUUID()}`;
  const now = Date.now();
  state.challenges.set(id, {
    id,
    deviceName: String(body.deviceName || ""),
    leaseId: body.leaseId || null,
    accountAlias: String(body.accountAlias || ""),
    screenshotBase64: String(body.screenshotBase64 || ""),
    signalType: String(body.signalType || "CAPTCHA_OR_SECURITY_PAGE"),
    pageUrl: body.pageUrl || null,
    status: "pending",
    answer: null,
    createdAt: new Date(now).toISOString(),
    answeredAt: null,
    completedAt: null,
    success: null,
  });
  // 오래된 완료 챌린지 정리 (최대 200개 유지)
  trimChallenges(state);
  return { challengeId: id };
}

function pollChallenge(state, body) {
  const id = String(body.challengeId || "");
  const challenge = state.challenges.get(id);
  if (!challenge) return { status: "expired", answer: null };
  // 만료 처리
  if (challenge.status === "pending" && Date.now() - new Date(challenge.createdAt).getTime() > CHALLENGE_EXPIRE_MS) {
    challenge.status = "expired";
  }
  return { status: challenge.status, answer: challenge.answer };
}

function completeChallenge(state, body) {
  const id = String(body.challengeId || "");
  const challenge = state.challenges.get(id);
  if (challenge && challenge.status !== "completed") {
    challenge.status = "completed";
    challenge.success = Boolean(body.success);
    challenge.completedAt = new Date().toISOString();
  }
  return { ok: true };
}

function answerChallenge(state, id, body) {
  const challenge = state.challenges.get(id);
  if (!challenge) return { ok: false, error: "not_found" };
  if (challenge.status !== "pending") return { ok: false, error: "already_answered" };
  challenge.status = "answered";
  challenge.answer = String(body.answer || "");
  challenge.answeredAt = new Date().toISOString();
  return { ok: true };
}

function adminChallengeList(state) {
  const now = Date.now();
  return Array.from(state.challenges.values())
    .filter((c) => c.status !== "completed" || now - new Date(c.completedAt || c.createdAt).getTime() < 60_000)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    .slice(0, 50)
    .map((c) => ({
      id: c.id,
      deviceName: c.deviceName,
      accountAlias: c.accountAlias,
      signalType: c.signalType,
      pageUrl: c.pageUrl,
      status: c.status,
      answer: c.answer,
      createdAt: c.createdAt,
      answeredAt: c.answeredAt,
      completedAt: c.completedAt,
      success: c.success,
      waitingSec: Math.floor((now - new Date(c.createdAt).getTime()) / 1000),
    }));
}

function adminChallengeScreenshot(state, id) {
  const challenge = state.challenges.get(id);
  if (!challenge) return { error: "not_found" };
  return { screenshotBase64: challenge.screenshotBase64 };
}

function publicDeviceCommandInfo(command) {
  if (!command) return null;
  const reports = Array.from(command.reports?.values?.() || []);
  return {
    commandId: command.commandId,
    type: command.type,
    requestedAt: command.requestedAt,
    expiresAtIso: new Date(command.expiresAtMs).toISOString(),
    reportedCount: reports.length,
    successCount: reports.filter((report) => report.success).length,
    failureCount: reports.filter((report) => !report.success).length,
  };
}

function adminSnapshot(state) {
  const now = Date.now();
  evictStaleDevices(state, now);
  const latestReportByDevice = new Map();
  state.accountReports.forEach((report) => {
    if (report.deviceName && !latestReportByDevice.has(report.deviceName)) {
      latestReportByDevice.set(report.deviceName, report);
    }
  });
  const latestDeviceCommandReportByDevice = new Map();
  state.deviceCommandReports.forEach((report) => {
    if (report.deviceName && !latestDeviceCommandReportByDevice.has(report.deviceName)) {
      latestDeviceCommandReportByDevice.set(report.deviceName, report);
    }
  });
  const accountByDevice = new Map(
    state.accounts
      .filter((account) => account.assignedDeviceName)
      .map((account) => [account.assignedDeviceName, account]),
  );
  const groups = Array.from(state.groups.values())
    .sort((a, b) => a.groupId.localeCompare(b.groupId))
    .map((group) => {
      const devices = Array.from(group.devices.values());
      const onlineDevices = devices.filter((device) => isDeviceOnline(device, now));
      if (group.deviceCommand && now > group.deviceCommand.expiresAtMs) {
        group.deviceCommand = null;
      }
      return {
        groupId: group.groupId,
        state: group.state,
        commandId: group.commandId,
        deviceCommand: publicDeviceCommandInfo(group.deviceCommand),
        completedSinceRotation: group.completedSinceRotation,
        drainingStartedAt: group.drainingStartedAt ? new Date(group.drainingStartedAt).toISOString() : null,
        deviceCount: devices.length,
        onlineCount: onlineDevices.length,
        bossCount: devices.filter((device) => device.role === "boss").length,
        soldierCount: devices.filter((device) => device.role === "soldier").length,
        runningCount: devices.filter((device) => device.state === "RUNNING_TASK").length,
        lastRotationReport: group.lastRotationReport || null,
      };
    });

  const devices = Array.from(state.groups.values())
    .flatMap((group) => Array.from(group.devices.values()).map((device) => {
      const report = latestReportByDevice.get(device.deviceName) || null;
      const latestDeviceCommandReport = latestDeviceCommandReportByDevice.get(device.deviceName) || null;
      const account = accountByDevice.get(device.deviceName) || null;
      return {
        ...device,
        accountAlias: account?.alias || report?.accountAlias || null,
        accountStatus: account?.status || null,
        latestAccountReport: report,
        latestDeviceCommandReport,
        accountAlert: deviceAccountAlert(account, report),
        online: isDeviceOnline(device, now),
        updatedAtIso: new Date(device.updatedAt).toISOString(),
        lastSeenSec: Math.floor((now - device.updatedAt) / 1000),
      };
    }))
    .sort((a, b) => a.groupId.localeCompare(b.groupId) || a.deviceName.localeCompare(b.deviceName));

  return {
    server: {
      now: new Date(now).toISOString(),
      uptimeSec: Math.floor((now - state.startedAt) / 1000),
      zeroTrafficApiConfigured: Boolean(state.zeroTrafficApiUrl),
      supabaseConfigured: Boolean(state.supabaseRestUrl && state.supabaseAnonKey),
      accountPersistence: state.accountCryptoKey ? "encrypted-file" : "memory-only",
      adminApiProtected: Boolean(state.adminApiToken),
      adminAllowedOrigins: state.adminAllowedOrigins,
    },
    policy: state.policy,
    summary: {
      groups: groups.length,
      devices: devices.length,
      onlineDevices: devices.filter((device) => device.online).length,
      protectedDevices: devices.filter((device) => device.accountAlert?.active).length,
      bosses: devices.filter((device) => device.role === "boss").length,
      soldiers: devices.filter((device) => device.role === "soldier").length,
      runningDevices: devices.filter((device) => device.state === "RUNNING_TASK").length,
      accounts: state.accounts.length,
      availableAccounts: state.accounts.filter((account) => (account.status || "available") === "available").length,
      protectedAccounts: state.accounts.filter((account) => ["protected", "manual_check_required"].includes(account.status)).length,
      products: state.products.length,
      activeTaskLeases: Array.from(state.taskLeases.values()).filter((lease) => lease.status === "active").length,
      pendingChallenges: Array.from(state.challenges.values()).filter((c) => c.status === "pending").length,
    },
    groups,
    devices,
    accounts: state.accounts.map((account) => publicAccountInfo(account, state)),
    products: state.products.slice(0, 100).map(publicProductInfo),
    taskLeases: Array.from(state.taskLeases.values()).slice(-50).map(publicTaskLeaseInfo),
    accountReports: state.accountReports.slice(0, 100),
    deviceCommandReports: state.deviceCommandReports.slice(0, 100),
  };
}

function isLoginBlockedReport(result, signals) {
  return ["protected", "manual_check_required", "invalid", "session_expired"].includes(result) ||
    signals.some((signal) => [
      "PROTECTION_TEXT",
      "CAPTCHA_OR_SECURITY_PAGE",
      "PHONE_VERIFICATION_REQUIRED",
      "EMAIL_VERIFICATION_REQUIRED",
      "PASSWORD_RETRY_REQUIRED",
      "LOGIN_STILL_REQUIRED",
      "SESSION_EXPIRED",
      "UNEXPECTED_LOGOUT",
      "SAMSUNG_INTERNET_MISSING",
    ].includes(signal));
}

function deviceAccountAlert(account, report) {
  const accountStatus = String(account?.status || "");
  const reportResult = String(report?.result || "");
  const signals = Array.isArray(report?.signals) ? report.signals : [];
  const active = ["protected", "manual_check_required", "invalid"].includes(accountStatus) ||
    isLoginBlockedReport(reportResult, signals);
  if (!active) return null;
  return {
    active: true,
    accountAlias: account?.alias || report?.accountAlias || null,
    accountStatus: account?.status || null,
    result: report?.result || null,
    signals,
    message: report?.message || null,
    detectedAt: report?.createdAt || account?.protectionDetectedAt || account?.updatedAt || null,
  };
}

function addAdminAccount(state, body) {
  const alias = String(body.alias || "").trim();
  const loginId = String(body.loginId || "").trim();
  const password = String(body.password || "");
  const groupId = String(body.groupId || "").trim();
  const assignedDeviceName = String(body.assignedDeviceName || body.deviceName || "").trim();
  if (!alias || !loginId || !password) {
    return { ok: false, error: "missing_fields", message: "alias, loginId, password are required" };
  }
  if (state.accounts.some((account) => account.alias === alias)) {
    return { ok: false, error: "duplicate_alias", message: "account alias already exists" };
  }

  const account = {
    alias,
    status: "available",
    groupId: groupId || null,
    assignedDeviceName: assignedDeviceName || null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };
  if (state.accountCryptoKey) {
    account.loginIdEnc = encryptSecret(state.accountCryptoKey, loginId);
    account.passwordEnc = encryptSecret(state.accountCryptoKey, password);
  } else {
    account.loginId = loginId;
    account.password = password;
    account.memoryOnly = true;
  }

  state.accounts.push(account);
  let persisted = false;
  if (state.accountCryptoKey) {
    persistAccounts(state);
    persisted = true;
  }

  return { ok: true, persisted, account: publicAccountInfo(account, state) };
}

function setAdminGroupState(state, body) {
  const groupId = String(body.groupId || "").trim();
  const nextState = String(body.state || "").trim().toUpperCase();
  const allowed = new Set(["READY", "DRAINING", "ROTATING", "ROTATION_FAILED", "PAUSED", "STOPPED"]);
  if (!groupId || !allowed.has(nextState)) {
    return { ok: false, error: "invalid_group_state" };
  }
  const group = getGroup(state, groupId);
  group.state = nextState;
  group.commandId = `cmd_${crypto.randomUUID()}`;
  group.drainingStartedAt = nextState === "DRAINING" ? Date.now() : null;
  if (nextState === "READY") group.completedSinceRotation = 0;
  return { ok: true, groupId, groupState: group.state, commandId: group.commandId };
}

function setAdminGroupCommand(state, body) {
  const groupId = String(body.groupId || "").trim();
  const type = String(body.command || body.type || "").trim().toUpperCase();
  if (!groupId || !DEVICE_COMMAND_TYPES.has(type)) {
    return { ok: false, error: "invalid_device_command" };
  }

  const group = getGroup(state, groupId);
  const now = Date.now();
  group.deviceCommand = {
    commandId: `devcmd_${crypto.randomUUID()}`,
    type,
    payload: body.payload == null ? null : String(body.payload),
    requestedAt: new Date(now).toISOString(),
    expiresAtMs: now + DEVICE_COMMAND_TTL_MS,
    reports: new Map(),
  };
  return {
    ok: true,
    groupId,
    commandId: group.deviceCommand.commandId,
    command: group.deviceCommand.type,
    expiresAtIso: new Date(group.deviceCommand.expiresAtMs).toISOString(),
  };
}

function reportDeviceCommand(state, body) {
  const groupId = String(body.groupId || "").trim();
  const deviceName = String(body.deviceName || "").trim();
  const commandId = String(body.commandId || "").trim();
  const command = String(body.command || body.type || "").trim().toUpperCase();
  const success = body.success === true || String(body.result || "").toLowerCase() === "succeeded";
  const message = body.message == null ? null : String(body.message);
  if (!groupId || !deviceName || !command) {
    return { ok: false, error: "invalid_device_command_report" };
  }

  const report = {
    commandId: commandId || null,
    deviceName,
    groupId,
    command,
    success,
    message,
    reportedAt: new Date().toISOString(),
  };
  state.deviceCommandReports.unshift(report);
  trimArray(state.deviceCommandReports, DEVICE_COMMAND_REPORT_CAP);

  const group = getGroup(state, groupId);
  if (group.deviceCommand && (!commandId || group.deviceCommand.commandId === commandId)) {
    group.deviceCommand.reports.set(deviceName, report);
  }
  return { ok: true };
}

function publicAccountInfo(account, state = null) {
  const loginId = account.loginId || (state ? readAccountSecret(state, account, "loginId") : "");
  return {
    alias: account.alias,
    groupId: account.groupId || null,
    status: account.status || "available",
    assignedDeviceName: account.assignedDeviceName || null,
    lastUsedAt: account.lastUsedAt || null,
    lastSuccessAt: account.lastSuccessAt || null,
    protectionDetectedAt: account.protectionDetectedAt || null,
    failCount: Number(account.failCount || 0),
    loginIdMasked: maskLogin(loginId || (account.loginIdEnc ? "encrypted" : "")),
    secretStorage: account.loginIdEnc || account.passwordEnc ? "encrypted" : (account.memoryOnly ? "memory" : "plain-file"),
  };
}

function publicProductInfo(product) {
  return {
    keyword: product.keyword || "",
    secondKeyword: product.secondKeyword || null,
    keywordName: product.keywordName || null,
    linkUrl: product.linkUrl || "",
    mid: product.mid || null,
    productTitle: product.productTitle || null,
    productName: product.productName || null,
    catalogMid: product.catalogMid || null,
    strategy: normalizeStrategy(product.strategy),
    status: product.status || "available",
    successCount: Number(product.successCount || 0),
    failCount: Number(product.failCount || 0),
    assignedDeviceName: product.assignedDeviceName || null,
    lastUsedAt: product.lastUsedAt || null,
  };
}

function normalizeStrategy(strategy) {
  const normalized = String(strategy || DEFAULT_STRATEGY).trim().toUpperCase();
  return normalized || DEFAULT_STRATEGY;
}

function publicTaskLeaseInfo(lease) {
  return {
    id: lease.id,
    taskId: lease.supabaseTaskId || lease.zeroTrafficId || null,
    leaseId: lease.supabaseLeaseId || null,
    slotId: lease.supabaseSlotId || lease.zeroSlotId || null,
    idempotencyKey: lease.supabaseIdempotencyKey || null,
    linkUrl: lease.product?.linkUrl || null,
    deviceName: lease.deviceName,
    strategy: lease.strategy,
    status: lease.status,
    leasedAt: lease.leasedAt || null,
    result: lease.result || null,
    productTitle: lease.product?.productTitle || null,
    mid: lease.product?.mid || null,
  };
}

function maskLogin(loginId) {
  if (!loginId) return "";
  if (loginId === "encrypted") return "encrypted";
  const [name, domain] = loginId.split("@");
  const maskedName = name.length <= 2 ? `${name[0] || ""}*` : `${name.slice(0, 2)}***${name.slice(-1)}`;
  return domain ? `${maskedName}@${domain}` : maskedName;
}

function persistAccounts(state) {
  const accounts = state.accounts.map((account) => {
    const copy = { ...account };
    delete copy.memoryOnly;
    if (!copy.loginIdEnc && copy.loginId) copy.loginIdEnc = encryptSecret(state.accountCryptoKey, copy.loginId);
    if (!copy.passwordEnc && copy.password) copy.passwordEnc = encryptSecret(state.accountCryptoKey, copy.password);
    delete copy.loginId;
    delete copy.password;
    return copy;
  });
  fs.writeFileSync(state.accountsFile, `${JSON.stringify({ accounts }, null, 2)}\n`, "utf8");
}

function readAccountSecret(state, account, field) {
  const plain = account[field];
  if (plain) return plain;
  const encrypted = account[`${field}Enc`];
  if (!encrypted) return "";
  const key = state.accountCryptoKey;
  if (!key) return "";
  return decryptSecret(key, encrypted);
}

function normalizeAccountCryptoKey(secret) {
  if (!secret) return null;
  return crypto.createHash("sha256").update(String(secret)).digest();
}

function encryptSecret(key, value) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
  const encrypted = Buffer.concat([cipher.update(String(value), "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return `v1:${iv.toString("base64url")}:${tag.toString("base64url")}:${encrypted.toString("base64url")}`;
}

function decryptSecret(key, value) {
  const [version, ivRaw, tagRaw, encryptedRaw] = String(value).split(":");
  if (version !== "v1") return "";
  const decipher = crypto.createDecipheriv("aes-256-gcm", key, Buffer.from(ivRaw, "base64url"));
  decipher.setAuthTag(Buffer.from(tagRaw, "base64url"));
  return Buffer.concat([
    decipher.update(Buffer.from(encryptedRaw, "base64url")),
    decipher.final(),
  ]).toString("utf8");
}

function serveAdminAsset(res, pathname) {
  const adminRoot = path.resolve(
    process.env.ADMIN_ASSET_DIR || path.join(__dirname, "..", "web", "admin"),
  );
  const relativePath = pathname === "/" || pathname === "/admin" || pathname === "/admin/"
    ? "index.html"
    : pathname.replace(/^\/admin\//, "");
  const normalized = path.normalize(relativePath).replace(/^(\.\.[/\\])+/, "");
  const target = path.join(adminRoot, normalized);
  const resolvedTarget = path.resolve(target);
  if (!resolvedTarget.startsWith(adminRoot) || !fs.existsSync(resolvedTarget) || fs.statSync(resolvedTarget).isDirectory()) {
    return sendJson(res, 404, { error: "not_found" });
  }
  const ext = path.extname(resolvedTarget).toLowerCase();
  const mime = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
    ".svg": "image/svg+xml",
  }[ext] || "application/octet-stream";
  const body = fs.readFileSync(resolvedTarget);
  res.writeHead(200, {
    "Content-Type": mime,
    "Content-Length": body.length,
    "Cache-Control": "no-store",
  });
  res.end(body);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let raw = "";
    req.on("data", (chunk) => {
      raw += chunk;
    });
    req.on("end", () => {
      try {
        resolve(raw ? JSON.parse(raw) : {});
      } catch (error) {
        reject(error);
      }
    });
    req.on("error", reject);
  });
}

function sendJson(res, status, body, headers = {}) {
  const text = JSON.stringify(body);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(text),
    ...headers,
  });
  res.end(text);
}

async function postJson(url, body) {
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    if (response.status === 404) return null;
    throw new Error(`upstream ${response.status}: ${await response.text()}`);
  }
  return response.json();
}

async function supabaseRequestJson(state, method, path, body = null, preferMinimal = false) {
  const base = state.supabaseRestUrl.replace(/\/$/, "");
  const response = await fetch(`${base}${path}`, {
    method,
    headers: {
      apikey: state.supabaseAnonKey,
      Authorization: `Bearer ${state.supabaseAnonKey}`,
      "Content-Type": "application/json",
      Accept: "application/json",
      Prefer: preferMinimal ? "return=minimal" : "return=representation",
    },
    body: body == null ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    throw new Error(`supabase ${response.status}: ${await response.text()}`);
  }
  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

if (require.main === module) {
  const port = Number(process.env.PORT || 8080);
  createServer().listen(port, "0.0.0.0", () => {
    console.log(`dev server listening on http://0.0.0.0:${port}`);
  });
}

module.exports = {
  createServer,
  createState,
  heartbeat,
  rotationReport,
};
