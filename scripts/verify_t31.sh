#!/usr/bin/env bash
#
# verify_t31.sh — T3.1 인식 계층 검증
#
# [검증] (AI_Bot_Design.md T3.1):
#   좀비 스폰 → 인식 덤프 → 타겟 목록에 좀비 있고 공격력·체력 측정값이 바닐라와 일치
#   + 화살 발사 → incoming 목록에 잡힘.
#   판정: 타겟 목록 길이>=1 AND 측정 공격력==바닐라값 AND 투사체 감지 반영.
#
# BotPerceptionTest 가 좀비의 실제 attribute 를 ground-truth 로 읽어 인식값과 대조하고,
# 봇으로 향하는 화살이 incoming 에 잡히는지 관찰한다. 판정 근거는 측정값 일치(값 대조) — R.2 준수.
#
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
SCRATCH="${SCRATCH:-/tmp/claude-0/-home-user-friendbot/0c645f03-3948-5413-bc6f-b01dd06604d4/scratchpad}"
export JAVA_HOME="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64}"

SERVER_LOG="$SCRATCH/t31_server.log"

echo ">>> [T3.1] step 1: ./gradlew build"
./gradlew build --console=plain > "$SCRATCH/t31_build.log" 2>&1
BUILD_EXIT=$?
echo "    build exit=$BUILD_EXIT"

echo ">>> [T3.1] step 2: dev server boot with -PbottestAuto=bot_perception"
rm -rf run/world; rm -f "$SERVER_LOG"; mkdir -p run; echo "eula=true" > run/eula.txt
( ./gradlew runServer -PbottestAuto=bot_perception --console=plain > "$SERVER_LOG" 2>&1 ) &
SRV_PID=$!
for i in $(seq 1 120); do
    if grep -qE "\[BOTTEST\] bot_perception (PASS|FAIL)" "$SERVER_LOG" 2>/dev/null; then break; fi
    if ! kill -0 "$SRV_PID" 2>/dev/null; then break; fi
    sleep 2
done
if kill -0 "$SRV_PID" 2>/dev/null; then kill "$SRV_PID" 2>/dev/null; sleep 3; kill -9 "$SRV_PID" 2>/dev/null; fi

STATE="absent"
if grep -qE "\[BOTTEST\] bot_perception PASS" "$SERVER_LOG" 2>/dev/null; then STATE="PASS"
elif grep -qE "\[BOTTEST\] bot_perception FAIL" "$SERVER_LOG" 2>/dev/null; then STATE="FAIL"; fi
echo "    bot_perception = $STATE"

echo ">>> [T3.1] judge"
PASS=1
[ "$BUILD_EXIT" -eq 0 ] || PASS=0
[ "$STATE" = "PASS" ]   || PASS=0
MEASURED="build:${BUILD_EXIT},bot_perception:${STATE}"
EXPECTED="build==0 AND bot_perception PASS (zombie measured==attr + arrow incoming)"
if [ "$PASS" -eq 1 ]; then
    echo "[BOTTEST] T3.1 PASS measured=${MEASURED} expected=${EXPECTED}"; exit 0
else
    echo "[BOTTEST] T3.1 FAIL measured=${MEASURED} expected=${EXPECTED}"; exit 1
fi
