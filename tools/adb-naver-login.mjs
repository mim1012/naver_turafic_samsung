import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";

const config = {
  serial: process.env.ADB_SERIAL || "172.30.1.68:35665",
  devtoolsPort: Number(process.env.DEVTOOLS_PORT || 9223),
  browserPackage: process.env.BROWSER_PACKAGE || "com.sec.android.app.sbrowser",
  devtoolsSocket: process.env.DEVTOOLS_SOCKET || "Terrace_devtools_remote",
  accountPath:
    process.env.NAVER_ACCOUNT_FILE ||
    "D:\\Project\\electrone_navershopping\\naver-account.txt",
  loginUrl:
    "https://nid.naver.com/nidlogin.login?mode=form&url=https://www.naver.com/",
};

function adb(args) {
  return execFileSync("adb.exe", ["-s", config.serial, ...args], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
}

function shell(command) {
  return adb(["shell", command]);
}

function openSamsungBrowser(url) {
  shell(`am start -a android.intent.action.VIEW -d '${url}' ${config.browserPackage}`);
}

function readAccount() {
  const raw = fs.readFileSync(path.resolve(config.accountPath), "utf8");
  const lines = raw
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith("#"));
  if (lines.length < 2) throw new Error("naver-account.txt must contain id and password on two lines");
  return { id: lines[0], pw: lines[1] };
}

async function listPages() {
  const response = await fetch(`http://127.0.0.1:${config.devtoolsPort}/json`);
  if (!response.ok) throw new Error(`DevTools /json failed: ${response.status}`);
  return response.json();
}

async function waitForDevTools() {
  const deadline = Date.now() + 30000;
  let lastError = null;
  while (Date.now() < deadline) {
    try {
      return await listPages();
    } catch (error) {
      lastError = error;
      await sleep(500);
    }
  }
  throw lastError || new Error("DevTools /json was not ready");
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

async function evaluate(client, expression) {
  const response = await client.send("Runtime.evaluate", {
    expression,
    returnByValue: true,
    awaitPromise: true,
  });
  const remote = response?.result?.result;
  if (remote?.subtype === "error") throw new Error(remote.description || "Runtime.evaluate error");
  return remote?.value;
}

async function findLoginPage() {
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    const pages = await waitForDevTools();
    const loginPage = pages.find((page) => page.type === "page" && page.url.includes("nidlogin.login"));
    if (loginPage) return loginPage;
    await sleep(500);
  }
  return null;
}

async function waitForLoginResult(pageId) {
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) {
    const pages = await listPages();
    const page = pages.find((item) => item.id === pageId) || pages[0];
    if (page && !page.url.includes("nidlogin.login")) {
      return { ok: true, page };
    }
    await sleep(1000);
  }
  const pages = await listPages();
  const page = pages.find((item) => item.id === pageId) || pages[0];
  return { ok: false, page };
}

async function main() {
  const account = readAccount();
  const masked = account.id.length <= 4 ? "****" : `${account.id.slice(0, 2)}…${account.id.slice(-2)}`;
  console.log(`[NaverLogin] account loaded: ${masked}`);

  adb(["forward", `tcp:${config.devtoolsPort}`, `localabstract:${config.devtoolsSocket}`]);
  openSamsungBrowser(config.loginUrl);

  const page = await findLoginPage();
  if (!page) throw new Error("Naver login page was not exposed through Samsung Internet DevTools");

  const client = await connectPage(page);
  try {
    await evaluate(client, `new Promise((resolve, reject) => {
      const deadline = Date.now() + 20000;
      const tick = () => {
        if (document.querySelector("#id") && document.querySelector("#pw")) resolve(true);
        else if (Date.now() > deadline) reject(new Error("login inputs not found"));
        else setTimeout(tick, 250);
      };
      tick();
    })`);

    await evaluate(client, `(() => {
      const id = document.querySelector("#id");
      id.focus();
      id.value = "";
      id.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "deleteContentBackward" }));
      return true;
    })()`);
    await client.send("Input.insertText", { text: account.id });
    await sleep(400);

    await evaluate(client, `(() => {
      const pw = document.querySelector("#pw");
      pw.focus();
      pw.value = "";
      pw.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "deleteContentBackward" }));
      return true;
    })()`);
    await client.send("Input.insertText", { text: account.pw });
    await sleep(700);

    await evaluate(client, `(() => {
      const button = document.getElementById("log.login") || document.querySelector('button[type="submit"]');
      if (!button) return false;
      button.click();
      return true;
    })()`);
  } finally {
    client.close();
  }

  const result = await waitForLoginResult(page.id);
  if (!result.ok) {
    console.log(`[NaverLogin] login did not leave login page: ${result.page?.title || "-"} | ${result.page?.url || "-"}`);
    process.exit(2);
  }
  console.log(`[NaverLogin] login completed: ${result.page?.title || "-"} | ${result.page?.url || "-"}`);
}

main().catch((error) => {
  console.error(error?.stack || error);
  process.exit(1);
});
