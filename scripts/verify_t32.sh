#!/usr/bin/env bash
#
# verify_t32.sh — T3.2 측정 기반 전술 규칙 엔진 검증
#
# [검증] (AI_Bot_Design.md T3.2):
#   좀비·철골렘 각각 스폰 후 판정 덤프. 좀비→근접허용, 철골렘→원거리강제+방패.
#   판정: 좀비 allowMelee==true AND 철골렘 allowMelee==false.
#
# BotTacticsTest 가 두 몹의 측정 스탯을 규칙 엔진에 넣어 allowMelee 를 도출한다.
# 판정 근거는 규칙 엔진 출력값(좀비 true / 골렘 false) — 이름이 아닌 측정으로 갈림 (R.2 준수).
#
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
SCRATCH="${SCRATCH:-/tmp/claude-0/-home-user-friendbot/0c645f03-3948-5413-bc6f-b01dd06604d4/scratchpad}"
export JAVA_HOME="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64}"

SERVER_LOG="$SCRATCH/t32_server.log"

echo ">>> [T3.2] step 1: ./gradlew build"
./gradlew build --console=plain > "$SCRATCH/t32_build.log" 2>&1
BUILD_EXIT=$?
echo "    build exit=$BUILD_EXIT"

echo ">>> [T3.2] step 2: dev server boot with -PbottestAuto=bot_tactics"
rm -rf run/world; rm -f "$SERVER_LOG"; mkdir -p run; echo "eula=true" > run/eula.txt
( ./gradlew runServer -PbottestAuto=bot_tactics --console=plain > "$SERVER_LOG" 2>&1 ) &
SRV_PID=$!
for i in $(seq 1 120); do
    if grep -qE "\[BOTTEST\] bot_tactics (PASS|FAIL)" "$SERVER_LOG" 2>/dev/null; then break; fi
    if ! kill -0 "$SRV_PID" 2>/dev/null; then break; fi
    sleep 2
done
if kill -0 "$SRV_PID" 2>/dev/null; then kill "$SRV_PID" 2>/dev/null; sleep 3; kill -9 "$SRV_PID" 2>/dev/null; fi

STATE="absent"
if grep -qE "\[BOTTEST\] bot_tactics PASS" "$SERVER_LOG" 2>/dev/null; then STATE="PASS"
elif grep -qE "\[BOTTEST\] bot_tactics FAIL" "$SERVER_LOG" 2>/dev/null; then STATE="FAIL"; fi
echo "    bot_tactics = $STATE"

echo ">>> [T3.2] judge"
PASS=1
[ "$BUILD_EXIT" -eq 0 ] || PASS=0
[ "$STATE" = "PASS" ]   || PASS=0
MEASURED="build:${BUILD_EXIT},bot_tactics:${STATE}"
EXPECTED="build==0 AND bot_tactics PASS (zombie allowMelee=true, golem allowMelee=false)"
if [ "$PASS" -eq 1 ]; then
    echo "[BOTTEST] T3.2 PASS measured=${MEASURED} expected=${EXPECTED}"; exit 0
else
    echo "[BOTTEST] T3.2 FAIL measured=${MEASURED} expected=${EXPECTED}"; exit 1
fi
