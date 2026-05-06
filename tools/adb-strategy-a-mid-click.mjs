import { execFileSync } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";

const config = {
  serial: process.env.ADB_SERIAL || "172.30.1.68:35665",
  count: Number(process.env.LOOP_COUNT || 200),
  startIndex: Number(process.env.START_INDEX || 1),
  browserPackage: process.env.BROWSER_PACKAGE || "com.sec.android.app.sbrowser",
  devtoolsSocket: process.env.DEVTOOLS_SOCKET || "Terrace_devtools_remote",
  firstKeyword: process.env.FIRST_KEYWORD || "양꼬치 양갈비 양고기",
  secondKeyword: process.env.SECOND_KEYWORD || "최상급 어린양으로 만든 양꼬치 양갈비 양고기",
  mid: process.env.MID || "82095489871",
  devtoolsPort: Number(process.env.DEVTOOLS_PORT || 9223),
  afterFirstSearchMs: Number(process.env.AFTER_FIRST_SEARCH_MS || 13000),
  afterSecondSearchMs: Number(process.env.AFTER_SECOND_SEARCH_MS || 13000),
  afterProductClickMs: Number(process.env.AFTER_PRODUCT_CLICK_MS || 15000),
  detailSwipeDurationMs: Number(process.env.DETAIL_SWIPE_DURATION_MS || 2000),
  closeBrowserEachLoop: process.env.CLOSE_BROWSER_EACH_LOOP !== "0",
  retryMissingMid: Number(process.env.RETRY_MISSING_MID || 2),
};

function adb(args, options = {}) {
  return execFileSync("adb.exe", ["-s", config.serial, ...args], {
    encoding: "utf8",
    stdio: options.stdio || ["ignore", "pipe", "pipe"],
  });
}

function shell(command) {
  return adb(["shell", command]);
}

function searchUrl(query) {
  return `https://m.search.naver.com/search.naver?where=m&query=${encodeURIComponent(query)}`;
}

function openSamsungBrowser(url) {
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

async function connectPage(page) {
  let nextId = 1;
  const pending = new Map();
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  ws.onmessage = (event) => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      pending.get(message.id)(message);
      pending.delete(message.id);
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

async function findSecondSearchPage() {
  const pages = await listPages();
  return pages.find((page) =>
    page.type === "page" &&
    page.url.includes("m.search.naver.com/search.naver") &&
    decodeURIComponent(page.url).includes(config.secondKeyword)
  );
}

function getWebContentTop() {
  const xml = shell("uiautomator dump /sdcard/window.xml >/dev/null && cat /sdcard/window.xml");
  const match = xml.match(/resource-id="com\.sec\.android\.app\.sbrowser:id\/sbrowser_tab_holder"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  if (!match) return 230;
  return Number(match[2]);
}

async function findMidClickTarget(page) {
  const expression = `(() => {
    const mids = [${JSON.stringify(config.mid)}].filter(Boolean);
    const isAdAnchor = (anchor) => {
      const inventory = anchor.getAttribute("data-shp-inventory") ||
        anchor.closest("[data-shp-inventory]")?.getAttribute("data-shp-inventory") || "";
      return /lst\\*(A|P|D)/.test(inventory);
    };
    const directProductHref = (href, targetMid) =>
      href.includes("/products/" + targetMid) ||
      href.includes("smartstore.naver.com/main/products/" + targetMid) ||
      href.includes("m.smartstore.naver.com/main/products/" + targetMid);
    const trackedSearchHref = (href, targetMid) =>
      href.includes(targetMid) &&
      (href.includes("/p/crd/rd") || href.includes("cr.shopping") ||
        href.includes("cr2.shopping") || href.includes("cr3.shopping") ||
        href.includes("/bridge/searchGate") || href.includes("searchGate"));
    const scoreAnchor = (anchor) => {
      if (isAdAnchor(anchor)) return null;
      const href = anchor.href || anchor.getAttribute("href") || "";
      const contentId = anchor.getAttribute("data-shp-contents-id") || "";
      const labelledBy = anchor.getAttribute("aria-labelledby") || "";
      const dataset = JSON.stringify(anchor.dataset || {});
      for (const targetMid of mids) if (trackedSearchHref(href, targetMid)) return { score: 0, method: "tracked-search-gate" };
      for (const targetMid of mids) if (href.includes("nv_mid=" + targetMid)) return { score: 1, method: "nv_mid" };
      for (const targetMid of mids) if (href.includes("searchGate") && href.includes(targetMid)) return { score: 2, method: "searchGate" };
      for (const targetMid of mids) if (contentId === targetMid) return { score: 3, method: "data-shp-contents-id" };
      for (const targetMid of mids) if (labelledBy.includes("nstore_productId_" + targetMid)) return { score: 4, method: "aria-product-id" };
      for (const targetMid of mids) if (dataset.includes(targetMid)) return { score: 5, method: "data-attr-mid" };
      for (const targetMid of mids) if (directProductHref(href, targetMid)) return { score: 6, method: "direct-product" };
      return null;
    };
    const ranked = [...document.querySelectorAll("a")]
      .map((anchor, index) => {
        const scored = scoreAnchor(anchor);
        return scored ? { anchor, index, ...scored } : null;
      })
      .filter(Boolean)
      .sort((a, b) => a.score - b.score || a.index - b.index);
    if (!ranked.length) return { found: false, title: document.title, url: location.href };
    const picked = ranked[0];
    picked.anchor.scrollIntoView({ block: "center", inline: "center", behavior: "instant" });
    picked.anchor.removeAttribute("target");
    const r = picked.anchor.getBoundingClientRect();
    return {
      found: true,
      method: picked.method,
      href: (picked.anchor.href || picked.anchor.getAttribute("href") || "").slice(0, 240),
      dpr: devicePixelRatio,
      rect: { left: r.left, top: r.top, width: r.width, height: r.height, cx: r.left + r.width / 2, cy: r.top + r.height / 2 },
      title: document.title,
      url: location.href,
    };
  })()`;
  return evaluate(page, expression);
}

async function runOnce(index) {
  console.log(`[${index}/${config.count}] 1차 통합검색: ${config.firstKeyword}`);
  openSamsungBrowser(searchUrl(config.firstKeyword));
  await sleep(config.afterFirstSearchMs);

  console.log(`[${index}/${config.count}] 2차 통합검색: ${config.secondKeyword}`);
  openSamsungBrowser(searchUrl(config.secondKeyword));
  await sleep(config.afterSecondSearchMs);

  const page = await findSecondSearchPage();
  if (!page) throw new Error("2차 검색 DevTools page를 찾지 못했습니다.");

  let target = await findMidClickTarget(page);
  for (let retry = 1; !target?.found && retry <= config.retryMissingMid; retry += 1) {
    console.log(`[${index}/${config.count}] MID(${config.mid}) 미노출: 재검색 ${retry}/${config.retryMissingMid}`);
    openSamsungBrowser(searchUrl(config.secondKeyword));
    await sleep(config.afterSecondSearchMs + retry * 1500);
    const retryPage = await findSecondSearchPage();
    if (!retryPage) continue;
    target = await findMidClickTarget(retryPage);
  }
  if (!target?.found) {
    console.log(`[${index}/${config.count}] MID(${config.mid}) 상품 링크 미노출: 이번 회차 제외 후 계속`);
    return false;
  }

  const webTop = getWebContentTop();
  const x = Math.round(target.rect.cx * target.dpr);
  const y = Math.round(webTop + target.rect.cy * target.dpr);
  console.log(`[${index}/${config.count}] MID(${config.mid}) 발견: ${target.method}, tap=(${x},${y})`);
  shell(`input tap ${x} ${y}`);
  await sleep(3000);

  console.log(`[${index}/${config.count}] 상세페이지 터치 슬라이드: ${config.detailSwipeDurationMs}ms`);
  shell(`input swipe 540 1650 540 720 ${config.detailSwipeDurationMs}`);
  await sleep(Math.max(0, config.afterProductClickMs - 3000 - config.detailSwipeDurationMs));

  const pages = await listPages();
  const current = pages.find((item) => item.id === page.id) || pages[0];
  console.log(`[${index}/${config.count}] after click: ${current?.title || "-"} | ${current?.url || "-"}`);
  return true;
}

async function main() {
  console.log(`ADB Strategy A MID click runner: serial=${config.serial}, count=${config.count}, start=${config.startIndex}, mid=${config.mid}`);
  console.log(`초기 상태: ${config.browserPackage}에 Naver 로그인이 되어 있어야 합니다. 앱 데이터 삭제는 하지 않습니다.`);
  adb(["forward", `tcp:${config.devtoolsPort}`, `localabstract:${config.devtoolsSocket}`]);
  for (let i = config.startIndex; i <= config.count; i += 1) {
    await closeBrowser(i, "회차 시작 전 초기화");
    try {
      await runOnce(i);
    } finally {
      await closeBrowser(i, "회차 완료");
    }
  }
  console.log("complete");
}

main().catch((error) => {
  console.error(error?.stack || error);
  process.exit(1);
});
