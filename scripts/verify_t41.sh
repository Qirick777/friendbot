#!/usr/bin/env bash
#
# verify_t41.sh — T4.1 생존 상태머신(이탈·회복·펄) 검증
#
# [검증] (AI_Bot_Design.md T4.1):
#   봇 체력을 위험선 아래로 강제 + 적 배치 → 봇이 교전 중단하고 후퇴/펄 이탈(봇↔적 거리 증가).
#   회복아이템 지급 시 사용해 체력 증가. 판정: 봇-적 거리 증가 AND 회복아이템 보유 시 체력 증가.
#
# 자동 값검증 2종:
#   bot_survival = RETREAT_HEAL 경로. 근접 타겟이 있는데도 survival이 오버라이드해 후퇴(거리↑)하고
#                  황금사과로 체력↑. 판정 근거는 거리·체력 값 변동뿐 ("모드 진입"이 아님, R.2).
#   bot_pearl    = PEARL_ESCAPE 경로. 봇이 실제로 펄을 던져 텔레포트하는지(변위>4) + 성립 메커니즘
#                  (vanilla/fallback) 로그로 확정 — connection 게이트가 막히면 teleportTo 폴백.
#
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
SCRATCH="${SCRATCH:-/tmp/claude-0/-home-user-friendbot/0c645f03-3948-5413-bc6f-b01dd06604d4/scratchpad}"
export JAVA_HOME="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64}"

run_test() {
    local NAME="$1"; local LOG="$SCRATCH/t41_${NAME}.log"
    echo ">>> [T4.1] dev server boot with -PbottestAuto=${NAME}"
    rm -rf run/world; rm -f "$LOG"; mkdir -p run; echo "eula=true" > run/eula.txt
    ( ./gradlew runServer -PbottestAuto="${NAME}" --console=plain > "$LOG" 2>&1 ) &
    local PID=$!
    for i in $(seq 1 150); do
        if grep -qE "\[BOTTEST\] ${NAME} (PASS|FAIL)" "$LOG" 2>/dev/null; then break; fi
        if ! kill -0 "$PID" 2>/dev/null; then break; fi
        sleep 2
    done
    if kill -0 "$PID" 2>/dev/null; then kill "$PID" 2>/dev/null; sleep 3; kill -9 "$PID" 2>/dev/null; fi
    echo "    --- ${NAME} SURVIVAL/PEARL log lines ---"
    grep -E "\[SURVIVAL\]" "$LOG" 2>/dev/null | tail -6
    grep -E "\[BOTTEST\] ${NAME}" "$LOG" 2>/dev/null | tail -1
    if grep -qE "\[BOTTEST\] ${NAME} PASS" "$LOG" 2>/dev/null; then return 0; fi
    return 1
}

echo ">>> [T4.1] step 1: ./gradlew build"
./gradlew build --console=plain > "$SCRATCH/t41_build.log" 2>&1
BUILD_EXIT=$?
echo "    build exit=$BUILD_EXIT"

SURV="FAIL"; PEARL="FAIL"
if [ "$BUILD_EXIT" -eq 0 ]; then
    run_test bot_survival && SURV="PASS"
    run_test bot_pearl     && PEARL="PASS"
fi

echo ">>> [T4.1] judge"
PASS=1
[ "$BUILD_EXIT" -eq 0 ] || PASS=0
[ "$SURV" = "PASS" ]    || PASS=0
[ "$PEARL" = "PASS" ]   || PASS=0
MEASURED="build:${BUILD_EXIT},bot_survival:${SURV},bot_pearl:${PEARL}"
EXPECTED="build==0 AND bot_survival PASS (거리↑+체력↑) AND bot_pearl PASS (텔레포트 성립)"
if [ "$PASS" -eq 1 ]; then
    echo "[BOTTEST] T4.1 PASS measured=${MEASURED} expected=${EXPECTED}"; exit 0
else
    echo "[BOTTEST] T4.1 FAIL measured=${MEASURED} expected=${EXPECTED}"; exit 1
fi
