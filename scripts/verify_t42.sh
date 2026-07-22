#!/usr/bin/env bash
#
# verify_t42.sh — T4.2 반사 계층(토템·방패·투사체 회피) 검증
#
# [검증] (AI_Bot_Design.md T4.2):
#   (a) 봇 체력 낮추고 강한 근접 적 접근 → 봇 오프핸드에 토템 장착(슬롯 확인).
#   (b) 스켈레톤이 봇에 화살 발사 → 봇이 사이드스텝해 회피(화살 명중 안 함, 봇 체력 불변).
#   판정: (a) 오프핸드 아이템==토템, (b) 화살 발사 후 봇 체력 불변.
#
# 자동 값검증:
#   bot_totem  = R0. 오프핸드 슬롯 empty->totem_of_undying 값 변동. (설계 [검증] a)
#   bot_dodge  = R1 사이드스텝. 화살 발사(>=3) AND 봇 체력 불변(피격0) AND 측면 변위>1. (설계 [검증] b)
#   bot_shield = R1 방패 올림 확인용. isBlocking()==true 값 확인(judge 게이트 외 X4 값확인).
#
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
SCRATCH="${SCRATCH:-/tmp/claude-0/-home-user-friendbot/0c645f03-3948-5413-bc6f-b01dd06604d4/scratchpad}"
export JAVA_HOME="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64}"

run_test() {
    local NAME="$1"; local LOG="$SCRATCH/t42_${NAME}.log"
    echo ">>> [T4.2] dev server boot with -PbottestAuto=${NAME}"
    rm -rf run/world; rm -f "$LOG"; mkdir -p run; echo "eula=true" > run/eula.txt
    ( ./gradlew runServer -PbottestAuto="${NAME}" --console=plain > "$LOG" 2>&1 ) &
    local PID=$!
    for i in $(seq 1 150); do
        if grep -qE "\[BOTTEST\] ${NAME} (PASS|FAIL)" "$LOG" 2>/dev/null; then break; fi
        if ! kill -0 "$PID" 2>/dev/null; then break; fi
        sleep 2
    done
    if kill -0 "$PID" 2>/dev/null; then kill "$PID" 2>/dev/null; sleep 3; kill -9 "$PID" 2>/dev/null; fi
    echo "    --- ${NAME} REFLEX log lines ---"
    grep -E "\[REFLEX\]" "$LOG" 2>/dev/null | tail -5
    grep -E "\[BOTTEST\] ${NAME}" "$LOG" 2>/dev/null | tail -1
    if grep -qE "\[BOTTEST\] ${NAME} PASS" "$LOG" 2>/dev/null; then return 0; fi
    return 1
}

echo ">>> [T4.2] step 1: ./gradlew build"
./gradlew build --console=plain > "$SCRATCH/t42_build.log" 2>&1
BUILD_EXIT=$?
echo "    build exit=$BUILD_EXIT"

TOTEM="FAIL"; DODGE="FAIL"; SHIELD="FAIL"
if [ "$BUILD_EXIT" -eq 0 ]; then
    run_test bot_totem  && TOTEM="PASS"
    run_test bot_dodge  && DODGE="PASS"
    run_test bot_shield && SHIELD="PASS"
fi

echo ">>> [T4.2] judge"
PASS=1
[ "$BUILD_EXIT" -eq 0 ] || PASS=0
[ "$TOTEM" = "PASS" ]   || PASS=0
[ "$DODGE" = "PASS" ]   || PASS=0
[ "$SHIELD" = "PASS" ]  || PASS=0
MEASURED="build:${BUILD_EXIT},bot_totem:${TOTEM},bot_dodge:${DODGE},bot_shield:${SHIELD}"
EXPECTED="build==0 AND bot_totem PASS (오프핸드==토템) AND bot_dodge PASS (발사+무피격+사이드스텝) AND bot_shield PASS (isBlocking)"
if [ "$PASS" -eq 1 ]; then
    echo "[BOTTEST] T4.2 PASS measured=${MEASURED} expected=${EXPECTED}"; exit 0
else
    echo "[BOTTEST] T4.2 FAIL measured=${MEASURED} expected=${EXPECTED}"; exit 1
fi
