#!/usr/bin/env bash
#
# verify_t43.sh — T4.3 유저 보호 프로토콜(개입·타겟팅) 검증
#
# [검증] (AI_Bot_Design.md T4.3):
#   (a) 유저 옆 좀비 어그로 → 봇 선제 처치(좀비 체력 감소 시작).
#   (b) 고위험·저위험 동시 배치 + 유저 정상 → 봇이 고위험부터(고위험 체력 먼저 감소).
#
# 자동 값검증 (유저>40% 최고위험 분기 + 개입 O/X):
#   bot_protect_intervene = 어그로 좀비 체력 감소(개입) AND 중립 몹 체력 불변(U1 선제공격 금지).
#                           + 가짜 플레이어 유저를 몹이 자연 타게팅하는지 로그 확인.
#   bot_protect_priority  = 고위험 적이 죽을 때까지 저위험 적 무피해(우연 아닌 우선순위 결과).
#
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
SCRATCH="${SCRATCH:-/tmp/claude-0/-home-user-friendbot/0c645f03-3948-5413-bc6f-b01dd06604d4/scratchpad}"
export JAVA_HOME="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64}"

run_test() {
    local NAME="$1"; local LOG="$SCRATCH/t43_${NAME}.log"
    echo ">>> [T4.3] dev server boot with -PbottestAuto=${NAME}"
    rm -rf run/world; rm -f "$LOG"; mkdir -p run; echo "eula=true" > run/eula.txt
    ( ./gradlew runServer -PbottestAuto="${NAME}" --console=plain > "$LOG" 2>&1 ) &
    local PID=$!
    for i in $(seq 1 170); do
        if grep -qE "\[BOTTEST\] ${NAME} (PASS|FAIL)" "$LOG" 2>/dev/null; then break; fi
        if ! kill -0 "$PID" 2>/dev/null; then break; fi
        sleep 2
    done
    if kill -0 "$PID" 2>/dev/null; then kill "$PID" 2>/dev/null; sleep 3; kill -9 "$PID" 2>/dev/null; fi
    echo "    --- ${NAME} PROTECT log lines ---"
    grep -E "\[PROTECT\]" "$LOG" 2>/dev/null | tail -4
    grep -E "\[BOTTEST\] ${NAME}" "$LOG" 2>/dev/null | tail -1
    if grep -qE "\[BOTTEST\] ${NAME} PASS" "$LOG" 2>/dev/null; then return 0; fi
    return 1
}

echo ">>> [T4.3] step 1: ./gradlew build"
./gradlew build --console=plain > "$SCRATCH/t43_build.log" 2>&1
BUILD_EXIT=$?
echo "    build exit=$BUILD_EXIT"

INTV="FAIL"; PRIO="FAIL"
if [ "$BUILD_EXIT" -eq 0 ]; then
    run_test bot_protect_intervene && INTV="PASS"
    run_test bot_protect_priority  && PRIO="PASS"
fi

echo ">>> [T4.3] judge"
PASS=1
[ "$BUILD_EXIT" -eq 0 ] || PASS=0
[ "$INTV" = "PASS" ]    || PASS=0
[ "$PRIO" = "PASS" ]    || PASS=0
MEASURED="build:${BUILD_EXIT},intervene:${INTV},priority:${PRIO}"
EXPECTED="build==0 AND intervene PASS (개입O+중립X) AND priority PASS (고위험 죽을때까지 저위험 무피해)"
if [ "$PASS" -eq 1 ]; then
    echo "[BOTTEST] T4.3 PASS measured=${MEASURED} expected=${EXPECTED}"; exit 0
else
    echo "[BOTTEST] T4.3 FAIL measured=${MEASURED} expected=${EXPECTED}"; exit 1
fi
