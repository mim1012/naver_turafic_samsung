const { execSync, spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const DEVICE = 'ce12160c9966340705';
const PKG = 'com.navertraffic.samsung';
const OUTDIR = path.resolve(__dirname);
const LOGFILE = path.join(OUTDIR, 'run-abc.log');

function log(msg) {
  const line = `[${new Date().toTimeString().slice(0,8)}] ${msg}`;
  console.log(line);
  fs.appendFileSync(LOGFILE, line + '\n');
}

function adb(...args) {
  return spawnSync('adb', ['-s', DEVICE, ...args], { encoding: 'utf8' }).stdout || '';
}

function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

function serviceRunning() {
  const out = adb('shell', 'dumpsys activity services');
  return out.includes('BotKeepAlive');
}

async function runVariant(variant, mid, kw, skw, title) {
  const deviceLog = `/sdcard/logcat_${variant}_0511.txt`;
  const localLog = path.join(OUTDIR, `logcat_${variant}_0511.txt`);

  log(`=== 변형 ${variant} 시작 ===`);

  // Force stop previous
  adb('shell', 'am', 'force-stop', PKG);
  await sleep(5000);

  // Kill existing logcat
  adb('shell', 'pkill', '-f', 'logcat');
  await sleep(2000);

  // Start logcat
  adb('shell', `rm -f ${deviceLog} && nohup logcat -v time -s SamsungTrafficBot > ${deviceLog} 2>&1 &`);
  await sleep(3000);

  // Launch app — pass entire shell command as single string to preserve Korean encoding
  const amCmd = `am start -n ${PKG}/.ui.MainActivity --ez autoRun true --es deviceName z1 --ei loopCount 200 --ei rotateEvery 5 --es strategyVariant "${variant}" --es mid "${mid}" --es keyword "${kw}" --es secondKeyword "${skw}" --es productTitle "${title}"`;
  adb('shell', amCmd);

  log(`${variant} 앱 실행 — 서비스 시작 대기...`);

  // Wait for service to start (max 2min)
  let waited = 0;
  while (!serviceRunning() && waited < 120) {
    await sleep(10000);
    waited += 10;
  }
  if (!serviceRunning()) {
    log(`경고: ${variant} 서비스 미시작 — 다음으로 진행`);
    return;
  }
  log(`${variant} 서비스 확인됨. 완료 대기 중...`);

  // Poll every 2 min until service stops
  let loops = 0;
  while (serviceRunning()) {
    await sleep(120000);
    loops++;
    const lines = adb('shell', `wc -l < ${deviceLog} 2>/dev/null || echo 0`).trim().replace(/\r/g, '');
    log(`${variant} 진행 중 (${loops * 2}분 경과, 로그 ${lines}줄)`);
  }

  log(`${variant} 완료 — 로그 수집`);
  spawnSync('adb', ['-s', DEVICE, 'pull', deviceLog, localLog], { encoding: 'utf8' });
  log(`저장: ${localLog}`);

  await sleep(15000);
}

(async () => {
  log('A/B/C 순차 테스트 시작');

  await runVariant('A', '89408249403', '노트북 파우치',
    '킨맥 360쉴드 노트북 파우치 가방 S 13 투톤네이비', '킨맥파우치A');

  await runVariant('B', '89408249403', '노트북 파우치',
    '킨맥 360쉴드 노트북 파우치 가방 S 13 투톤네이비', '킨맥파우치B');

  await runVariant('C', '90991583792', '여름 선글라스',
    '남성 자외선 차단 여름 선글라스 보잉 블랙 미러', '여름선글라스C');

  log('=== A/B/C 전체 완료 ===');
})();
