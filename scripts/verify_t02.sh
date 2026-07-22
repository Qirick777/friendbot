#!/usr/bin/env bash
#
# verify_t02.sh — T0.2 검증 하네스 검증
#
# [검증] (AI_Bot_Design.md T0.2):
#   더미 테스트("2초 후 무조건 PASS, 측정값 42 출력")를 실행해 로그에
#   "[BOTTEST] dummy PASS measured=42" 가 뜨는지.  판정: 로그에 해당 형식 라인 존재.
#
# 판정(결과=로그 라인 존재, "호출됨=성공" 금지):
#   - BUILD_EXIT   : ./gradlew build 종료 코드 (기대 0)
#   - DUMMY_LINE   : dev 서버 로그에 "[BOTTEST] dummy PASS measured=42" 존재 (absent -> present)
#
# 실행 경로: dev 서버를 `-PbottestAuto=dummy`로 기동 → ServerStartedEvent 훅이
#            dummy 를 큐잉 → 서버틱 드라이버가 setup→tick(40t)→judge → 로그 출력.
#
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SCRATCH="${SCRATCH:-/tmp/claude-0/-home-user-friendbot/0c645f03-3948-5413-bc6f-b01dd06604d4/scratchpad}"
JAVA17="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64}"
export JAVA_HOME="$JAVA17"

EXPECT_LINE="[BOTTEST] dummy PASS measured=42"

BUILD_LOG="$SCRATCH/t02_build.log"
SERVER_LOG="$SCRATCH/t02_server.log"

echo ">>> [T0.2] step 1: ./gradlew build"
./gradlew build --console=plain > "$BUILD_LOG" 2>&1
BUILD_EXIT=$?
echo "    build exit=$BUILD_EXIT"

echo ">>> [T0.2] step 2: dev server boot with -PbottestAuto=dummy"
rm -f "$SERVER_LOG"
mkdir -p run
echo "eula=true" > run/eula.txt
( ./gradlew runServer -PbottestAuto=dummy --console=plain > "$SERVER_LOG" 2>&1 ) &
SRV_PID=$!

DUMMY_FOUND=0
BOOTED=0
for i in $(seq 1 180); do
    if grep -qF "$EXPECT_LINE" "$SERVER_LOG" 2>/dev/null; then DUMMY_FOUND=1; fi
    if grep -qE 'Done \(|For help, type' "$SERVER_LOG" 2>/dev/null; then BOOTED=1; fi
    # dummy PASS 라인을 잡으면 즉시 종료
    if [ "$DUMMY_FOUND" -eq 1 ]; then break; fi
    if ! kill -0 "$SRV_PID" 2>/dev/null; then break; fi
    sleep 2
done

# 서버 정지
if kill -0 "$SRV_PID" 2>/dev/null; then
    kill "$SRV_PID" 2>/dev/null
    sleep 3
    kill -9 "$SRV_PID" 2>/dev/null
fi

# 최종 재확인 (로그 파일 기준)
if grep -qF "$EXPECT_LINE" "$SERVER_LOG" 2>/dev/null; then DUMMY_FOUND=1; fi

DUMMY_STATE="absent"
if [ "$DUMMY_FOUND" -eq 1 ]; then DUMMY_STATE="present"; fi

echo ">>> [T0.2] judge"
PASS=1
[ "$BUILD_EXIT" -eq 0 ] || PASS=0
[ "$DUMMY_FOUND" -eq 1 ] || PASS=0

MEASURED="build_exit:${BUILD_EXIT},dummy_line:${DUMMY_STATE}"
EXPECTED="build_exit==0 AND '[BOTTEST] dummy PASS measured=42' present"

if [ "$PASS" -eq 1 ]; then
    echo "[BOTTEST] T0.2 PASS measured=${MEASURED} expected=${EXPECTED}"
    exit 0
else
    echo "[BOTTEST] T0.2 FAIL measured=${MEASURED} expected=${EXPECTED}"
    exit 1
fi
