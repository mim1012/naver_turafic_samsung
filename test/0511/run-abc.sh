#!/bin/bash
DEVICE="ce12160c9966340705"
PKG="com.navertraffic.samsung"
OUTDIR="test/0511"

log() { echo "[$(date '+%H:%M:%S')] $*"; }

run_variant() {
  local VAR="$1" MID="$2" KW="$3" SKW="$4" TITLE="$5"
  local LOGFILE="/sdcard/logcat_${VAR}_0511.txt"

  log "=== 변형 $VAR 시작 ==="

  adb -s "$DEVICE" shell am force-stop "$PKG" 2>/dev/null || true
  sleep 5

  adb -s "$DEVICE" shell "pkill -f 'logcat' 2>/dev/null; true" || true
  sleep 2

  adb -s "$DEVICE" shell "rm -f $LOGFILE && nohup logcat -v time -s SamsungTrafficBot > $LOGFILE 2>&1 &"
  sleep 3

  adb -s "$DEVICE" shell "am start -n $PKG/.ui.MainActivity \
    --ez autoRun true --es deviceName z1 --ei loopCount 200 --ei rotateEvery 5 \
    --es strategyVariant '$VAR' --es mid '$MID' \
    --es keyword '$KW' --es secondKeyword '$SKW' --es productTitle '$TITLE'"

  log "$VAR 실행 시작 — 서비스 대기 중..."

  # 서비스 시작 대기 (최대 2분)
  WAIT=0
  until adb -s "$DEVICE" shell dumpsys activity services 2>/dev/null | grep -q "BotKeepAlive"; do
    sleep 10; WAIT=$((WAIT+10))
    if [ $WAIT -ge 120 ]; then log "경고: 서비스 미시작 ($VAR)"; break; fi
  done

  # 서비스 종료 대기 (2분마다 폴링)
  while adb -s "$DEVICE" shell dumpsys activity services 2>/dev/null | grep -q "BotKeepAlive"; do
    LINES=$(adb -s "$DEVICE" shell "wc -l < $LOGFILE 2>/dev/null || echo 0" | tr -d '[:space:]\r')
    log "$VAR 진행 중 (로그 ${LINES}줄)..."
    sleep 120
  done

  log "$VAR 완료 — 로그 수집 중"
  adb -s "$DEVICE" pull "$LOGFILE" "$OUTDIR/logcat_${VAR}_0511.txt" && \
    log "저장: $OUTDIR/logcat_${VAR}_0511.txt" || log "로그 pull 실패"

  sleep 15
}

run_variant "A" "89408249403" "노트북 파우치" "킨맥 360쉴드 노트북 파우치 가방 S 13 투톤네이비" "킨맥파우치A"
run_variant "B" "89408249403" "노트북 파우치" "킨맥 360쉴드 노트북 파우치 가방 S 13 투톤네이비" "킨맥파우치B"
run_variant "C" "90991583792" "여름 선글라스" "남성 자외선 차단 여름 선글라스 보잉 블랙 미러" "여름선글라스C"

log "=== A/B/C 전체 완료 ==="
