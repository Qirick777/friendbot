#!/usr/bin/env bash
#
# verify_t34.sh — T3.4 투사체 예측 조준 + 원거리 교전 검증
#
# [검증] (AI_Bot_Design.md T3.4):
#   일정 속도로 직선 이동하는 타겟 스폰 → 봇에 활+화살 지급 → 원거리 교전 지시 →
#   발사한 화살이 타겟에 명중(타겟 체력 감소)하는지 여러 회 측정.
#   판정: 발사 대비 명중률 ≥ 임계(80%) = 타겟 체력 감소 이벤트 수 / 발사 수.
#
# BotRangedTest 가 0.15/tick 로 시선에 수직 직선 이동하는 고체력 더미에 봇이 활을 쏘게 하고,
# 실제 발사 수(rangedCombat.shotsFired) 대비 명중(더미 체력 감소 이벤트) 비율을 측정한다.
# 판정 근거는 명중률(값 변동)뿐 — "발사 호출됨"이 아니라 타겟 체력이 실제로 감소해야 한다 (R.2).
#
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
SCRATCH="${SCRATCH:-/tmp/claude-0/-home-user-friendbot/0c645f03-3948-5413-bc6f-b01dd06604d4/scratchpad}"
export JAVA_HOME="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64}"

SERVER_LOG="$SCRATCH/t34_server.log"

echo ">>> [T3.4] step 1: ./gradlew build"
./gradlew build --console=plain > "$SCRATCH/t34_build.log" 2>&1
BUILD_EXIT=$?
echo "    build exit=$BUILD_EXIT"

echo ">>> [T3.4] step 2: dev server boot with -PbottestAuto=bot_ranged"
rm -rf run/world; rm -f "$SERVER_LOG"; mkdir -p run; echo "eula=true" > run/eula.txt
( ./gradlew runServer -PbottestAuto=bot_ranged --console=plain > "$SERVER_LOG" 2>&1 ) &
SRV_PID=$!
for i in $(seq 1 180); do
    if grep -qE "\[BOTTEST\] bot_ranged (PASS|FAIL)" "$SERVER_LOG" 2>/dev/null; then break; fi
    if ! kill -0 "$SRV_PID" 2>/dev/null; then break; fi
    sleep 2
done
if kill -0 "$SRV_PID" 2>/dev/null; then kill "$SRV_PID" 2>/dev/null; sleep 3; kill -9 "$SRV_PID" 2>/dev/null; fi

STATE="absent"
if grep -qE "\[BOTTEST\] bot_ranged PASS" "$SERVER_LOG" 2>/dev/null; then STATE="PASS"
elif grep -qE "\[BOTTEST\] bot_ranged FAIL" "$SERVER_LOG" 2>/dev/null; then STATE="FAIL"; fi
echo "    bot_ranged = $STATE"
grep -E "\[BOTTEST\] bot_ranged" "$SERVER_LOG" 2>/dev/null | tail -1

echo ">>> [T3.4] judge"
PASS=1
[ "$BUILD_EXIT" -eq 0 ] || PASS=0
[ "$STATE" = "PASS" ]   || PASS=0
MEASURED="build:${BUILD_EXIT},bot_ranged:${STATE}"
EXPECTED="build==0 AND bot_ranged PASS (hit rate >= 80% on a moving target)"
if [ "$PASS" -eq 1 ]; then
    echo "[BOTTEST] T3.4 PASS measured=${MEASURED} expected=${EXPECTED}"; exit 0
else
    echo "[BOTTEST] T3.4 FAIL measured=${MEASURED} expected=${EXPECTED}"; exit 1
fi
