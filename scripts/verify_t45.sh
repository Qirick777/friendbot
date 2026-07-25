#!/usr/bin/env bash
#
# verify_t45.sh — T4.5 도주 모드 + 유저 낙하 받기(탑승) 검증
#
# [검증] (AI_Bot_Design.md T4.5):
#   (a) 유저 15%↓ + 회복포션 없음 + 적 위협 → 봇이 유저 탑승(유저.getVehicle()==봇).
#   (b) 유저를 봇 위로 낙하 → 봇이 받아 유저 낙하데미지 0(체력 불변).
#
# 자동 값검증:
#   bot_escape_ride = vehicle==bot AND passengers 포함 AND 탑승 후 유저가 봇 따라 >3블록 이동
#                     (관계만 맺고 안 움직이는 반쪽 탑승 방지).
#   bot_escape_none = 대조: 유저 >15%면 탑승 미발동(getVehicle 계속 null).
#   bot_catch_fall  = 유저 실낙하(doTick+doCheckFallDamage 구동) → 봇이 받음 → 체력 불변.
#   bot_catch_none  = 대조: 봇 멀리 → 같은 낙하가 실데미지(받기가 원인임 증명).
#
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
SCRATCH="${SCRATCH:-/tmp/claude-0/-home-user-friendbot/0c645f03-3948-5413-bc6f-b01dd06604d4/scratchpad}"
export JAVA_HOME="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64}"

run_test() {
    local NAME="$1"; local LOG="$SCRATCH/t45_${NAME}.log"
    echo ">>> [T4.5] dev server boot with -PbottestAuto=${NAME}"
    rm -rf run/world; rm -f "$LOG"; mkdir -p run; echo "eula=true" > run/eula.txt
    ( ./gradlew runServer -PbottestAuto="${NAME}" --console=plain > "$LOG" 2>&1 ) &
    local PID=$!
    for i in $(seq 1 150); do
        if grep -qE "\[BOTTEST\] ${NAME} (PASS|FAIL)" "$LOG" 2>/dev/null; then break; fi
        if ! kill -0 "$PID" 2>/dev/null; then break; fi
        sleep 2
    done
    if kill -0 "$PID" 2>/dev/null; then kill "$PID" 2>/dev/null; sleep 3; kill -9 "$PID" 2>/dev/null; fi
    echo "    --- ${NAME} RESCUE log lines ---"
    grep -E "\[RESCUE\]" "$LOG" 2>/dev/null | tail -4
    grep -E "\[BOTTEST\] ${NAME}" "$LOG" 2>/dev/null | tail -1
    if grep -qE "\[BOTTEST\] ${NAME} PASS" "$LOG" 2>/dev/null; then return 0; fi
    return 1
}

echo ">>> [T4.5] step 1: ./gradlew build"
./gradlew build --console=plain > "$SCRATCH/t45_build.log" 2>&1
BUILD_EXIT=$?
echo "    build exit=$BUILD_EXIT"

ER="FAIL"; EN="FAIL"; CF="FAIL"; CN="FAIL"
if [ "$BUILD_EXIT" -eq 0 ]; then
    run_test bot_escape_ride && ER="PASS"
    run_test bot_escape_none && EN="PASS"
    run_test bot_catch_fall  && CF="PASS"
    run_test bot_catch_none  && CN="PASS"
fi

echo ">>> [T4.5] judge"
PASS=1
[ "$BUILD_EXIT" -eq 0 ] || PASS=0
[ "$ER" = "PASS" ] || PASS=0
[ "$EN" = "PASS" ] || PASS=0
[ "$CF" = "PASS" ] || PASS=0
[ "$CN" = "PASS" ] || PASS=0
MEASURED="build:${BUILD_EXIT},escape_ride:${ER},escape_none:${EN},catch_fall:${CF},catch_none:${CN}"
EXPECTED="build==0 AND escape_ride PASS(vehicle==bot+이동추종) AND escape_none PASS(>15% 미발동) AND catch_fall PASS(받기+체력불변) AND catch_none PASS(대조 데미지)"
if [ "$PASS" -eq 1 ]; then
    echo "[BOTTEST] T4.5 PASS measured=${MEASURED} expected=${EXPECTED}"; exit 0
else
    echo "[BOTTEST] T4.5 FAIL measured=${MEASURED} expected=${EXPECTED}"; exit 1
fi
