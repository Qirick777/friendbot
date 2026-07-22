#!/usr/bin/env bash
#
# verify_t21.sh — T2.1 이동 실행기 검증
#
# [검증] (AI_Bot_Design.md T2.1):
#   평지 직진 → 목표 방향 수평거리 감소.  한 칸 블록 앞 지시 → 점프해 y 증가.
#   판정: 목표 방향 수평거리 감소 AND 계단 케이스에서 y 증가.
#
# 두 테스트를 한 부팅에서 순차 실행한다(-PbottestAuto=bot_move_flat,bot_move_stair).
# 각 테스트 setup이 자기 지형(활주로/계단)을 조성하고 봇을 배치한다.
# 판정 근거는 좌표 변동(거리 감소 / y 증가)뿐 (R.2 "호출≠성공").
#
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SCRATCH="${SCRATCH:-/tmp/claude-0/-home-user-friendbot/0c645f03-3948-5413-bc6f-b01dd06604d4/scratchpad}"
JAVA17="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64}"
export JAVA_HOME="$JAVA17"

BUILD_LOG="$SCRATCH/t21_build.log"
SERVER_LOG="$SCRATCH/t21_server.log"

echo ">>> [T2.1] step 1: ./gradlew build"
./gradlew build --console=plain > "$BUILD_LOG" 2>&1
BUILD_EXIT=$?
echo "    build exit=$BUILD_EXIT"

echo ">>> [T2.1] step 2: dev server boot with bot_move_flat,bot_move_stair"
rm -rf run/world
rm -f "$SERVER_LOG"
mkdir -p run
echo "eula=true" > run/eula.txt
( ./gradlew runServer -PbottestAuto=bot_move_flat,bot_move_stair --console=plain > "$SERVER_LOG" 2>&1 ) &
SRV_PID=$!

for i in $(seq 1 150); do
    # both verdicts printed?
    if grep -qE "\[BOTTEST\] bot_move_flat (PASS|FAIL)" "$SERVER_LOG" 2>/dev/null \
       && grep -qE "\[BOTTEST\] bot_move_stair (PASS|FAIL)" "$SERVER_LOG" 2>/dev/null; then
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

R_FLAT=$(result_of bot_move_flat)
R_STAIR=$(result_of bot_move_stair)
echo "    bot_move_flat = $R_FLAT"
echo "    bot_move_stair = $R_STAIR"

echo ">>> [T2.1] judge"
PASS=1
[ "$BUILD_EXIT" -eq 0 ] || PASS=0
[ "$R_FLAT" = "PASS" ]  || PASS=0
[ "$R_STAIR" = "PASS" ] || PASS=0

MEASURED="build:${BUILD_EXIT},flat:${R_FLAT},stair:${R_STAIR}"
EXPECTED="build==0 AND bot_move_flat PASS (dist decreased) AND bot_move_stair PASS (y increased)"

if [ "$PASS" -eq 1 ]; then
    echo "[BOTTEST] T2.1 PASS measured=${MEASURED} expected=${EXPECTED}"
    exit 0
else
    echo "[BOTTEST] T2.1 FAIL measured=${MEASURED} expected=${EXPECTED}"
    exit 1
fi
