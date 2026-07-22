#!/usr/bin/env bash
#
# verify_t22.sh — T2.2 시선 시스템 검증
#
# [검증] (AI_Bot_Design.md T2.2):
#   봇 옆에 표식 엔티티 스폰 → 주시 지시 → 봇 머리 yaw가 여러 틱에 걸쳐(즉시 아님)
#   표식 방향 각도로 수렴하는지.  판정: 각 틱 yaw 변화량 <= 상한 AND 최종 yaw가 목표각 ±오차 이내.
#
# BotLookTest 가 매 틱 head yaw 를 샘플링해 최대 틱당 변화량과 최종 수렴 오차를 측정한다.
# 판정 근거는 각도값의 변동(스텝<=상한 + 목표 수렴)뿐 (R.2 "호출≠성공").
#
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SCRATCH="${SCRATCH:-/tmp/claude-0/-home-user-friendbot/0c645f03-3948-5413-bc6f-b01dd06604d4/scratchpad}"
JAVA17="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64}"
export JAVA_HOME="$JAVA17"

EXPECT_LINE="[BOTTEST] bot_look PASS"
BUILD_LOG="$SCRATCH/t22_build.log"
SERVER_LOG="$SCRATCH/t22_server.log"

echo ">>> [T2.2] step 1: ./gradlew build"
./gradlew build --console=plain > "$BUILD_LOG" 2>&1
BUILD_EXIT=$?
echo "    build exit=$BUILD_EXIT"

echo ">>> [T2.2] step 2: dev server boot with -PbottestAuto=bot_look"
rm -rf run/world
rm -f "$SERVER_LOG"
mkdir -p run
echo "eula=true" > run/eula.txt
( ./gradlew runServer -PbottestAuto=bot_look --console=plain > "$SERVER_LOG" 2>&1 ) &
SRV_PID=$!

for i in $(seq 1 120); do
    if grep -qE "\[BOTTEST\] bot_look (PASS|FAIL)" "$SERVER_LOG" 2>/dev/null; then break; fi
    if ! kill -0 "$SRV_PID" 2>/dev/null; then break; fi
    sleep 2
done

if kill -0 "$SRV_PID" 2>/dev/null; then
    kill "$SRV_PID" 2>/dev/null; sleep 3; kill -9 "$SRV_PID" 2>/dev/null
fi

LOOK_STATE="absent"
if grep -qE "\[BOTTEST\] bot_look PASS" "$SERVER_LOG" 2>/dev/null; then LOOK_STATE="PASS"
elif grep -qE "\[BOTTEST\] bot_look FAIL" "$SERVER_LOG" 2>/dev/null; then LOOK_STATE="FAIL"; fi

echo ">>> [T2.2] judge"
PASS=1
[ "$BUILD_EXIT" -eq 0 ]   || PASS=0
[ "$LOOK_STATE" = "PASS" ] || PASS=0

MEASURED="build:${BUILD_EXIT},bot_look:${LOOK_STATE}"
EXPECTED="build==0 AND '[BOTTEST] bot_look PASS' present (smooth step + converged)"

if [ "$PASS" -eq 1 ]; then
    echo "[BOTTEST] T2.2 PASS measured=${MEASURED} expected=${EXPECTED}"
    exit 0
else
    echo "[BOTTEST] T2.2 FAIL measured=${MEASURED} expected=${EXPECTED}"
    exit 1
fi
