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

test("serves admin snapshot with group and device status", async () => {
  const state = createState({
    accounts: [{ alias: "naver_a", loginId: "user", password: "secret", status: "available" }],
  });
  const baseUrl = await start(state);

  await post(baseUrl, "/android/heartbeat", {
    deviceName: "z1-1",
    groupId: "z1",
    role: "soldier",
    state: "IDLE",
    taskCount: 3,
    currentIp: "127.0.0.1",
  });

  const snapshot = await get(baseUrl, "/admin/api/snapshot");

  assert.equal(snapshot.summary.devices, 1);
  assert.equal(snapshot.summary.accounts, 1);
  assert.equal(snapshot.groups[0].groupId, "z1");
  assert.equal(snapshot.devices[0].deviceName, "z1-1");
  assert.equal(snapshot.devices[0].currentIp, "127.0.0.1");
});

test("adds admin account in memory and leases it to a device", async () => {
  const state = createState({ accounts: [] });
  const baseUrl = await start(state);

  const added = await post(baseUrl, "/admin/api/accounts", {
    alias: "naver_web",
    loginId: "web-user",
    password: "web-secret",
    groupId: "z1",
    assignedDeviceName: "z1-1",
  });

  assert.equal(added.ok, true);
  assert.equal(added.persisted, false);
  assert.equal(added.account.alias, "naver_web");
  assert.equal(added.account.assignedDeviceName, "z1-1");

  const lease = await post(baseUrl, "/android/accounts/lease", {
    deviceName: "z1-1",
    role: "soldier",
    strategy: "A",
    appVersion: "0.1.0",
  });

  assert.equal(lease.accountAlias, "naver_web");
  assert.equal(lease.loginId, "web-user");
  assert.equal(lease.password, "web-secret");
});

test("prefers exact device account assignment over group pool accounts", async () => {
  const state = createState({
    accounts: [
      { alias: "group_pool", loginId: "group-user", password: "group-secret", status: "available", groupId: "z1" },
      { alias: "device_exact", loginId: "device-user", password: "device-secret", status: "available", assignedDeviceName: "z1-1" },
    ],
  });
  const baseUrl = await start(state);

  const lease = await post(baseUrl, "/android/accounts/lease", {
    deviceName: "z1-1",
    role: "soldier",
    strategy: "G",
    appVersion: "0.1.0",
  });

  assert.equal(lease.accountAlias, "device_exact");
  assert.equal(lease.loginId, "device-user");
});

test("leases group account when device has no exact assignment", async () => {
  const state = createState({
    accounts: [
      { alias: "z1_pool", loginId: "group-user", password: "group-secret", status: "available", groupId: "z1" },
    ],
  });
  const baseUrl = await start(state);

  const lease = await post(baseUrl, "/android/accounts/lease", {
    deviceName: "z1-2",
    role: "soldier",
    strategy: "G",
    appVersion: "0.1.0",
  });

  assert.equal(lease.accountAlias, "z1_pool");
  assert.equal(lease.loginId, "group-user");
});

test("stores reusable cookies per device and account alias", async () => {
  const state = createState();
  const baseUrl = await start(state);

  await post(baseUrl, "/android/cookies/save", {
    deviceName: "z1-1",
    accountAlias: "naver_a",
    cookies: "NID_AUT=aaa; NID_SES=111",
  });
  await post(baseUrl, "/android/cookies/save", {
    deviceName: "z1-1",
    accountAlias: "naver_b",
    cookies: "NID_AUT=bbb; NID_SES=222",
  });

  const first = await post(baseUrl, "/android/cookies/load", {
    deviceName: "z1-1",
    accountAlias: "naver_a",
  });
  const second = await post(baseUrl, "/android/cookies/load", {
    deviceName: "z1-1",
    accountAlias: "naver_b",
  });
  const missing = await post(baseUrl, "/android/cookies/load", {
    deviceName: "z1-1",
    accountAlias: "naver_c",
  });

  assert.equal(first.cookies, "NID_AUT=aaa; NID_SES=111");
  assert.equal(first.accountAlias, "naver_a");
  assert.equal(second.cookies, "NID_AUT=bbb; NID_SES=222");
  assert.equal(second.accountAlias, "naver_b");
  assert.equal(missing.cookies, null);
});

test("surfaces login protection reports on the matching device", async () => {
  const state = createState({
    accounts: [{ alias: "naver_a", loginId: "user", password: "secret", status: "available" }],
  });
  const baseUrl = await start(state);

  await post(baseUrl, "/android/heartbeat", {
    deviceName: "z1-1",
    groupId: "z1",
    role: "soldier",
    state: "IDLE",
    taskCount: 0,
  });
  const lease = await post(baseUrl, "/android/accounts/lease", {
    deviceName: "z1-1",
    role: "soldier",
    strategy: "A",
    appVersion: "0.1.0",
  });
  await post(baseUrl, "/android/accounts/report", {
    leaseId: lease.leaseId,
    deviceName: "z1-1",
    result: "protected",
    signals: ["PHONE_VERIFICATION_REQUIRED"],
    message: "temporary protection",
  });

  const snapshot = await get(baseUrl, "/admin/api/snapshot");

  assert.equal(snapshot.summary.protectedDevices, 1);
  assert.equal(snapshot.devices[0].deviceName, "z1-1");
  assert.equal(snapshot.devices[0].state, "ERROR");
  assert.equal(snapshot.devices[0].accountAlert.active, true);
  assert.equal(snapshot.devices[0].accountAlert.signals[0], "PHONE_VERIFICATION_REQUIRED");
});

test("protects admin API with token and emits allowed CORS headers", async () => {
  const state = createState({
    adminApiToken: "admin-secret",
    adminAllowedOrigins: ["https://example.vercel.app"],
  });
  const baseUrl = await start(state);

  const unauthorized = await rawGet(baseUrl, "/admin/api/snapshot", {
    Origin: "https://example.vercel.app",
  });
  assert.equal(unauthorized.status, 401);
  assert.equal(unauthorized.headers.get("access-control-allow-origin"), "https://example.vercel.app");

  const authorized = await rawGet(baseUrl, "/admin/api/snapshot", {
    Origin: "https://example.vercel.app",
    "X-Admin-Token": "admin-secret",
  });
  assert.equal(authorized.status, 200);
  assert.equal(authorized.headers.get("access-control-allow-origin"), "https://example.vercel.app");

  const preflight = await fetch(baseUrl + "/admin/api/snapshot", {
    method: "OPTIONS",
    headers: {
      Origin: "https://example.vercel.app",
      "Access-Control-Request-Method": "GET",
    },
  });
  assert.equal(preflight.status, 204);
  assert.equal(preflight.headers.get("access-control-allow-origin"), "https://example.vercel.app");
});

test("omits admin API CORS headers for a disallowed origin", async () => {
  const state = createState({
    adminApiToken: "admin-secret",
    adminAllowedOrigins: ["https://example.vercel.app"],
  });
  const baseUrl = await start(state);

  const response = await rawGet(baseUrl, "/admin/api/snapshot", {
    Origin: "https://evil.example",
    "X-Admin-Token": "admin-secret",
  });

  assert.equal(response.status, 200);
  assert.equal(response.headers.get("access-control-allow-origin"), null);
});

test("serves latest app release for Vercel-style Android update checks", async () => {
  const state = createState({
    androidReleaseRepo: "",
    appReleases: [
      { versionCode: 2, versionName: "0.2.0", apkUrl: "https://example.com/app-v2.apk", enabled: true },
      { versionCode: 3, versionName: "0.3.0", apkUrl: "https://example.com/app-v3.apk", enabled: false },
      { versionCode: 1, versionName: "0.1.0", apkUrl: "https://example.com/app-v1.apk", enabled: true },
    ],
  });
  const baseUrl = await start(state);

  const release = await get(baseUrl, "/android/app-release/latest");

  assert.equal(release.version_code, 2);
  assert.equal(release.version_name, "0.2.0");
  assert.equal(release.apk_url, "https://example.com/app-v2.apk");
});

test("serves newer GitHub Android release when metadata table is stale", async () => {
  const github = await startGitHubReleases([
    {
      tag_name: "android-0.1.34",
      name: "Android 0.1.34",
      draft: false,
      prerelease: false,
      assets: [
        {
          name: "naver-traffic-samsung-0.1.34-v35-release.apk",
          browser_download_url: "https://github.com/example/releases/download/android-0.1.34/app.apk",
          digest: "sha256:abc123",
        },
      ],
    },
  ]);
  const state = createState({
    githubReleaseApiUrl: `${github.baseUrl}/releases`,
    appReleases: [
      { versionCode: 34, versionName: "0.1.33", apkUrl: "https://example.com/app-v34.apk", enabled: true },
    ],
  });
  const baseUrl = await start(state);

  const release = await get(baseUrl, "/android/app-release/latest");

  assert.equal(release.version_code, 35);
  assert.equal(release.version_name, "0.1.34");
  assert.equal(release.apk_url, "https://github.com/example/releases/download/android-0.1.34/app.apk");
  assert.equal(release.sha256, "abc123");
});

test("admin group update command is delivered through heartbeat and reportable", async () => {
  const state = createState();
  const baseUrl = await start(state);

  await post(baseUrl, "/android/heartbeat", {
    deviceName: "z1-1",
    groupId: "z1",
    role: "soldier",
    state: "IDLE",
    taskCount: 0,
    appVersion: "0.1.24",
    versionCode: 25,
  });

  const requested = await post(baseUrl, "/admin/api/groups/command", {
    groupId: "z1",
    command: "UPDATE_APP",
  });
  assert.equal(requested.ok, true);
  assert.equal(requested.command, "UPDATE_APP");

  const heartbeat = await post(baseUrl, "/android/heartbeat", {
    deviceName: "z1-1",
    groupId: "z1",
    role: "soldier",
    state: "IDLE",
    taskCount: 0,
    appVersion: "0.1.24",
    versionCode: 25,
  });
  assert.equal(heartbeat.deviceCommand.type, "UPDATE_APP");
  assert.equal(heartbeat.deviceCommand.commandId, requested.commandId);

  const report = await post(baseUrl, "/android/commands/report", {
    commandId: requested.commandId,
    deviceName: "z1-1",
    groupId: "z1",
    command: "UPDATE_APP",
    success: true,
    message: "already latest: 25",
  });
  assert.equal(report.ok, true);

  const afterReport = await post(baseUrl, "/android/heartbeat", {
    deviceName: "z1-1",
    groupId: "z1",
    role: "soldier",
    state: "IDLE",
    taskCount: 0,
    appVersion: "0.1.24",
    versionCode: 25,
  });
  assert.equal(afterReport.deviceCommand, null);

  const snapshot = await get(baseUrl, "/admin/api/snapshot");
  assert.equal(snapshot.groups[0].deviceCommand.type, "UPDATE_APP");
  assert.equal(snapshot.groups[0].deviceCommand.reportedCount, 1);
  assert.equal(snapshot.deviceCommandReports[0].message, "already latest: 25");
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
        keyword_name: "프리미엄 블루투스 이어팟 차이팟 무선이어폰 충전케이스무료",
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

  assert.equal(lease.taskLeaseId, "sb_88_44");
  assert.equal(lease.keyword, "삼성 갤럭시 이어폰");
  assert.equal(lease.secondKeyword, "프리미엄 블루투스 이어팟 차이팟 무선이어폰 충전케이스무료");
  assert.equal(lease.mid, "83539482665");

  const report = await post(baseUrl, "/android/tasks/report", {
    taskLeaseId: lease.taskLeaseId,
    deviceName: "z1-1",
    result: "success",
  });

  assert.equal(report.ok, true);
  assert.equal(upstream.requests.some((item) => item.method === "DELETE" && item.url.startsWith("/sellermate_traffic_navershopping")), true);
  assert.equal(upstream.requests.some((item) => item.method === "PATCH" && item.url.startsWith("/sellermate_slot_naver")), true);
});

test("supabase RPC lease claims a strategy A task without deleting queue rows", async () => {
  const upstream = await startSupabaseRest({
    rpcClaim: {
      task_id: 188,
      lease_id: "lease-188",
      slot_id: 144,
      idempotency_key: "idem-188",
      keyword: "삼성 갤럭시 이어폰",
      keyword_name: "프리미엄 블루투스 이어팟 차이팟 무선이어폰 충전케이스무료",
      link_url: "https://smartstore.naver.com/sunsaem/products/83539482665",
      mid: "83539482665",
      product_title: "차이팟",
    },
  });
  const state = createState({
    supabaseRestUrl: upstream.baseUrl,
    supabaseAnonKey: "test-key",
    supabaseTaskRpcEnabled: true,
  });
  const baseUrl = await start(state);

  const lease = await post(baseUrl, "/android/tasks/lease", {
    deviceName: "z1-1",
    role: "soldier",
    strategy: "A",
    appVersion: "0.1.0",
  });

  assert.equal(lease.taskLeaseId, "sb_lease-188");
  assert.equal(lease.keyword, "삼성 갤럭시 이어폰");
  assert.equal(lease.secondKeyword, "프리미엄 블루투스 이어팟 차이팟 무선이어폰 충전케이스무료");
  assert.equal(lease.mid, "83539482665");
  assert.equal(upstream.requests.some((item) => item.method === "POST" && item.url === "/rpc/claim_android_naver_task"), true);
  assert.equal(upstream.requests.some((item) => item.method === "DELETE" && item.url.startsWith("/sellermate_traffic_navershopping")), false);

  const snapshot = await get(baseUrl, "/admin/api/snapshot");
  assert.equal(snapshot.taskLeases[0].taskId, 188);
  assert.equal(snapshot.taskLeases[0].leaseId, "lease-188");
  assert.equal(snapshot.taskLeases[0].slotId, 144);
  assert.equal(snapshot.taskLeases[0].idempotencyKey, "idem-188");
  assert.equal(snapshot.taskLeases[0].linkUrl, "https://smartstore.naver.com/sunsaem/products/83539482665");
});

test("supabase RPC report calls report RPC without slot patching on duplicate reports", async () => {
  const upstream = await startSupabaseRest({
    rpcClaim: {
      task_id: 288,
      lease_id: "lease-288",
      slot_id: 244,
      idempotency_key: "idem-288",
      keyword: "삼성 갤럭시 이어폰",
      keyword_name: "프리미엄 블루투스 이어팟 차이팟 무선이어폰 충전케이스무료",
      link_url: "https://smartstore.naver.com/sunsaem/products/83539482665",
      mid: "83539482665",
    },
    rpcReport: { ok: true },
  });
  const state = createState({
    supabaseRestUrl: upstream.baseUrl,
    supabaseAnonKey: "test-key",
    supabaseTaskRpcEnabled: true,
  });
  const baseUrl = await start(state);

  const lease = await post(baseUrl, "/android/tasks/lease", {
    deviceName: "z1-1",
    role: "soldier",
    strategy: "A",
    appVersion: "0.1.0",
  });
  const firstReport = await post(baseUrl, "/android/tasks/report", {
    taskLeaseId: lease.taskLeaseId,
    deviceName: "z1-1",
    result: "success",
  });
  const duplicateReport = await post(baseUrl, "/android/tasks/report", {
    taskLeaseId: lease.taskLeaseId,
    deviceName: "z1-1",
    result: "success",
  });

  assert.equal(firstReport.ok, true);
  assert.equal(duplicateReport.ok, true);
  const reportRequests = upstream.requests.filter((item) => item.method === "POST" && item.url === "/rpc/report_android_naver_task");
  assert.equal(reportRequests.length, 2);
  assert.deepEqual(reportRequests[0].body, {
    p_task_id: 288,
    p_lease_id: "lease-288",
    p_device_name: "z1-1",
    p_success: true,
    p_link_url: "https://smartstore.naver.com/sunsaem/products/83539482665",
    p_source: "android-samsung-login-A",
    p_idempotency_key: "idem-288",
  });
  assert.equal(upstream.requests.some((item) => item.method === "PATCH" && item.url.startsWith("/sellermate_slot_naver")), false);
});

test("maps supabase traffic queue into strategy G without strategy query filter", async () => {
  const upstream = await startSupabaseRest({
    trafficRows: [
      {
        id: 89,
        slot_id: 45,
        keyword: "나이키 운동화",
        keyword_name: "나이키 에어맥스 운동화 스니커즈",
        product_name: "나이키 에어맥스 270 운동화 남성 여성 스니커즈",
        catalog_mid: "catalog-123",
        link_url: "https://smartstore.naver.com/sunsaem/products/123456789",
      },
    ],
    slotRows: [
      {
        id: 45,
        mid: "123456789",
        keyword_name: "나이키 에어맥스 운동화 스니커즈",
        success_count: 0,
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
    strategy: "G",
    appVersion: "0.1.0",
  });

  assert.equal(lease.taskLeaseId, "sb_89_45");
  assert.equal(lease.keyword, "나이키 운동화");
  assert.equal(lease.keywordName, "나이키 에어맥스 운동화 스니커즈");
  assert.equal(lease.productName, "나이키 에어맥스 270 운동화 남성 여성 스니커즈");
  assert.equal(lease.catalogMid, "catalog-123");
  assert.equal(lease.secondKeyword, null);
  assert.equal(lease.mid, "123456789");
  assert.equal(
    upstream.requests.some(
      (item) =>
        item.method === "GET" &&
        item.url.startsWith("/sellermate_traffic_navershopping") &&
        item.url.includes("strategy="),
    ),
    false,
  );
});

test("defaults omitted task strategy to strategy G", async () => {
  const upstream = await startSupabaseRest({
    trafficRows: [
      {
        id: 90,
        slot_id: 46,
        keyword: "뉴발란스 운동화",
        keyword_name: "뉴발란스 574 스니커즈",
        link_url: "https://smartstore.naver.com/sunsaem/products/987654321",
      },
    ],
    slotRows: [
      {
        id: 46,
        mid: "987654321",
        keyword_name: "뉴발란스 574 스니커즈",
        success_count: 0,
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
    deviceName: "z1-2",
    role: "soldier",
    appVersion: "0.1.0",
  });

  assert.equal(lease.taskLeaseId, "sb_90_46");
  assert.equal(lease.keyword, "뉴발란스 운동화");
  assert.equal(lease.keywordName, "뉴발란스 574 스니커즈");
  assert.equal(lease.secondKeyword, null);
  assert.equal(lease.mid, "987654321");
});

test("bounds task leases and evicts stale heartbeat devices", async () => {
  const products = Array.from({ length: 505 }, (_, index) => ({
    keyword: `keyword-${index}`,
    secondKeyword: `second-${index}`,
    linkUrl: `https://example.com/products/${index}`,
    mid: String(index),
    productTitle: `product-${index}`,
    strategy: "A",
    status: "available",
  }));
  const state = createState({ products });
  const baseUrl = await start(state);

  for (let index = 0; index < products.length; index += 1) {
    const lease = await post(baseUrl, "/android/tasks/lease", {
      deviceName: `z1-${index}`,
      role: "soldier",
      strategy: "A",
      appVersion: "0.1.0",
    });
    assert.equal(lease.taskLeaseId.startsWith("task_"), true);
  }
  assert.equal(state.taskLeases.size, 500);
  const snapshot = await get(baseUrl, "/admin/api/snapshot");
  assert.equal(snapshot.taskLeases.length, 50);

  await post(baseUrl, "/android/heartbeat", {
    deviceName: "z1-1",
    groupId: "z1",
    role: "soldier",
    state: "IDLE",
    taskCount: 0,
  });
  state.groups.get("z1").devices.get("z1-1").updatedAt = Date.now() - 31_000;
  const offlineSnapshot = await get(baseUrl, "/admin/api/snapshot");
  assert.equal(offlineSnapshot.devices.find((device) => device.deviceName === "z1-1").online, false);

  state.groups.get("z1").devices.get("z1-1").updatedAt = Date.now() - 11 * 60_000;
  const evictedSnapshot = await get(baseUrl, "/admin/api/snapshot");
  assert.equal(evictedSnapshot.devices.some((device) => device.deviceName === "z1-1"), false);
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

async function startSupabaseRest({ trafficRows = [], slotRows = [], rpcClaim = null, rpcReport = { ok: true } }) {
  const requests = [];
  const server = require("node:http").createServer(async (req, res) => {
    const body = await readRequestBody(req);
    requests.push({ url: req.url, method: req.method, body });
    if (req.method === "POST" && req.url === "/rpc/claim_android_naver_task") {
      return send(res, rpcClaim);
    }
    if (req.method === "POST" && req.url === "/rpc/report_android_naver_task") {
      return send(res, rpcReport);
    }
    if (req.method === "GET" && req.url.startsWith("/sellermate_traffic_navershopping")) {
      return send(res, trafficRows);
    }
    if (req.method === "GET" && req.url.startsWith("/sellermate_slot_naver")) {
      return send(res, slotRows);
    }
    if (req.method === "DELETE" && req.url.startsWith("/sellermate_traffic_navershopping")) {
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

async function startGitHubReleases(releases) {
  const server = require("node:http").createServer(async (req, res) => {
    if (req.method === "GET" && req.url === "/releases") {
      return send(res, releases);
    }
    send(res, { error: "not_found" }, 404);
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  test.after(() => server.close());
  const { port } = server.address();
  return { baseUrl: `http://127.0.0.1:${port}` };
}

function readRequestBody(req) {
  return new Promise((resolve) => {
    let raw = "";
    req.on("data", (chunk) => {
      raw += chunk;
    });
    req.on("end", () => {
      resolve(raw ? JSON.parse(raw) : null);
    });
  });
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

async function get(baseUrl, path) {
  const response = await fetch(baseUrl + path);
  assert.equal(response.status, 200);
  return response.json();
}

async function rawGet(baseUrl, path, headers = {}) {
  return fetch(baseUrl + path, { headers });
}
