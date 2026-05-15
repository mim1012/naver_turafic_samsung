const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const DEVICE = 'ce12160c9966340705';
const PKG = 'com.navertraffic.samsung';
const OUTDIR = path.resolve(__dirname);
const LOGFILE = path.join(OUTDIR, 'watch-b-then-c.log');

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

async function waitForServiceStop(label) {
  log(`${label} 서비스 종료 대기 중...`);
  let loops = 0;
  while (serviceRunning()) {
    await sleep(60000);
    loops++;
    const lines = adb('shell', `wc -l < /sdcard/logcat_B_0511.txt 2>/dev/null || echo 0`).trim().replace(/\r/g, '');
    const latest = adb('shell', `grep '반복' /sdcard/logcat_B_0511.txt 2>/dev/null | tail -1`).trim();
    log(`${label} 진행 중 (${loops}분 경과, 로그 ${lines}줄) — ${latest}`);
  }
  log(`${label} 서비스 종료 확인`);
}

async function runVariant(variant, mid, kw, skw, title) {
  const deviceLog = `/sdcard/logcat_${variant}_0511.txt`;
  const localLog = path.join(OUTDIR, `logcat_${variant}_0511.txt`);

  log(`=== 변형 ${variant} 시작 ===`);

  adb('shell', 'am', 'force-stop', PKG);
  await sleep(5000);

  adb('shell', 'pkill', '-f', 'logcat');
  await sleep(2000);

  adb('shell', `rm -f ${deviceLog} && nohup logcat -v time -s SamsungTrafficBot > ${deviceLog} 2>&1 &`);
  await sleep(3000);

  const amCmd = `am start -n ${PKG}/.ui.MainActivity --ez autoRun true --es deviceName z1 --ei loopCount 200 --ei rotateEvery 5 --es strategyVariant "${variant}" --es mid "${mid}" --es keyword "${kw}" --es secondKeyword "${skw}" --es productTitle "${title}"`;
  adb('shell', amCmd);

  log(`${variant} 앱 실행 — 서비스 시작 대기...`);

  let waited = 0;
  while (!serviceRunning() && waited < 120) {
    await sleep(10000);
    waited += 10;
  }
  if (!serviceRunning()) {
    log(`경고: ${variant} 서비스 미시작`);
    return;
  }
  log(`${variant} 서비스 확인됨`);

  let loops = 0;
  while (serviceRunning()) {
    await sleep(60000);
    loops++;
    const lines = adb('shell', `wc -l < ${deviceLog} 2>/dev/null || echo 0`).trim().replace(/\r/g, '');
    const latest = adb('shell', `grep '반복' ${deviceLog} 2>/dev/null | tail -1`).trim();
    log(`${variant} 진행 중 (${loops}분 경과, 로그 ${lines}줄) — ${latest}`);
  }

  log(`${variant} 완료 — 로그 수집`);
  spawnSync('adb', ['-s', DEVICE, 'pull', deviceLog, localLog], { encoding: 'utf8' });
  log(`저장: ${localLog}`);

  await sleep(15000);
}

(async () => {
  log('B 완료 감시 시작 (B는 현재 실행 중)');

  // B가 이미 실행 중이므로 끝날 때까지 대기
  await waitForServiceStop('B');

  // B 로그 수집
  log('B 로그 수집');
  spawnSync('adb', ['-s', DEVICE, 'pull', '/sdcard/logcat_B_0511.txt',
    path.join(OUTDIR, 'logcat_B_0511_final.txt')], { encoding: 'utf8' });

  await sleep(15000);

  // C 실행
  await runVariant('C', '90991583792', '여름 선글라스',
    '남성 자외선 차단 여름 선글라스 보잉 블랙 미러', '여름선글라스C');

  log('=== B 감시 + C 실행 전체 완료 ===');
})();
