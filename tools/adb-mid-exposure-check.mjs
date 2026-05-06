import { execFileSync } from "node:child_process";
import { writeFileSync } from "node:fs";
import { setTimeout as sleep } from "node:timers/promises";

const config = {
  serial: process.env.ADB_SERIAL,
  count: Number(process.env.LOOP_COUNT || 200),
  startIndex: Number(process.env.START_INDEX || 1),
  devtoolsPort: Number(process.env.DEVTOOLS_PORT || 9223),
  browserPackage: process.env.BROWSER_PACKAGE || "com.sec.android.app.sbrowser",
  browserActivity: process.env.BROWSER_ACTIVITY || "",
  devtoolsSocket: process.env.DEVTOOLS_SOCKET || "Terrace_devtools_remote",
  appOwnedLaunch: process.env.APP_OWNED_LAUNCH === "1",
  launcherActivity: process.env.LAUNCHER_ACTIVITY || "com.navertraffic.samsung/.strategy.SamsungBrowserLaunchActivity",
  launcherUrlExtra: process.env.LAUNCHER_URL_EXTRA || "com.navertraffic.samsung.extra.URL",
  firstKeyword: process.env.FIRST_KEYWORD || "전지가위",
  secondKeyword: process.env.SECOND_KEYWORD || "켈슨 충전 전동 전지가위 추천",
  mid: process.env.MID || "87327803739",
  afterFirstSearchMs: Number(process.env.AFTER_FIRST_SEARCH_MS || 4500),
  afterSecondSearchMs: Number(process.env.AFTER_SECOND_SEARCH_MS || 7000),
  afterProductClickMs: Number(process.env.AFTER_PRODUCT_CLICK_MS || 15000),
  detailSwipeDurationMs: Number(process.env.DETAIL_SWIPE_DURATION_MS || 2000),
  closeBrowserEachLoop: process.env.CLOSE_BROWSER_EACH_LOOP !== "0",
  retryMissingMid: Number(process.env.RETRY_MISSING_MID || 2),
  productUrl: process.env.PRODUCT_URL || "",
  productFallbackOnMissingMid: process.env.PRODUCT_FALLBACK_ON_MISSING_MID === "1",
  traceRedirects: process.env.TRACE_REDIRECTS === "1",
  traceAllNetwork: process.env.TRACE_ALL_NETWORK === "1",
  redirectTraceMs: Number(process.env.REDIRECT_TRACE_MS || 8000),
  redirectTraceIntervalMs: Number(process.env.REDIRECT_TRACE_INTERVAL_MS || 300),
  flowOutput: process.env.FLOW_OUTPUT || "",
  flowJsonOutput: process.env.FLOW_JSON_OUTPUT || "",
  requireTrackedFlow: process.env.REQUIRE_TRACKED_FLOW === "1",
};

if (!config.serial) {
  console.error("ADB_SERIAL is required");
  process.exit(2);
}

function adb(args) {
  return execFileSync("adb.exe", ["-s", config.serial, ...args], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
}

function shell(command) {
  return adb(["shell", command]);
}

function searchUrl(query) {
  return `https://m.search.naver.com/search.naver?where=m&query=${encodeURIComponent(query)}`;
}

function redactUrl(value) {
  if (!value) return value;
  let url;
  try {
    url = new URL(value);
  } catch {
    return value;
  }

  const sensitiveKeys = new Set([
    "NaPm",
    "nl-ts-pid",
    "token",
    "access_token",
    "refresh_token",
    "sid",
    "session",
    "ci",
    "uuid",
    "device_id",
    "gaid",
    "adid",
    "x",
    "t",
    "h",
    "ackey",
  ]);
  for (const key of [...url.searchParams.keys()]) {
    if (sensitiveKeys.has(key)) url.searchParams.set(key, "<redacted>");
  }
  return url.toString();
}

function classifyFlowUrl(value) {
  if (!value) return null;
  let url;
  try {
    url = new URL(value);
  } catch {
    return null;
  }
  const host = url.hostname;
  const path = url.pathname;
  if (host === "m.search.naver.com" && path === "/search.naver") return "search";
  if (host === "m.search.naver.com" && path === "/p/crd/rd") return "p-crd-rd";
  if (host === "cr2.shopping.naver.com" && path === "/adcr") return "cr2-adcr";
  if (host === "cr3.shopping.naver.com" && path === "/v2/bridge/searchGate") return "cr3-searchGate";
  if (host === "m.smartstore.naver.com" && path.includes("/products/")) return "smartstore-product";
  if (host === "m.smartstore.naver.com" && !path.startsWith("/i/")) return "smartstore";
  return null;
}

function flowSummary(events) {
  const kinds = new Set(events.map((event) => event.kind));
  return {
    hasSearch: kinds.has("search"),
    hasClickBeacon: kinds.has("p-crd-rd"),
    hasCr2: kinds.has("cr2-adcr"),
    hasCr3SearchGate: kinds.has("cr3-searchGate"),
    hasSmartStore: kinds.has("smartstore") || kinds.has("smartstore-product"),
    orderedKinds: events.map((event) => event.kind),
  };
}

function shouldRecordFlowEvent(source, title, kind) {
  if (!kind) return false;
  if (!kind.startsWith("smartstore")) return true;
  if (source === "page" || source === "fallback") return true;
  return title === "Document";
}

function recordFlowEvent(events, source, title, url) {
  const kind = classifyFlowUrl(url);
  if (!shouldRecordFlowEvent(source, title || "-", kind)) return;
  if (!kind) return;
  const redactedUrl = redactUrl(url);
  const key = `${source}|${kind}|${redactedUrl}`;
  if (events.some((event) => event.key === key)) return;
  events.push({
    key,
    at: new Date().toISOString(),
    source,
    kind,
    title: title || "-",
    url: redactedUrl,
  });
}

function writeFlowArtifacts(events, finalUrl = "") {
  if (!config.flowOutput && !config.flowJsonOutput) return;
  const summary = flowSummary(events);
  const payload = {
    serial: config.serial,
    browserPackage: config.browserPackage,
    appOwnedLaunch: config.appOwnedLaunch,
    mid: config.mid,
    firstKeyword: config.firstKeyword,
    secondKeyword: config.secondKeyword,
    finalUrl: redactUrl(finalUrl),
    summary,
    events: events.map(({ key, ...event }) => event),
  };

  if (config.flowJsonOutput) {
    writeFileSync(config.flowJsonOutput, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
  }
  if (config.flowOutput) {
    const lines = [
      `serial=${payload.serial}`,
      `browserPackage=${payload.browserPackage}`,
      `appOwnedLaunch=${payload.appOwnedLaunch}`,
      `mid=${payload.mid}`,
      `firstKeyword=${payload.firstKeyword}`,
      `secondKeyword=${payload.secondKeyword}`,
      `finalUrl=${payload.finalUrl || "-"}`,
      `hasSearch=${summary.hasSearch}`,
      `hasClickBeacon=${summary.hasClickBeacon}`,
      `hasCr2=${summary.hasCr2}`,
      `hasCr3SearchGate=${summary.hasCr3SearchGate}`,
      `hasSmartStore=${summary.hasSmartStore}`,
      `orderedKinds=${summary.orderedKinds.join(" -> ") || "-"}`,
      "",
      ...payload.events.map((event) => `${event.at} ${event.source} ${event.kind} | ${event.title} | ${event.url}`),
    ];
    writeFileSync(config.flowOutput, `${lines.join("\n")}\n`, "utf8");
  }
}

function assertTrackedFlow(events) {
  if (!config.requireTrackedFlow) return;
  const summary = flowSummary(events);
  const missing = [];
  if (!summary.hasSearch) missing.push("m.search.naver.com/search.naver");
  if (!summary.hasClickBeacon) missing.push("m.search.naver.com/p/crd/rd");
  if (!summary.hasCr2) missing.push("cr2.shopping.naver.com/adcr");
  if (!summary.hasCr3SearchGate) missing.push("cr3.shopping.naver.com/v2/bridge/searchGate");
  if (!summary.hasSmartStore) missing.push("m.smartstore.naver.com");
  if (missing.length > 0) {
    throw new Error(`tracked flow missing: ${missing.join(", ")}`);
  }
}

function openSamsungBrowser(url) {
  if (config.appOwnedLaunch) {
    shell(`am start -n ${config.launcherActivity} --es ${config.launcherUrlExtra} '${url}'`);
    return;
  }
  if (config.browserActivity) {
    shell(`am start -a android.intent.action.VIEW -d '${url}' -n ${config.browserActivity}`);
    return;
  }
  shell(`am start -a android.intent.action.VIEW -d '${url}' ${config.browserPackage}`);
}

async function closeBrowser(index, reason) {
  if (!config.closeBrowserEachLoop) return;
  console.log(`[${index}/${config.count}] Samsung Internet 종료 (${reason}, 로그인 쿠키 세션 유지)`);
  shell(`am force-stop ${config.browserPackage}`);
  await sleep(1200);
}

async function listPages() {
  const response = await fetch(`http://127.0.0.1:${config.devtoolsPort}/json`);
  if (!response.ok) throw new Error(`DevTools /json failed: ${response.status}`);
  return response.json();
}

async function waitForPages() {
  const deadline = Date.now() + 30000;
  let lastError;
  while (Date.now() < deadline) {
    try {
      return await listPages();
    } catch (error) {
      lastError = error;
      await sleep(500);
    }
  }
  throw lastError || new Error("DevTools not ready");
}

async function connectPage(page) {
  let nextId = 1;
  const pending = new Map();
  const eventHandlers = new Map();
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  ws.onmessage = (event) => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      pending.get(message.id)(message);
      pending.delete(message.id);
      return;
    }
    if (message.method && eventHandlers.has(message.method)) {
      for (const handler of eventHandlers.get(message.method)) handler(message.params || {});
    }
  };
  await new Promise((resolve, reject) => {
    ws.onopen = resolve;
    ws.onerror = reject;
  });
  return {
    send(method, params = {}) {
      const id = nextId++;
      ws.send(JSON.stringify({ id, method, params }));
      return new Promise((resolve) => pending.set(id, resolve));
    },
    close() {
      ws.close();
    },
    on(method, handler) {
      if (!eventHandlers.has(method)) eventHandlers.set(method, []);
      eventHandlers.get(method).push(handler);
    },
  };
}

async function evaluate(page, expression) {
  const client = await connectPage(page);
  try {
    const response = await client.send("Runtime.evaluate", {
      expression,
      returnByValue: true,
      awaitPromise: true,
    });
    const remote = response?.result?.result;
    if (remote?.subtype === "error") throw new Error(remote.description || "Runtime.evaluate error");
    return remote?.value;
  } finally {
    client.close();
  }
}

async function findSearchPage() {
  const decodedNeedle = config.secondKeyword;
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    const pages = await waitForPages();
    const page = pages.find((item) =>
      item.type === "page" &&
      item.url.includes("m.search.naver.com/search.naver") &&
      decodeURIComponent(item.url).includes(decodedNeedle)
    );
    if (page) return page;
    await sleep(500);
  }
  return null;
}

async function tracePageUrls(label, durationMs = config.redirectTraceMs, flowEvents = []) {
  if (!config.traceRedirects) return;
  const deadline = Date.now() + durationMs;
  const seen = new Set();
  while (Date.now() < deadline) {
    let pages = [];
    try {
      pages = await listPages();
    } catch (error) {
      console.log(`${label} trace unavailable: ${error.message}`);
      return;
    }
    for (const page of pages.filter((item) => item.type === "page" && item.url)) {
      const key = `${page.id}:${page.url}`;
      if (seen.has(key)) continue;
      seen.add(key);
      recordFlowEvent(flowEvents, "page", page.title || "-", page.url);
      console.log(`${label} url: ${page.title || "-"} | ${redactUrl(page.url)}`);
    }
    await sleep(config.redirectTraceIntervalMs);
  }
}

async function traceNetworkUrls(page, label, durationMs = config.redirectTraceMs, flowEvents = []) {
  if (!config.traceRedirects || !page?.webSocketDebuggerUrl) return;
  const client = await connectPage(page);
  const seen = new Set();
  const logUrl = (title, url) => {
    if (!url || seen.has(url)) return;
    seen.add(url);
    const kind = classifyFlowUrl(url);
    recordFlowEvent(flowEvents, "network", title || "-", url);
    if ((!kind || !shouldRecordFlowEvent("network", title || "-", kind)) && !config.traceAllNetwork) return;
    console.log(`${label} network: ${title || "-"} | ${redactUrl(url)}`);
  };
  try {
    client.on("Network.requestWillBeSent", (params) => {
      logUrl(params.type || "request", params.request?.url);
    });
    client.on("Network.responseReceived", (params) => {
      logUrl(`response ${params.response?.status || ""}`.trim(), params.response?.url);
    });
    await client.send("Network.enable");
    await sleep(durationMs);
  } catch (error) {
    console.log(`${label} network trace unavailable: ${error.message}`);
  } finally {
    client.close();
  }
}

function getWebContentTop() {
  const xml = shell("uiautomator dump /sdcard/window.xml >/dev/null && cat /sdcard/window.xml");
  const escapedPackage = config.browserPackage.replaceAll(".", "\\.");
  const match = xml.match(new RegExp(`resource-id="${escapedPackage}:id/sbrowser_tab_holder"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`));
  if (!match) return 230;
  return Number(match[2]);
}

async function checkMid(page) {
  const expression = `(() => {
    const mid = ${JSON.stringify(config.mid)};
    const anchors = [...document.querySelectorAll("a")];
    const matches = anchors.map((anchor, index) => {
      const href = anchor.href || anchor.getAttribute("href") || "";
      const contentId = anchor.getAttribute("data-shp-contents-id") || "";
      const labelledBy = anchor.getAttribute("aria-labelledby") || "";
      const dataset = JSON.stringify(anchor.dataset || {});
      const text = (anchor.innerText || anchor.textContent || "").replace(/\\s+/g, " ").trim();
      let method = "";
      if (href.includes(mid) && (href.includes("/p/crd/rd") || href.includes("cr.shopping") || href.includes("searchGate"))) method = "tracked-search-gate";
      else if (href.includes("nv_mid=" + mid)) method = "nv_mid";
      else if (contentId === mid) method = "data-shp-contents-id";
      else if (labelledBy.includes("nstore_productId_" + mid)) method = "aria-product-id";
      else if (dataset.includes(mid)) method = "data-attr-mid";
      else if (href.includes("/products/" + mid)) method = "direct-product";
      if (!method) return null;
      return { anchor, index, method, href, text: text.slice(0, 160) };
    }).filter(Boolean);
    if (matches[0]) {
      matches[0].anchor.scrollIntoView({ block: "center", inline: "center", behavior: "instant" });
      matches[0].anchor.removeAttribute("target");
      const r = matches[0].anchor.getBoundingClientRect();
      matches[0].rect = { left: r.left, top: r.top, width: r.width, height: r.height, cx: r.left + r.width / 2, cy: r.top + r.height / 2 };
      matches[0].dpr = devicePixelRatio;
      delete matches[0].anchor;
    }
    return {
      found: matches.length > 0,
      count: matches.length,
      title: document.title,
      url: location.href,
      first: matches[0] || null,
    };
  })()`;
  return evaluate(page, expression);
}

async function runOnce(index) {
  const flowEvents = [];
  console.log(`[${index}/${config.count}] 1차 통합검색: ${config.firstKeyword}`);
  const firstSearchUrl = searchUrl(config.firstKeyword);
  recordFlowEvent(flowEvents, "launch", "first-search", firstSearchUrl);
  openSamsungBrowser(firstSearchUrl);
  await sleep(config.afterFirstSearchMs);

  console.log(`[${index}/${config.count}] 2차 통합검색: ${config.secondKeyword}`);
  const secondSearchUrl = searchUrl(config.secondKeyword);
  recordFlowEvent(flowEvents, "launch", "second-search", secondSearchUrl);
  openSamsungBrowser(secondSearchUrl);
  await sleep(config.afterSecondSearchMs);

  const page = await findSearchPage();
  if (!page) throw new Error("2차 검색 페이지를 찾지 못했습니다.");

  let result = await checkMid(page);
  for (let retry = 1; !result?.found && retry <= config.retryMissingMid; retry += 1) {
    console.log(`[${index}/${config.count}] MID(${config.mid}) 미노출: 재검색 ${retry}/${config.retryMissingMid}`);
    openSamsungBrowser(searchUrl(config.secondKeyword));
    await sleep(config.afterSecondSearchMs + retry * 1500);
    const retryPage = await findSearchPage();
    if (!retryPage) continue;
    result = await checkMid(retryPage);
  }

  if (!result?.found) {
    if (config.productUrl && config.productFallbackOnMissingMid) {
      console.log(`[${index}/${config.count}] MID(${config.mid}) 미노출: 지정 상품 URL fallback`);
      recordFlowEvent(flowEvents, "fallback", "product", config.productUrl);
      openSamsungBrowser(config.productUrl);
      await sleep(5000);
      console.log(`[${index}/${config.count}] 상세페이지 터치 슬라이드: ${config.detailSwipeDurationMs}ms`);
      shell(`input swipe 540 1650 540 720 ${config.detailSwipeDurationMs}`);
      await sleep(Math.max(0, config.afterProductClickMs - 5000 - config.detailSwipeDurationMs));
      const fallbackPages = await listPages();
      const fallbackCurrent = fallbackPages.find((item) => item.url?.includes(config.productUrl)) ||
        fallbackPages.find((item) => item.url?.includes("smartstore.naver.com")) ||
        fallbackPages[0];
      console.log(`[${index}/${config.count}] after fallback: ${fallbackCurrent?.title || "-"} | ${redactUrl(fallbackCurrent?.url) || "-"}`);
      recordFlowEvent(flowEvents, "page", fallbackCurrent?.title || "-", fallbackCurrent?.url || "");
      writeFlowArtifacts(flowEvents, fallbackCurrent?.url || "");
      return true;
    }
    writeFlowArtifacts(flowEvents, "");
    console.log(`[${index}/${config.count}] MID(${config.mid}) 상품 링크 미노출: 이번 회차 제외 후 계속`);
    return false;
  }

  const target = result.first;
  recordFlowEvent(flowEvents, "matched-anchor", target.method, target.href);
  const webTop = getWebContentTop();
  const x = Math.round(target.rect.cx * target.dpr);
  const y = Math.round(webTop + target.rect.cy * target.dpr);
  console.log(`[${index}/${config.count}] MID(${config.mid}) 발견: ${target.method}, tap=(${x},${y})`);
  const networkTrace = traceNetworkUrls(page, `[${index}/${config.count}] redirect`, config.redirectTraceMs, flowEvents);
  shell(`input tap ${x} ${y}`);
  const pageTrace = tracePageUrls(`[${index}/${config.count}] redirect`, config.redirectTraceMs, flowEvents);
  await sleep(3000);

  let pages = await listPages();
  let current = pages.find((item) => item.id === page.id) || pages[0];
  if (current?.url?.includes("m.search.naver.com/search.naver") && config.productUrl) {
    console.log(`[${index}/${config.count}] 검색 URL 유지: 지정 상품 URL fallback`);
    recordFlowEvent(flowEvents, "fallback", "product", config.productUrl);
    openSamsungBrowser(config.productUrl);
    await sleep(5000);
  }

  console.log(`[${index}/${config.count}] 상세페이지 터치 슬라이드: ${config.detailSwipeDurationMs}ms`);
  shell(`input swipe 540 1650 540 720 ${config.detailSwipeDurationMs}`);
  await sleep(Math.max(0, config.afterProductClickMs - 3000 - config.detailSwipeDurationMs));
  await Promise.all([networkTrace, pageTrace]);

  pages = await listPages();
  current = pages.find((item) => item.url?.includes("smartstore.naver.com")) ||
    pages.find((item) => item.id === page.id) ||
    pages[0];
  recordFlowEvent(flowEvents, "page", current?.title || "-", current?.url || "");
  writeFlowArtifacts(flowEvents, current?.url || "");
  assertTrackedFlow(flowEvents);
  console.log(`[${index}/${config.count}] after click: ${current?.title || "-"} | ${redactUrl(current?.url) || "-"}`);
  return true;
}

async function main() {
  const model = shell("getprop ro.product.model").trim();
  console.log(`MID exposure touch runner: serial=${config.serial}, model=${model}, count=${config.count}, start=${config.startIndex}, mid=${config.mid}`);
  console.log(`초기 상태: ${config.browserPackage}에 Naver 로그인이 되어 있어야 합니다. 앱 데이터 삭제는 하지 않습니다.`);
  console.log(`firstKeyword=${config.firstKeyword}`);
  console.log(`secondKeyword=${config.secondKeyword}`);
  console.log(`traceRedirects=${config.traceRedirects}`);
  console.log(`traceAllNetwork=${config.traceAllNetwork}`);
  console.log(`appOwnedLaunch=${config.appOwnedLaunch}`);
  if (config.flowOutput) console.log(`flowOutput=${config.flowOutput}`);
  if (config.flowJsonOutput) console.log(`flowJsonOutput=${config.flowJsonOutput}`);
  console.log(`requireTrackedFlow=${config.requireTrackedFlow}`);
  adb(["forward", `tcp:${config.devtoolsPort}`, `localabstract:${config.devtoolsSocket}`]);

  let successCount = 0;
  for (let i = config.startIndex; i <= config.count; i += 1) {
    await closeBrowser(i, "회차 시작 전 초기화");
    try {
      if (await runOnce(i)) successCount += 1;
    } finally {
      await closeBrowser(i, "회차 완료");
    }
  }
  console.log(`complete: success=${successCount}/${config.count - config.startIndex + 1}`);
}

main().catch((error) => {
  console.error(error?.stack || error);
  process.exit(1);
});
