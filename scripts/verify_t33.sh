#!/usr/bin/env bash
#
# verify_t33.sh — T3.3 근접 교전(크리 타이밍) 검증
#
# [검증] (AI_Bot_Design.md T3.3):
#   고체력 더미 스폰 → 검 지급 → 근접 교전 → 타겟 체력 감소량 + 크리 데미지(크기로 판별) 측정.
#   판정: 타겟 체력 감소 AND 최소 1회 이상 크리 데미지(기본의 1.5배 근사) 관측.
#
# BotMeleeTest 가 더미의 틱당 체력 감소를 측정해 최대 단일타 데미지가 botAtk*1.4 이상인지 본다.
# 판정 근거는 데미지 크기(값 변동)뿐 — "공격 호출됨"이 아니라 크리 배율 데미지가 관측돼야 PASS (R.2).
#
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
SCRATCH="${SCRATCH:-/tmp/claude-0/-home-user-friendbot/0c645f03-3948-5413-bc6f-b01dd06604d4/scratchpad}"
export JAVA_HOME="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64}"

SERVER_LOG="$SCRATCH/t33_server.log"

echo ">>> [T3.3] step 1: ./gradlew build"
./gradlew build --console=plain > "$SCRATCH/t33_build.log" 2>&1
BUILD_EXIT=$?
echo "    build exit=$BUILD_EXIT"

echo ">>> [T3.3] step 2: dev server boot with -PbottestAuto=bot_melee"
rm -rf run/world; rm -f "$SERVER_LOG"; mkdir -p run; echo "eula=true" > run/eula.txt
( ./gradlew runServer -PbottestAuto=bot_melee --console=plain > "$SERVER_LOG" 2>&1 ) &
SRV_PID=$!
for i in $(seq 1 150); do
    if grep -qE "\[BOTTEST\] bot_melee (PASS|FAIL)" "$SERVER_LOG" 2>/dev/null; then break; fi
    if ! kill -0 "$SRV_PID" 2>/dev/null; then break; fi
    sleep 2
done
if kill -0 "$SRV_PID" 2>/dev/null; then kill "$SRV_PID" 2>/dev/null; sleep 3; kill -9 "$SRV_PID" 2>/dev/null; fi

STATE="absent"
if grep -qE "\[BOTTEST\] bot_melee PASS" "$SERVER_LOG" 2>/dev/null; then STATE="PASS"
elif grep -qE "\[BOTTEST\] bot_melee FAIL" "$SERVER_LOG" 2>/dev/null; then STATE="FAIL"; fi
echo "    bot_melee = $STATE"

echo ">>> [T3.3] judge"
PASS=1
[ "$BUILD_EXIT" -eq 0 ] || PASS=0
[ "$STATE" = "PASS" ]   || PASS=0
MEASURED="build:${BUILD_EXIT},bot_melee:${STATE}"
EXPECTED="build==0 AND bot_melee PASS (target damaged + crit observed)"
if [ "$PASS" -eq 1 ]; then
    echo "[BOTTEST] T3.3 PASS measured=${MEASURED} expected=${EXPECTED}"; exit 0
else
    echo "[BOTTEST] T3.3 FAIL measured=${MEASURED} expected=${EXPECTED}"; exit 1
fi
