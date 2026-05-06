const assert = require("node:assert/strict");
const test = require("node:test");
const { createServer, createState } = require("./dev-server");

test("leases an available account and releases it", async () => {
  const state = createState({
    accounts: [{ alias: "naver_a", loginId: "user", password: "secret", status: "available" }],
  });
  const baseUrl = await start(state);

  const lease = await post(baseUrl, "/android/accounts/lease", {
    deviceName: "z1-1",
    role: "soldier",
    strategy: "A",
    appVersion: "0.1.0",
  });

  assert.equal(lease.accountAlias, "naver_a");
  assert.equal(lease.loginId, "user");

  const released = await post(baseUrl, "/android/accounts/release", {
    leaseId: lease.leaseId,
    deviceName: "z1-1",
    reason: "task_complete",
  });

  assert.equal(released.ok, true);
});

test("drains before rotating while a soldier is running", async () => {
  const state = createState({ policy: { rotateEveryGroupTasks: 1, drainTimeoutSec: 120 } });
  const baseUrl = await start(state);

  await post(baseUrl, "/android/heartbeat", {
    deviceName: "z1-1",
    groupId: "z1",
    role: "soldier",
    state: "IDLE",
    taskCount: 0,
  });

  const running = await post(baseUrl, "/android/heartbeat", {
    deviceName: "z1-1",
    groupId: "z1",
    role: "soldier",
    state: "RUNNING_TASK",
    taskCount: 1,
  });

  assert.equal(running.groupState, "DRAINING");
  assert.equal(running.command, "PAUSE_FOR_ROTATION");

  const bossWhileRunning = await post(baseUrl, "/android/heartbeat", {
    deviceName: "z1",
    groupId: "z1",
    role: "boss",
    state: "IDLE",
    taskCount: 0,
  });

  assert.equal(bossWhileRunning.groupState, "DRAINING");
  assert.equal(bossWhileRunning.command, "PAUSE_FOR_ROTATION");

  const idle = await post(baseUrl, "/android/heartbeat", {
    deviceName: "z1-1",
    groupId: "z1",
    role: "soldier",
    state: "IDLE",
    taskCount: 1,
  });

  assert.equal(idle.groupState, "ROTATING");
  assert.equal(idle.command, "PAUSE_FOR_ROTATION");

  const boss = await post(baseUrl, "/android/heartbeat", {
    deviceName: "z1",
    groupId: "z1",
    role: "boss",
    state: "IDLE",
    taskCount: 0,
  });

  assert.equal(boss.groupState, "ROTATING");
  assert.equal(boss.command, "ROTATE_GROUP_IP");
});

test("leases a strategy A product task and reports it", async () => {
  const state = createState({
    products: [
      {
        keyword: "1차",
        secondKeyword: "2차",
        linkUrl: "https://smartstore.naver.com/sunsaem/products/83539482665",
        mid: "83539482665",
        productTitle: "차이팟",
        strategy: "A",
        status: "available",
      },
    ],
  });
  const baseUrl = await start(state);

  const lease = await post(baseUrl, "/android/tasks/lease", {
    deviceName: "z1-1",
    role: "soldier",
    strategy: "A",
    appVersion: "0.1.0",
  });

  assert.equal(lease.taskLeaseId.startsWith("task_"), true);
  assert.equal(lease.keyword, "1차");
  assert.equal(lease.secondKeyword, "2차");
  assert.equal(lease.mid, "83539482665");

  const report = await post(baseUrl, "/android/tasks/report", {
    taskLeaseId: lease.taskLeaseId,
    deviceName: "z1-1",
    result: "success",
  });

  assert.equal(report.ok, true);
});

test("maps existing zero claim-work product task into strategy A lease", async () => {
  const upstream = await startUpstream({
    claim: {
      traffic_id: 77,
      slot_id: 12,
      product_name: "프리미엄 블루투스 이어팟 차이팟 무선이어폰 충전케이스무료",
      nv_mid: "83539482665",
      short_keyword: "삼성 갤럭시 이어폰",
      target_url: "https://adpangshopping.co.kr/r/abc123",
    },
  });
  const state = createState({ zeroTrafficApiUrl: upstream.baseUrl });
  const baseUrl = await start(state);

  const lease = await post(baseUrl, "/android/tasks/lease", {
    deviceName: "z1-1",
    role: "soldier",
    strategy: "A",
    appVersion: "0.1.0",
  });

  assert.equal(lease.taskLeaseId, "zero_77");
  assert.equal(lease.keyword, "삼성 갤럭시 이어폰");
  assert.equal(lease.secondKeyword, "프리미엄 블루투스 이어팟 차이팟 무선이어폰 충전케이스무료");
  assert.equal(lease.mid, "83539482665");
  assert.equal(lease.linkUrl, "https://adpangshopping.co.kr/r/abc123");

  const report = await post(baseUrl, "/android/tasks/report", {
    taskLeaseId: lease.taskLeaseId,
    deviceName: "z1-1",
    result: "success",
  });

  assert.equal(report.ok, true);
  assert.equal(upstream.requests.at(-1).url, "/complete");
});

test("maps existing supabase traffic queue into strategy A lease", async () => {
  const upstream = await startSupabaseRest({
    trafficRows: [
      {
        id: 88,
        slot_id: 44,
        keyword: "삼성 갤럭시 이어폰",
        link_url: "https://smartstore.naver.com/sunsaem/products/83539482665",
      },
    ],
    slotRows: [
      {
        id: 44,
        mid: "83539482665",
        keyword_name: "프리미엄 블루투스 이어팟 차이팟 무선이어폰 충전케이스무료",
        success_count: 1,
        fail_count: 0,
      },
    ],
  });
  const state = createState({
    supabaseRestUrl: upstream.baseUrl,
    supabaseAnonKey: "test-key",
  });
  const baseUrl = await start(state);

  const lease = await post(baseUrl, "/android/tasks/lease", {
    deviceName: "z1-1",
    role: "soldier",
    strategy: "A",
    appVersion: "0.1.0",
  });

  assert.equal(lease.taskLeaseId, "sb_88");
  assert.equal(lease.keyword, "삼성 갤럭시 이어폰");
  assert.equal(lease.secondKeyword, "프리미엄 블루투스 이어팟 차이팟 무선이어폰 충전케이스무료");
  assert.equal(lease.mid, "83539482665");

  const report = await post(baseUrl, "/android/tasks/report", {
    taskLeaseId: lease.taskLeaseId,
    deviceName: "z1-1",
    result: "success",
  });

  assert.equal(report.ok, true);
  assert.equal(upstream.requests.some((item) => item.method === "DELETE" && item.url.startsWith("/traffic-navershopping-app")), true);
  assert.equal(upstream.requests.some((item) => item.method === "PATCH" && item.url.startsWith("/sellermate_slot_naver")), true);
});

async function start(state) {
  const server = createServer(state);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  test.after(() => server.close());
  const { port } = server.address();
  return `http://127.0.0.1:${port}`;
}

async function startUpstream({ claim }) {
  const requests = [];
  const server = require("node:http").createServer(async (req, res) => {
    requests.push({ url: req.url, method: req.method });
    if (req.url === "/claim-work") {
      return send(res, claim);
    }
    if (req.url === "/complete" || req.url === "/fail") {
      return send(res, { ok: true });
    }
    send(res, { error: "not_found" }, 404);
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  test.after(() => server.close());
  const { port } = server.address();
  return { baseUrl: `http://127.0.0.1:${port}`, requests };
}

function send(res, body, status = 200) {
  const text = JSON.stringify(body);
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(text);
}

async function startSupabaseRest({ trafficRows, slotRows }) {
  const requests = [];
  const server = require("node:http").createServer(async (req, res) => {
    requests.push({ url: req.url, method: req.method });
    if (req.method === "GET" && req.url.startsWith("/traffic-navershopping-app")) {
      return send(res, trafficRows);
    }
    if (req.method === "GET" && req.url.startsWith("/sellermate_slot_naver")) {
      return send(res, slotRows);
    }
    if (req.method === "DELETE" && req.url.startsWith("/traffic-navershopping-app")) {
      return send(res, []);
    }
    if (req.method === "PATCH" && req.url.startsWith("/sellermate_slot_naver")) {
      return send(res, []);
    }
    if (req.method === "POST" && req.url.startsWith("/slot_rank_naverapp_history")) {
      return send(res, {});
    }
    send(res, { error: "not_found" }, 404);
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  test.after(() => server.close());
  const { port } = server.address();
  return { baseUrl: `http://127.0.0.1:${port}`, requests };
}

async function post(baseUrl, path, body) {
  const response = await fetch(baseUrl + path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  assert.equal(response.status, 200);
  return response.json();
}
