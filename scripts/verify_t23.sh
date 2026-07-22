#!/usr/bin/env bash
#
# verify_t23.sh — T2.3 자체 A* 검증
#
# [검증] (AI_Bot_Design.md T2.3):
#   장애물(벽·구덩이) 지형에서 목표 도달(막힘 우회 포함) + 완전 봉쇄 시 경로 null 반환.
#   판정: 봇 최종 위치 == 목표 블록(±1) within 타임아웃 ; 별도로 벽 봉쇄 시 null 반환.
#
# 두 테스트를 한 부팅에서 순차 실행(-PbottestAuto=bot_path_reach,bot_path_blocked).
# 판정 근거: 봇 최종 좌표의 목표 일치(값 변동) / findPath 반환값 null(상태) — R.2 준수.
#
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SCRATCH="${SCRATCH:-/tmp/claude-0/-home-user-friendbot/0c645f03-3948-5413-bc6f-b01dd06604d4/scratchpad}"
JAVA17="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64}"
export JAVA_HOME="$JAVA17"

BUILD_LOG="$SCRATCH/t23_build.log"
SERVER_LOG="$SCRATCH/t23_server.log"

echo ">>> [T2.3] step 1: ./gradlew build"
./gradlew build --console=plain > "$BUILD_LOG" 2>&1
BUILD_EXIT=$?
echo "    build exit=$BUILD_EXIT"

echo ">>> [T2.3] step 2: dev server boot with bot_path_reach,bot_path_blocked"
rm -rf run/world
rm -f "$SERVER_LOG"
mkdir -p run
echo "eula=true" > run/eula.txt
( ./gradlew runServer -PbottestAuto=bot_path_reach,bot_path_blocked --console=plain > "$SERVER_LOG" 2>&1 ) &
SRV_PID=$!

for i in $(seq 1 200); do
    if grep -qE "\[BOTTEST\] bot_path_reach (PASS|FAIL)" "$SERVER_LOG" 2>/dev/null \
       && grep -qE "\[BOTTEST\] bot_path_blocked (PASS|FAIL)" "$SERVER_LOG" 2>/dev/null; then
        break
    fi
    if ! kill -0 "$SRV_PID" 2>/dev/null; then break; fi
    sleep 2
done

if kill -0 "$SRV_PID" 2>/dev/null; then
    kill "$SRV_PID" 2>/dev/null; sleep 3; kill -9 "$SRV_PID" 2>/dev/null
fi

result_of() {
    local name="$1"
    if grep -qE "\[BOTTEST\] ${name} PASS" "$SERVER_LOG" 2>/dev/null; then echo "PASS"
    elif grep -qE "\[BOTTEST\] ${name} FAIL" "$SERVER_LOG" 2>/dev/null; then echo "FAIL"
    else echo "absent"; fi
}

R_REACH=$(result_of bot_path_reach)
R_BLOCKED=$(result_of bot_path_blocked)
echo "    bot_path_reach = $R_REACH"
echo "    bot_path_blocked = $R_BLOCKED"

echo ">>> [T2.3] judge"
PASS=1
[ "$BUILD_EXIT" -eq 0 ]     || PASS=0
[ "$R_REACH" = "PASS" ]     || PASS=0
[ "$R_BLOCKED" = "PASS" ]   || PASS=0

MEASURED="build:${BUILD_EXIT},reach:${R_REACH},blocked:${R_BLOCKED}"
EXPECTED="build==0 AND bot_path_reach PASS (reached goal) AND bot_path_blocked PASS (null path)"

if [ "$PASS" -eq 1 ]; then
    echo "[BOTTEST] T2.3 PASS measured=${MEASURED} expected=${EXPECTED}"
    exit 0
else
    echo "[BOTTEST] T2.3 FAIL measured=${MEASURED} expected=${EXPECTED}"
    exit 1
fi
