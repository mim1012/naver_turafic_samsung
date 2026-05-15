/**
 * adb-naver-login-cdp.mjs
 *
 * CDP-based Naver login for Samsung Internet on S7 (SM-G930S).
 * - Dynamically resolves Samsung Internet PID → localabstract socket
 * - Overrides UA to Chrome 136 + full userAgentMetadata (Sec-CH-UA consistent)
 * - Reads credentials from .env.local (NAVER_ID / NAVER_PW) or env vars
 * - Verifies NID_AUT + NID_SES cookies after submit
 *
 * Usage:
 *   ADB_SERIAL=ce12160c9966340705 node tools/adb-naver-login-cdp.mjs
 */

import fs from "node:fs";
import path from "node:path";
import { execFileSync, execSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { setTimeout as sleep } from "node:timers/promises";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");

// ── env loader ────────────────────────────────────────────────────────────────
function loadEnv() {
  const envPath = path.join(ROOT, ".env.local");
  if (!fs.existsSync(envPath)) return;
  for (const line of fs.readFileSync(envPath, "utf8").split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    const eq = trimmed.indexOf("=");
    if (eq < 0) continue;
    const key = trimmed.slice(0, eq).trim();
    const val = trimmed.slice(eq + 1).trim();
    if (!(key in process.env)) process.env[key] = val;
  }
}
loadEnv();

// ── config ────────────────────────────────────────────────────────────────────
const SERIAL = process.env.ADB_SERIAL || "ce12160c9966340705";
const DEVTOOLS_PORT = Number(process.env.DEVTOOLS_PORT || 9224);
const BROWSER_PKG = "com.sec.android.app.sbrowser";
const LOGIN_URL = "https://nid.naver.com/nidlogin.login?mode=form&url=https://www.naver.com/";

const NAVER_ID = process.env.NAVER_ID;
const NAVER_PW = process.env.NAVER_PW;
if (!NAVER_ID || !NAVER_PW) {
  console.error("[cdp-login] NAVER_ID / NAVER_PW not set — add to .env.local");
  process.exit(1);
}

// Chrome 136 on SM-G930S / Android 8.0.0 — must be internally consistent
const UA_OVERRIDE = "Mozilla/5.0 (Linux; Android 8.0.0; SM-G930S) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.7103.127 Mobile Safari/537.36";
const UA_METADATA = {
  brands: [
    { brand: "Chromium", version: "136" },
    { brand: "Google Chrome", version: "136" },
    { brand: "Not-A.Brand", version: "99" },
  ],
  fullVersionList: [
    { brand: "Chromium", version: "136.0.7103.127" },
    { brand: "Google Chrome", version: "136.0.7103.127" },
    { brand: "Not-A.Brand", version: "99.0.0.0" },
  ],
  platform: "Android",
  platformVersion: "8.0.0",
  architecture: "",
  model: "SM-G930S",
  mobile: true,
  bitness: "64",
  wow64: false,
};

// ── adb helpers ───────────────────────────────────────────────────────────────
function adb(args, opts = {}) {
  return execFileSync("adb.exe", ["-s", SERIAL, ...args], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
    env: { ...process.env, MSYS_NO_PATHCONV: "1" },
    ...opts,
  }).trim();
}

function shell(cmd) {
  return adb(["shell", cmd]);
}

function getBrowserPid() {
  try {
    const out = shell(`pidof ${BROWSER_PKG}`);
    const pid = out.trim().split(/\s+/)[0];
    if (pid && /^\d+$/.test(pid)) return pid;
  } catch (_) {}
  // fallback: ps grep
  try {
    const ps = shell(`ps | grep ${BROWSER_PKG}`);
    const m = ps.match(/\S+\s+(\d+)/);
    if (m) return m[1];
  } catch (_) {}
  return null;
}

function openBrowser(url) {
  shell(`am start -a android.intent.action.VIEW -d '${url}' ${BROWSER_PKG}`);
}

function forwardDevTools(pid) {
  const socket = `webview_devtools_remote_${pid}`;
  adb(["forward", `tcp:${DEVTOOLS_PORT}`, `localabstract:${socket}`]);
  console.log(`[cdp-login] forwarded @${socket} → tcp:${DEVTOOLS_PORT}`);
}

// ── CDP page list ─────────────────────────────────────────────────────────────
async function listPages() {
  const res = await fetch(`http://127.0.0.1:${DEVTOOLS_PORT}/json`);
  if (!res.ok) throw new Error(`DevTools /json ${res.status}`);
  return res.json();
}

async function waitDevTools(timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    try { return await listPages(); } catch (e) { last = e; await sleep(600); }
  }
  throw last || new Error("DevTools did not become ready");
}

// ── WebSocket CDP client ──────────────────────────────────────────────────────
async function connectPage(page) {
  let nextId = 1;
  const pending = new Map();
  const events = new Map();
  const ws = new WebSocket(page.webSocketDebuggerUrl);

  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id != null && pending.has(msg.id)) {
      const cb = pending.get(msg.id);
      pending.delete(msg.id);
      cb(msg);
    }
    if (msg.method) {
      const listeners = events.get(msg.method) || [];
      listeners.forEach((fn) => fn(msg.params));
    }
  };

  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

  return {
    send(method, params = {}) {
      const id = nextId++;
      ws.send(JSON.stringify({ id, method, params }));
      return new Promise((res) => pending.set(id, res));
    },
    on(eventName, fn) {
      if (!events.has(eventName)) events.set(eventName, []);
      events.get(eventName).push(fn);
    },
    close() { ws.close(); },
  };
}

async function evaluate(client, expression) {
  const resp = await client.send("Runtime.evaluate", {
    expression,
    returnByValue: true,
    awaitPromise: true,
  });
  const r = resp?.result?.result;
  if (r?.subtype === "error") throw new Error(r.description || "Runtime.evaluate error");
  return r?.value;
}

// ── login flow ────────────────────────────────────────────────────────────────
async function waitForLoginPage(timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const pages = await listPages();
    const p = pages.find((pg) => pg.type === "page" && pg.url.includes("nidlogin.login"));
    if (p) return p;
    await sleep(600);
  }
  return null;
}

async function waitForNavAway(pageId, timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const pages = await listPages();
    const p = pages.find((pg) => pg.id === pageId) || pages[0];
    if (p && !p.url.includes("nidlogin.login")) return p;
    await sleep(1000);
  }
  const pages = await listPages();
  return pages.find((pg) => pg.id === pageId) || pages[0] || null;
}

async function main() {
  const masked = `${NAVER_ID.slice(0, 2)}…${NAVER_ID.slice(-2)}`;
  console.log(`[cdp-login] account: ${masked}  device: ${SERIAL}`);

  // 1. Ensure browser is open
  let pid = getBrowserPid();
  if (!pid) {
    console.log("[cdp-login] Samsung Internet not running — launching...");
    openBrowser(LOGIN_URL);
    await sleep(2500);
    pid = getBrowserPid();
  } else {
    console.log(`[cdp-login] Samsung Internet pid=${pid}`);
  }

  if (!pid) throw new Error("Could not get Samsung Internet PID");

  // 2. Forward DevTools socket
  forwardDevTools(pid);

  // 3. Open login URL
  console.log("[cdp-login] opening login URL...");
  openBrowser(LOGIN_URL);
  await sleep(2000);

  // 4. Wait for DevTools and login page
  await waitDevTools();
  let loginPage = await waitForLoginPage();
  if (!loginPage) {
    // PID may have changed after browser restart — retry
    pid = getBrowserPid();
    if (pid) { forwardDevTools(pid); await sleep(1000); }
    loginPage = await waitForLoginPage(15000);
  }
  if (!loginPage) {
    const pages = await listPages().catch(() => []);
    console.error("[cdp-login] pages:", pages.map((p) => p.url).join("\n  "));
    throw new Error("Naver login page not found in DevTools");
  }
  console.log(`[cdp-login] login page found: ${loginPage.url}`);

  // 5. Connect CDP
  const client = await connectPage(loginPage);

  try {
    // 6. Enable Network domain + override UA (Chrome 136 with full Client Hints)
    await client.send("Network.enable");
    await client.send("Network.setUserAgentOverride", {
      userAgent: UA_OVERRIDE,
      userAgentMetadata: UA_METADATA,
    });
    console.log("[cdp-login] UA override applied → Chrome/136");

    // 7. Wait for form inputs
    await evaluate(client, `new Promise((resolve, reject) => {
      const deadline = Date.now() + 25000;
      const tick = () => {
        if (document.querySelector("#id") && document.querySelector("#pw")) resolve(true);
        else if (Date.now() > deadline) reject(new Error("login inputs not found within 25s"));
        else setTimeout(tick, 300);
      };
      tick();
    })`);
    console.log("[cdp-login] form inputs ready");

    // 8. Fill ID
    await evaluate(client, `(() => {
      const el = document.querySelector("#id");
      el.focus();
      const nativeInputValueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      nativeInputValueSetter.call(el, "");
      el.dispatchEvent(new InputEvent("input", { bubbles: true }));
    })()`);
    await client.send("Input.insertText", { text: NAVER_ID });
    await sleep(350);

    // 9. Fill PW
    await evaluate(client, `(() => {
      const el = document.querySelector("#pw");
      el.focus();
      const nativeInputValueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      nativeInputValueSetter.call(el, "");
      el.dispatchEvent(new InputEvent("input", { bubbles: true }));
    })()`);
    await client.send("Input.insertText", { text: NAVER_PW });
    await sleep(600);

    // 10. Submit
    const clicked = await evaluate(client, `(() => {
      const btn = document.getElementById("log.login") || document.querySelector('button[type="submit"]') || document.querySelector('.btn_login');
      if (!btn) return false;
      btn.click();
      return true;
    })()`);
    if (!clicked) throw new Error("Submit button not found");
    console.log("[cdp-login] form submitted");
  } finally {
    client.close();
  }

  // 11. Wait for navigation away from login page
  console.log("[cdp-login] waiting for login result...");
  const resultPage = await waitForNavAway(loginPage.id);
  if (!resultPage || resultPage.url.includes("nidlogin.login")) {
    console.error(`[cdp-login] FAILED — still on: ${resultPage?.url || "(no page)"}`);
    process.exit(2);
  }
  console.log(`[cdp-login] navigated to: ${resultPage.title || ""} | ${resultPage.url}`);

  // 12. Verify session cookies via fresh CDP connection
  try {
    const freshPages = await listPages();
    const current = freshPages.find((p) => p.id === resultPage.id) || freshPages[0];
    if (!current) throw new Error("no page after login");

    const checker = await connectPage(current);
    try {
      await checker.send("Network.enable");
      const { cookies } = await checker.send("Network.getCookies", {
        urls: ["https://naver.com", "https://www.naver.com", "https://nid.naver.com"],
      });
      const names = cookies.map((c) => c.name);
      const hasAuth = names.includes("NID_AUT");
      const hasSes = names.includes("NID_SES");
      if (hasAuth && hasSes) {
        console.log("[cdp-login] ✓ NID_AUT + NID_SES cookies present — session active");
      } else {
        console.warn(`[cdp-login] cookie check: NID_AUT=${hasAuth} NID_SES=${hasSes}`);
        console.warn("[cdp-login] cookies found:", names.join(", ") || "(none)");
      }
    } finally {
      checker.close();
    }
  } catch (e) {
    console.warn("[cdp-login] cookie check failed:", e.message);
  }

  console.log("[cdp-login] done");
}

main().catch((e) => {
  console.error(e?.stack || e);
  process.exit(1);
});
