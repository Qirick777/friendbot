#!/usr/bin/env bash
#
# verify_t44.sh — T4.4 환경 조작(낙하 생존 + 크리퍼 벽) 검증
#
# [검증] (AI_Bot_Design.md T4.4):
#   (a) 봇 높은 곳 낙하+물통 → 착지 낙하 데미지 0(체력 불변).
#   (b) 유저 옆 크리퍼 점화+블록 지급 → 크리퍼-유저 사이 블록 설치(좌표 블록 변화).
#
# 자동 값검증:
#   bot_fall_water    = 물 설치→착수→체력 불변 (+물 블록 실제 생성 getFluidState + isInWater 관측).
#   bot_fall_nowater  = 대조: 물 없으면 낙하 데미지 발생(물이 원인임 증명).
#   bot_creeper_wall  = 크리퍼-유저 직선상 좌표 air→고체 설치.
#   bot_creeper_lowfuse = fuse 임박 시 설치 안 함 + 방패/이탈(설치만 검증하고 이탈 분기 안 빠뜨림).
#
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
SCRATCH="${SCRATCH:-/tmp/claude-0/-home-user-friendbot/0c645f03-3948-5413-bc6f-b01dd06604d4/scratchpad}"
export JAVA_HOME="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64}"

run_test() {
    local NAME="$1"; local LOG="$SCRATCH/t44_${NAME}.log"
    echo ">>> [T4.4] dev server boot with -PbottestAuto=${NAME}"
    rm -rf run/world; rm -f "$LOG"; mkdir -p run; echo "eula=true" > run/eula.txt
    ( ./gradlew runServer -PbottestAuto="${NAME}" --console=plain > "$LOG" 2>&1 ) &
    local PID=$!
    for i in $(seq 1 150); do
        if grep -qE "\[BOTTEST\] ${NAME} (PASS|FAIL)" "$LOG" 2>/dev/null; then break; fi
        if ! kill -0 "$PID" 2>/dev/null; then break; fi
        sleep 2
    done
    if kill -0 "$PID" 2>/dev/null; then kill "$PID" 2>/dev/null; sleep 3; kill -9 "$PID" 2>/dev/null; fi
    echo "    --- ${NAME} ENV log lines ---"
    grep -E "\[ENV\]" "$LOG" 2>/dev/null | tail -4
    grep -E "\[BOTTEST\] ${NAME}" "$LOG" 2>/dev/null | tail -1
    if grep -qE "\[BOTTEST\] ${NAME} PASS" "$LOG" 2>/dev/null; then return 0; fi
    return 1
}

echo ">>> [T4.4] step 1: ./gradlew build"
./gradlew build --console=plain > "$SCRATCH/t44_build.log" 2>&1
BUILD_EXIT=$?
echo "    build exit=$BUILD_EXIT"

FW="FAIL"; FN="FAIL"; CW="FAIL"; CL="FAIL"
if [ "$BUILD_EXIT" -eq 0 ]; then
    run_test bot_fall_water     && FW="PASS"
    run_test bot_fall_nowater   && FN="PASS"
    run_test bot_creeper_wall   && CW="PASS"
    run_test bot_creeper_lowfuse && CL="PASS"
fi

echo ">>> [T4.4] judge"
PASS=1
[ "$BUILD_EXIT" -eq 0 ] || PASS=0
[ "$FW" = "PASS" ] || PASS=0
[ "$FN" = "PASS" ] || PASS=0
[ "$CW" = "PASS" ] || PASS=0
[ "$CL" = "PASS" ] || PASS=0
MEASURED="build:${BUILD_EXIT},fall_water:${FW},fall_nowater:${FN},creeper_wall:${CW},creeper_lowfuse:${CL}"
EXPECTED="build==0 AND fall_water PASS(체력불변+물) AND fall_nowater PASS(대조 데미지) AND creeper_wall PASS(air->고체 선분상) AND creeper_lowfuse PASS(설치안함+이탈)"
if [ "$PASS" -eq 1 ]; then
    echo "[BOTTEST] T4.4 PASS measured=${MEASURED} expected=${EXPECTED}"; exit 0
else
    echo "[BOTTEST] T4.4 FAIL measured=${MEASURED} expected=${EXPECTED}"; exit 1
fi
