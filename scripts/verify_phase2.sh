#!/usr/bin/env bash
#
# verify_phase2.sh — Phase 2 통합/회귀 검증
#
# 이동(T2.1) + 시선(T2.2) + A*(T2.3)가 동시에 켜진 상태에서 서로 충돌/회귀가 없는지
# bot_phase2_combo 로 확인한다: 벽 우회 A* 이동 중 고정 지점 주시 → 도달 AND 머리가
# 주시 방향(±오차) AND 이동 방향과 독립(머리-몸통 각도차 큼).
# 판정 근거: 좌표 도달 + 각도값(머리/몸통 분리) — R.2 준수.
#
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
SCRATCH="${SCRATCH:-/tmp/claude-0/-home-user-friendbot/0c645f03-3948-5413-bc6f-b01dd06604d4/scratchpad}"
export JAVA_HOME="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64}"

SERVER_LOG="$SCRATCH/phase2_server.log"

echo ">>> [Phase2] step 1: ./gradlew build"
./gradlew build --console=plain > "$SCRATCH/phase2_build.log" 2>&1
BUILD_EXIT=$?
echo "    build exit=$BUILD_EXIT"

echo ">>> [Phase2] step 2: dev server boot with bot_phase2_combo"
rm -rf run/world; rm -f "$SERVER_LOG"; mkdir -p run; echo "eula=true" > run/eula.txt
( ./gradlew runServer -PbottestAuto=bot_phase2_combo --console=plain > "$SERVER_LOG" 2>&1 ) &
SRV_PID=$!
for i in $(seq 1 200); do
    if grep -qE "\[BOTTEST\] bot_phase2_combo (PASS|FAIL)" "$SERVER_LOG" 2>/dev/null; then break; fi
    if ! kill -0 "$SRV_PID" 2>/dev/null; then break; fi
    sleep 2
done
if kill -0 "$SRV_PID" 2>/dev/null; then kill "$SRV_PID" 2>/dev/null; sleep 3; kill -9 "$SRV_PID" 2>/dev/null; fi

COMBO="absent"
if grep -qE "\[BOTTEST\] bot_phase2_combo PASS" "$SERVER_LOG" 2>/dev/null; then COMBO="PASS"
elif grep -qE "\[BOTTEST\] bot_phase2_combo FAIL" "$SERVER_LOG" 2>/dev/null; then COMBO="FAIL"; fi
echo "    bot_phase2_combo = $COMBO"

echo ">>> [Phase2] judge"
PASS=1
[ "$BUILD_EXIT" -eq 0 ] || PASS=0
[ "$COMBO" = "PASS" ]   || PASS=0
MEASURED="build:${BUILD_EXIT},combo:${COMBO}"
EXPECTED="build==0 AND bot_phase2_combo PASS (move+look+A* no conflict)"
if [ "$PASS" -eq 1 ]; then
    echo "[BOTTEST] PHASE2 PASS measured=${MEASURED} expected=${EXPECTED}"; exit 0
else
    echo "[BOTTEST] PHASE2 FAIL measured=${MEASURED} expected=${EXPECTED}"; exit 1
fi
