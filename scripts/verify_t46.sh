#!/usr/bin/env bash
#
# verify_t46.sh — T4.6 특수 케이스 데이터(워든) + 소닉 차징 반사 검증
#
# [검증] (AI_Bot_Design.md T4.6):
#   워든 스폰(또는 워든 스탯 더미) → 봇 전술 판정 덤프가 {카이팅=true, 원거리강제, 밴드16~20, 방패off}인지.
#   소닉 차징 상태 유발 → 봇이 밴드 밖으로 이탈하는지(봇-워든 거리 증가).
#   판정: 전술 판정 4값 일치 AND 차징 시 봇-워든 거리 증가.
#
# 하니스:
#   bot_warden_tactics    = [검증] 4값.
#   bot_warden_noname     = (가)① 워든 스탯+프로필을 좀비에 주입 → 같은 4값 (이름 무관 증명).
#   bot_warden_generic    = (가)② 워든에 armorPiercing만 false → 방패 하나만 뒤집힘 (원인 격리).
#   bot_warden_band       = (2a) 비차징 시 밴드 16~20 유지 (거리 증가의 허위 PASS 차단).
#   bot_warden_charge     = (2b) 차징 자연 발생 → 34틱 내 15블록 밖 이탈 + 소닉 데미지 0.
#   bot_warden_charge_none= (2c) 대조: 이탈 억제 → 고정 10 데미지 피격.
#
# T3.2/T3.3 회귀(규칙 입력 배관 교체)는 bot_tactics·bot_melee 재실행으로 확인한다.
#
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
SCRATCH="${SCRATCH:-/tmp/claude-0/-home-user-friendbot/0c645f03-3948-5413-bc6f-b01dd06604d4/scratchpad}"
export JAVA_HOME="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64}"

run_test() {
    local NAME="$1"; local LOG="$SCRATCH/t46_${NAME}.log"
    echo ">>> [T4.6] dev server boot with -PbottestAuto=${NAME}"
    rm -rf run/world; rm -f "$LOG"; mkdir -p run; echo "eula=true" > run/eula.txt
    ( ./gradlew runServer -PbottestAuto="${NAME}" --console=plain > "$LOG" 2>&1 ) &
    local PID=$!
    for i in $(seq 1 170); do
        if grep -qE "\[BOTTEST\] ${NAME} (PASS|FAIL)" "$LOG" 2>/dev/null; then break; fi
        if ! kill -0 "$PID" 2>/dev/null; then break; fi
        sleep 2
    done
    if kill -0 "$PID" 2>/dev/null; then kill "$PID" 2>/dev/null; sleep 3; kill -9 "$PID" 2>/dev/null; fi
    grep -E "\[WARDEN\]|\[WARDENPROBE\]" "$LOG" 2>/dev/null | tail -3
    grep -E "\[BOTTEST\] ${NAME}" "$LOG" 2>/dev/null | tail -1
    if grep -qE "\[BOTTEST\] ${NAME} PASS" "$LOG" 2>/dev/null; then return 0; fi
    return 1
}

echo ">>> [T4.6] step 1: ./gradlew build"
./gradlew build --console=plain > "$SCRATCH/t46_build.log" 2>&1
BUILD_EXIT=$?
echo "    build exit=$BUILD_EXIT"

TAC="FAIL"; NON="FAIL"; GEN="FAIL"; BAND="FAIL"; CHG="FAIL"; CHGN="FAIL"; REG_T="FAIL"; REG_M="FAIL"
if [ "$BUILD_EXIT" -eq 0 ]; then
    # regression first — a break here blocks T4.6 entirely
    run_test bot_tactics && REG_T="PASS"
    run_test bot_melee   && REG_M="PASS"
    run_test bot_warden_tactics     && TAC="PASS"
    run_test bot_warden_noname      && NON="PASS"
    run_test bot_warden_generic     && GEN="PASS"
    run_test bot_warden_band        && BAND="PASS"
    run_test bot_warden_charge      && CHG="PASS"
    run_test bot_warden_charge_none && CHGN="PASS"
fi

echo ">>> [T4.6] judge"
PASS=1
[ "$BUILD_EXIT" -eq 0 ] || PASS=0
for v in "$REG_T" "$REG_M" "$TAC" "$NON" "$GEN" "$BAND" "$CHG" "$CHGN"; do
    [ "$v" = "PASS" ] || PASS=0
done
MEASURED="build:${BUILD_EXIT},regr_tactics:${REG_T},regr_melee:${REG_M},warden_tactics:${TAC},noname:${NON},generic:${GEN},band:${BAND},charge:${CHG},charge_none:${CHGN}"
EXPECTED="build==0 AND T3.2/T3.3 회귀 없음 AND 4값 일치 AND 이름무관(noname 동일/generic 방패만 반전) AND 비차징 밴드유지 AND 차징 이탈+무피해 AND 대조 10데미지"
if [ "$PASS" -eq 1 ]; then
    echo "[BOTTEST] T4.6 PASS measured=${MEASURED} expected=${EXPECTED}"; exit 0
else
    echo "[BOTTEST] T4.6 FAIL measured=${MEASURED} expected=${EXPECTED}"; exit 1
fi
