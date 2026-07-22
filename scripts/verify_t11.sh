#!/usr/bin/env bash
#
# verify_t11.sh — T1.1 상주 가짜 플레이어 검증
#
# [검증] (AI_Bot_Design.md T1.1):
#   봇 스폰 후 N틱 동안 tickCount 증가 + 배고픔 값 바닐라대로 변화 관찰.
#   판정: 스폰 직후 tickCount와 N틱 후 tickCount 비교해 증가 AND 봇 엔티티가 월드에 존재(null 아님).
#
# 판정(결과=상태값 변동, "호출됨=성공" 금지):
#   - BUILD_EXIT : ./gradlew build 종료 코드 (기대 0)
#   - ALIVE_LINE : dev 서버 로그에 "[BOTTEST] bot_alive PASS" 존재 (absent -> present)
#                  → BotAliveTest 가 exists AND tickCount_increased 를 측정해 판정.
#
# 실행 경로: dev 서버를 `-PbottestAuto=bot_alive`로 기동 → ServerStartedEvent 훅이 큐잉
#            → BotAliveTest.setup 에서 봇 스폰(더미 Connection + placeNewPlayer)
#            → 40틱 관찰 → judge(tickCount before→after).
#
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SCRATCH="${SCRATCH:-/tmp/claude-0/-home-user-friendbot/0c645f03-3948-5413-bc6f-b01dd06604d4/scratchpad}"
JAVA17="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64}"
export JAVA_HOME="$JAVA17"

EXPECT_LINE="[BOTTEST] bot_alive PASS"

BUILD_LOG="$SCRATCH/t11_build.log"
SERVER_LOG="$SCRATCH/t11_server.log"

echo ">>> [T1.1] step 1: ./gradlew build"
./gradlew build --console=plain > "$BUILD_LOG" 2>&1
BUILD_EXIT=$?
echo "    build exit=$BUILD_EXIT"

echo ">>> [T1.1] step 2: dev server boot with -PbottestAuto=bot_alive"
rm -f "$SERVER_LOG"
mkdir -p run
echo "eula=true" > run/eula.txt
( ./gradlew runServer -PbottestAuto=bot_alive --console=plain > "$SERVER_LOG" 2>&1 ) &
SRV_PID=$!

ALIVE_FOUND=0
for i in $(seq 1 180); do
    if grep -qF "$EXPECT_LINE" "$SERVER_LOG" 2>/dev/null; then ALIVE_FOUND=1; fi
    # 판정 라인(PASS 또는 FAIL)이 찍히면 즉시 종료
    if grep -qE '\[BOTTEST\] bot_alive (PASS|FAIL)' "$SERVER_LOG" 2>/dev/null; then break; fi
    if ! kill -0 "$SRV_PID" 2>/dev/null; then break; fi
    sleep 2
done

# 서버 정지
if kill -0 "$SRV_PID" 2>/dev/null; then
    kill "$SRV_PID" 2>/dev/null
    sleep 3
    kill -9 "$SRV_PID" 2>/dev/null
fi

if grep -qF "$EXPECT_LINE" "$SERVER_LOG" 2>/dev/null; then ALIVE_FOUND=1; fi

ALIVE_STATE="absent"
if [ "$ALIVE_FOUND" -eq 1 ]; then ALIVE_STATE="present"; fi

echo ">>> [T1.1] judge"
PASS=1
[ "$BUILD_EXIT" -eq 0 ] || PASS=0
[ "$ALIVE_FOUND" -eq 1 ] || PASS=0

MEASURED="build_exit:${BUILD_EXIT},bot_alive_pass:${ALIVE_STATE}"
EXPECTED="build_exit==0 AND '[BOTTEST] bot_alive PASS' present"

if [ "$PASS" -eq 1 ]; then
    echo "[BOTTEST] T1.1 PASS measured=${MEASURED} expected=${EXPECTED}"
    exit 0
else
    echo "[BOTTEST] T1.1 FAIL measured=${MEASURED} expected=${EXPECTED}"
    exit 1
fi
