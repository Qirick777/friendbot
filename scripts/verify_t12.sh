#!/usr/bin/env bash
#
# verify_t12.sh — T1.2 라이프사이클 검증 (3개 서브테스트)
#
# [검증] (AI_Bot_Design.md T1.2):
#   1. 에그 2회 사용 → 2번째 거부, 봇 수 == 1.            (판정: 봇 카운트 == 1)
#   2. 표식 넣고 봇 킬 → 드롭 엔티티 존재 AND botExists == false.
#   3. 봇 소환 후 월드 리로드 → 같은 UUID로 재존재.        (판정: 리로드 후 UUID 일치)
#
# 각 서브테스트는 dev 서버를 구동해 로그의 [BOTTEST] <name> PASS 라인 존재로 판정한다
# (결과=상태값 변동, R.2 "호출≠성공" 준수). 서브3은 실제 재부팅(별 JVM·디스크 왕복)으로
# 저장→로드를 검증한다: bot_persist_save(부팅A) → 같은 월드에서 bot_persist_load(부팅B).
#
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SCRATCH="${SCRATCH:-/tmp/claude-0/-home-user-friendbot/0c645f03-3948-5413-bc6f-b01dd06604d4/scratchpad}"
JAVA17="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64}"
export JAVA_HOME="$JAVA17"

WORLD_DIR="run/world"

# run_subtest <name> <clean_world:0|1> -> echoes PASS/FAIL/absent
run_subtest() {
    local name="$1"; local clean="$2"
    local log="$SCRATCH/t12_${name}.log"
    if [ "$clean" = "1" ]; then rm -rf "$WORLD_DIR"; fi
    rm -f "$log"
    mkdir -p run
    echo "eula=true" > run/eula.txt

    ( ./gradlew runServer -PbottestAuto="$name" --console=plain > "$log" 2>&1 ) &
    local pid=$!

    local result="absent"
    for i in $(seq 1 90); do
        if grep -qE "\[BOTTEST\] ${name} (PASS|FAIL)" "$log" 2>/dev/null; then
            if grep -qE "\[BOTTEST\] ${name} PASS" "$log" 2>/dev/null; then result="PASS"; else result="FAIL"; fi
            break
        fi
        if ! kill -0 "$pid" 2>/dev/null; then break; fi
        sleep 2
    done

    if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null; sleep 3; kill -9 "$pid" 2>/dev/null
    fi
    wait "$pid" 2>/dev/null

    # final re-check from disk
    if grep -qE "\[BOTTEST\] ${name} PASS" "$log" 2>/dev/null; then result="PASS"
    elif grep -qE "\[BOTTEST\] ${name} FAIL" "$log" 2>/dev/null; then result="FAIL"; fi
    echo "$result"
}

echo ">>> [T1.2] step 1: ./gradlew build"
./gradlew build --console=plain > "$SCRATCH/t12_build.log" 2>&1
BUILD_EXIT=$?
echo "    build exit=$BUILD_EXIT"

echo ">>> [T1.2] subtest 1: bot_single (clean world)"
R_SINGLE=$(run_subtest bot_single 1)
echo "    bot_single = $R_SINGLE"

echo ">>> [T1.2] subtest 2: bot_death (clean world)"
R_DEATH=$(run_subtest bot_death 1)
echo "    bot_death = $R_DEATH"

echo ">>> [T1.2] subtest 3A: bot_persist_save (clean world, keep after)"
R_SAVE=$(run_subtest bot_persist_save 1)
echo "    bot_persist_save = $R_SAVE"

echo ">>> [T1.2] subtest 3B: bot_persist_load (SAME world → real reload)"
R_LOAD=$(run_subtest bot_persist_load 0)
echo "    bot_persist_load = $R_LOAD"

echo ">>> [T1.2] judge"
PASS=1
[ "$BUILD_EXIT" -eq 0 ]     || PASS=0
[ "$R_SINGLE" = "PASS" ]    || PASS=0
[ "$R_DEATH" = "PASS" ]     || PASS=0
[ "$R_SAVE" = "PASS" ]      || PASS=0
[ "$R_LOAD" = "PASS" ]      || PASS=0

MEASURED="build:${BUILD_EXIT},single:${R_SINGLE},death:${R_DEATH},persist_save:${R_SAVE},persist_load:${R_LOAD}"
EXPECTED="build==0 AND single/death/persist_save/persist_load all PASS"

if [ "$PASS" -eq 1 ]; then
    echo "[BOTTEST] T1.2 PASS measured=${MEASURED} expected=${EXPECTED}"
    exit 0
else
    echo "[BOTTEST] T1.2 FAIL measured=${MEASURED} expected=${EXPECTED}"
    exit 1
fi
