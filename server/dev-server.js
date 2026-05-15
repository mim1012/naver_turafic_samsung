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

function createState(options = {}) {
  return {
    accounts: options.accounts || loadAccounts(),
    products: options.products || loadProducts(),
    zeroTrafficApiUrl: options.zeroTrafficApiUrl || process.env.ZERO_TRAFFIC_API_URL || "",
    supabaseRestUrl: options.supabaseRestUrl || process.env.SUPABASE_REST_URL || "",
    supabaseAnonKey: options.supabaseAnonKey || process.env.SUPABASE_ANON_KEY || "",
    supabaseTrafficTable: options.supabaseTrafficTable || process.env.SUPABASE_TRAFFIC_TABLE || DEFAULT_SUPABASE_TRAFFIC_TABLE,
    supabaseSlotTable: options.supabaseSlotTable || process.env.SUPABASE_SLOT_TABLE || DEFAULT_SUPABASE_SLOT_TABLE,
    leases: new Map(),
    taskLeases: new Map(),
    groups: new Map(),
    cookieStore: new Map(),
    policy: { ...DEFAULT_POLICY, ...(options.policy || {}) },
  };
}

function createServer(state = createState()) {
  return http.createServer(async (req, res) => {
    try {
      if (req.method === "GET" && req.url === "/health") {
        return sendJson(res, 200, { ok: true });
      }

      if (req.method !== "POST") {
        return sendJson(res, 405, { error: "method_not_allowed" });
      }

      const body = await readJson(req);
      switch (req.url) {
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
  const strategy = String(body.strategy || "A");
  const now = Date.now();
  const existing = Array.from(state.taskLeases.values()).find(
    (lease) => lease.deviceName === deviceName && lease.status === "active",
  );
  if (existing) return taskLeaseResponse(existing.product, existing);

  const product = state.products.find(
    (item) =>
      (item.status || "available") === "available" &&
      (item.strategy || "A") === strategy &&
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
  state.taskLeases.set(lease.id, lease);
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
  const strategy = String(body.strategy || "A");
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
    },
    deviceName,
    strategy,
    status: "active",
  };
  state.taskLeases.set(lease.id, lease);
  return taskLeaseResponse(lease.product, lease);
}

async function reportTaskToSupabase(state, body) {
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

async function leaseTaskFromZero(state, body) {
  const payload = {
    device_id: String(body.deviceName || ""),
  };
  const claimed = await postJson(`${state.zeroTrafficApiUrl.replace(/\/$/, "")}/claim-work`, payload);
  if (!claimed || !claimed.traffic_id) return null;

  const zeroStrategy = String(body.strategy || "A");
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
    },
    deviceName: String(body.deviceName || ""),
    strategy: zeroStrategy,
    status: "active",
  };
  state.taskLeases.set(lease.id, lease);
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
  const now = Date.now();
  const existing = Array.from(state.leases.values()).find(
    (lease) => lease.deviceName === deviceName && lease.expiresAtMs > now && lease.status === "active",
  );
  if (existing) return leaseResponse(existing.account, existing);

  const account = state.accounts.find(
    (item) =>
      (item.status || "available") === "available" &&
      (!item.assignedDeviceName || item.assignedDeviceName === deviceName),
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
    strategy: String(body.strategy || ""),
    status: "active",
    expiresAtMs: now + 15 * 60 * 1000,
  };
  state.leases.set(lease.id, lease);
  return leaseResponse(account, lease);
}

function reportAccount(state, body) {
  const lease = body.leaseId ? state.leases.get(String(body.leaseId)) : null;
  if (lease) {
    lease.status = "reported";
    const result = String(body.result || "");
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
  const deviceName = String(body.deviceName || "");
  const role = String(body.role || "soldier");
  const currentCount = Number(body.taskCount || 0);
  const previous = group.devices.get(deviceName);
  if (previous && currentCount > previous.taskCount) {
    group.completedSinceRotation += currentCount - previous.taskCount;
  }

  group.devices.set(deviceName, {
    deviceName,
    role,
    state: String(body.state || "IDLE"),
    taskCount: currentCount,
    updatedAt: Date.now(),
  });

  updateGroupState(state, group);
  return controlResponse(state, group, role);
}

async function saveCookies(state, body) {
  const deviceName = String(body.deviceName || "");
  const cookies = String(body.cookies || "");
  if (!deviceName || !cookies) return { ok: false, error: "missing_fields" };
  const now = new Date().toISOString();
  state.cookieStore.set(deviceName, { cookies, savedAt: now });
  if (state.supabaseRestUrl && state.supabaseAnonKey) {
    await supabaseRequestJson(
      state, "POST", "/device_cookies?on_conflict=device_name",
      { device_name: deviceName, cookies, updated_at: now },
      true,
    ).catch((e) => console.error("cookie save failed:", e.message));
  }
  return { ok: true };
}

async function loadCookies(state, body) {
  const deviceName = String(body.deviceName || "");
  if (state.supabaseRestUrl && state.supabaseAnonKey) {
    const rows = await supabaseRequestJson(
      state, "GET",
      `/device_cookies?device_name=eq.${encodeURIComponent(deviceName)}&limit=1`,
    ).catch(() => null);
    const row = Array.isArray(rows) && rows[0] ? rows[0] : null;
    if (row) {
      state.cookieStore.set(deviceName, { cookies: row.cookies, savedAt: row.updated_at });
      return { cookies: row.cookies, savedAt: row.updated_at };
    }
  }
  const entry = state.cookieStore.get(deviceName);
  if (!entry) return { cookies: null };
  return { cookies: entry.cookies, savedAt: entry.savedAt };
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

function controlResponse(state, group, role) {
  const command = commandFor(group.state, role);
  return {
    groupState: group.state,
    command,
    commandId: command === "NONE" ? null : group.commandId,
    policy: { ...state.policy, rotateOwner: group.groupId },
  };
}

function commandFor(groupState, role) {
  if (groupState === "ROTATING") return role === "boss" ? "ROTATE_GROUP_IP" : "PAUSE_FOR_ROTATION";
  if (groupState === "DRAINING") return "PAUSE_FOR_ROTATION";
  if (["PAUSED", "STOPPED", "ROTATION_FAILED"].includes(groupState)) return "STOP";
  return "NONE";
}

function getGroup(state, groupId) {
  if (!state.groups.has(groupId)) {
    state.groups.set(groupId, {
      groupId,
      state: "READY",
      commandId: `cmd_${crypto.randomUUID()}`,
      completedSinceRotation: 0,
      drainingStartedAt: null,
      devices: new Map(),
    });
  }
  return state.groups.get(groupId);
}

function taskLeaseResponse(product, lease) {
  return {
    taskLeaseId: lease.id,
    keyword: product.keyword,
    secondKeyword: product.secondKeyword || null,
    keywordName: product.keywordName || null,
    linkUrl: product.linkUrl,
    mid: product.mid || null,
    productTitle: product.productTitle || null,
  };
}

function leaseResponse(account, lease) {
  return {
    leaseId: lease.id,
    accountAlias: account.alias,
    loginId: account.loginId,
    password: account.password,
    expiresAt: new Date(lease.expiresAtMs).toISOString(),
  };
}

function loadProducts() {
  const localPath = process.env.PRODUCTS_FILE
    ? path.resolve(process.env.PRODUCTS_FILE)
    : path.join(__dirname, "products.local.json");
  if (!fs.existsSync(localPath)) return [];
  const parsed = JSON.parse(fs.readFileSync(localPath, "utf8"));
  return Array.isArray(parsed.products) ? parsed.products : [];
}

function loadAccounts() {
  const localPath = path.join(__dirname, "accounts.local.json");
  if (!fs.existsSync(localPath)) return [];
  const parsed = JSON.parse(fs.readFileSync(localPath, "utf8"));
  return Array.isArray(parsed.accounts) ? parsed.accounts : [];
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

function sendJson(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(text),
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
