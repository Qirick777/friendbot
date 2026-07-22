#!/usr/bin/env bash
#
# verify_t01.sh — T0.1 개발 환경 셋업 검증
#
# [검증] (AI_Bot_Design.md T0.1):
#   "./gradlew build 성공 + dev 서버 기동 시 로그에 모드 로드 라인 존재.
#    판정: 빌드 exit code 0 AND 로그에 모드ID 문자열 존재."
#
# 이 스크립트는 "호출됨=성공"이 아니라 결과 변동/상태로 판정한다:
#   - BUILD_EXIT : ./gradlew build 종료 코드 (기대 0)
#   - JDK_USED   : 컴파일 툴체인 Java 메이저 버전 (기대 17; 21이면 FAIL — 설계서 위배)
#   - MODID_LINE : dev 서버 로그에 모드 로드 라인 존재 여부 (absent -> present)
#
# 출력: [BOTTEST] T0.1 PASS/FAIL measured=<...> expected=<...>
#
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SCRATCH="${SCRATCH:-/tmp/claude-0/-home-user-friendbot/0c645f03-3948-5413-bc6f-b01dd06604d4/scratchpad}"
JAVA17="${JAVA17:-/usr/lib/jvm/java-17-openjdk-amd64}"
export JAVA_HOME="$JAVA17"

MODID="aicompanion"
LOADLINE="[BOTLOAD] ${MODID} mod loaded"

BUILD_LOG="$SCRATCH/t01_build.log"
SERVER_LOG="$SCRATCH/t01_server.log"

echo ">>> [T0.1] step 1: ./gradlew build"
./gradlew build --console=plain > "$BUILD_LOG" 2>&1
BUILD_EXIT=$?
echo "    build exit=$BUILD_EXIT"

# 툴체인 Java 버전 확인: 빌드 로그에 없으면 별도 태스크로 측정
echo ">>> [T0.1] step 2: verify compile toolchain is JDK 17"
JDK_USED=$(./gradlew -q printJavaVersion 2>/dev/null | tail -1)
if [ -z "$JDK_USED" ]; then JDK_USED="unknown"; fi
echo "    toolchain java major=$JDK_USED"

echo ">>> [T0.1] step 3: dev server boot + capture mod load line"
# runServer 를 백그라운드로 띄우고, 로드 라인이 뜨거나 서버가 'Done'에 도달하면 stop.
rm -f "$SERVER_LOG"
mkdir -p run
echo "eula=true" > run/eula.txt
( ./gradlew runServer --console=plain > "$SERVER_LOG" 2>&1 ) &
SRV_PID=$!

MODID_FOUND=0
DONE=0
for i in $(seq 1 180); do
    if grep -qF "$LOADLINE" "$SERVER_LOG" 2>/dev/null; then MODID_FOUND=1; fi
    # 서버가 완전히 기동했음을 알리는 라인
    if grep -qE 'Done \(|For help, type|Stopping server' "$SERVER_LOG" 2>/dev/null; then DONE=1; fi
    if [ "$MODID_FOUND" -eq 1 ] && [ "$DONE" -eq 1 ]; then break; fi
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
if grep -qF "$LOADLINE" "$SERVER_LOG" 2>/dev/null; then MODID_FOUND=1; fi

MODID_STATE="absent"
if [ "$MODID_FOUND" -eq 1 ]; then MODID_STATE="present"; fi

echo ">>> [T0.1] judge"
PASS=1
[ "$BUILD_EXIT" -eq 0 ]        || PASS=0
[ "$MODID_FOUND" -eq 1 ]       || PASS=0
[ "$JDK_USED" = "17" ]         || PASS=0

MEASURED="exit:${BUILD_EXIT},jdk:${JDK_USED},modid:${MODID_STATE}"
EXPECTED="exit==0 AND jdk==17 AND modid_line_present"

if [ "$PASS" -eq 1 ]; then
    echo "[BOTTEST] T0.1 PASS measured=${MEASURED} expected=${EXPECTED}"
    exit 0
else
    echo "[BOTTEST] T0.1 FAIL measured=${MEASURED} expected=${EXPECTED}"
    exit 1
fi
