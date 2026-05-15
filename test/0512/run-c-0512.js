const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const DEVICE = 'ce12160c9966340705';
const PKG = 'com.navertraffic.samsung';
const OUTDIR = path.resolve(__dirname);
const LOGFILE = path.join(OUTDIR, 'run-c-0512.log');

fs.mkdirSync(OUTDIR, { recursive: true });

function log(msg) {
  const line = `[${new Date().toTimeString().slice(0,8)}] ${msg}`;
  console.log(line);
  fs.appendFileSync(LOGFILE, line + '\n');
}

function adb(...args) {
  return spawnSync('adb', ['-s', DEVICE, ...args], { encoding: 'utf8', maxBuffer: 10 * 1024 * 1024 }).stdout || '';
}

function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

function serviceRunning() {
  const out = adb('shell', 'dumpsys activity services');
  return out.includes('BotKeepAlive');
}

async function runC() {
  const variant = 'C';
  const mid = '90991583792';
  const kw = '여름 선글라스';
  const skw = '남성 자외선 차단 여름 선글라스 보잉 블랙 미러 선글라스';
  const title = '선글라스C';
  const deviceLog = `/sdcard/logcat_C_0512.txt`;
  const localLog = path.join(OUTDIR, `logcat_C_0512.txt`);

  log(`=== C 실행 시작 ===`);
  log(`keyword: ${kw}`);
  log(`secondKeyword: ${skw}`);

  adb('shell', 'am', 'force-stop', PKG);
  await sleep(3000);
  adb('shell', 'pm', 'clear', PKG);
  await sleep(2000);

  adb('shell', 'pkill', '-f', 'logcat');
  await sleep(1000);
  adb('shell', `rm -f ${deviceLog}`);
  await sleep(500);

  // logcat 시작 (백그라운드)
  spawnSync('adb', ['-s', DEVICE, 'shell', `nohup logcat -v time -s SamsungTrafficBot > ${deviceLog} 2>&1 &`], { encoding: 'utf8' });
  await sleep(2000);

  // am start — Korean 인코딩 유지를 위해 단일 shell string으로 전달
  const amCmd = `am start -n ${PKG}/.ui.MainActivity --ez autoRun true --es deviceName z1 --ei loopCount 200 --ei rotateEvery 5 --es strategyVariant "${variant}" --es mid "${mid}" --es keyword "${kw}" --es secondKeyword "${skw}" --es productTitle "${title}"`;
  log(`am start: ${amCmd}`);
  adb('shell', amCmd);

  log('서비스 시작 대기...');
  let waited = 0;
  while (!serviceRunning() && waited < 120) {
    await sleep(10000);
    waited += 10;
  }
  if (!serviceRunning()) {
    log('경고: 서비스 미시작');
    return;
  }
  log('서비스 확인됨 — 실행 모니터링 시작');

  let loops = 0;
  while (serviceRunning()) {
    await sleep(60000);
    loops++;
    const lines = adb('shell', `wc -l < ${deviceLog} 2>/dev/null || echo 0`).trim().replace(/\r/g, '');
    const latest = adb('shell', `grep '반복' ${deviceLog} 2>/dev/null | tail -1`).trim();
    log(`진행 중 (${loops}분 경과, 로그 ${lines}줄) — ${latest}`);
  }

  log('완료 — 로그 수집');
  spawnSync('adb', ['-s', DEVICE, 'pull', deviceLog, localLog], { encoding: 'utf8' });
  log(`저장: ${localLog}`);
}

runC().then(() => log('=== 완료 ==='));
