# 개발 환경 기록 (T0.1 산출)

> 이후 모든 태스크는 이 문서의 경로/명령을 근거로 실제 1.20.1 클래스 시그니처를 참조한다.
> 추측 금지 — 반드시 아래 디컴파일 소스의 실제 시그니처를 본다.

## 툴체인 (고정)

| 항목 | 값 |
|---|---|
| JDK (컴파일 툴체인) | **17** (`/usr/lib/jvm/java-17-openjdk-amd64`). 21 사용 금지 — 설계서상 빌드 실패. |
| Gradle | 8.14.3 (wrapper). 데몬은 JDK 17로 구동. |
| ForgeGradle | `[6.0.24,6.2)` |
| Parchment | `org.parchmentmc.librarian.forgegradle` 1.+, 매핑 `2023.09.03-1.20.1` |
| Minecraft | 1.20.1 |
| Forge | 47.4.0 |
| mod id | `aicompanion` (메인 클래스 `com.aicompanion.AICompanionMod`) |

## 빌드·실행

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew build          # 빌드 (jar → build/libs/aicompanion-<ver>.jar)
./gradlew runServer      # dev 전용 서버 기동
./gradlew printJavaVersion   # 컴파일 툴체인 Java 메이저 버전 출력(검증용)
```

### Wrapper 배포 캐시 주의 (이 환경 한정)
`services.gradle.org`의 배포 zip은 GitHub 릴리스로 리다이렉트되며, 이 세션의 프록시가 GitHub를 차단한다.
그래서 wrapper 캐시를 이미 설치된 로컬 배포판으로 pre-seed했다:
```
$GRADLE_USER_HOME/wrapper/dists/gradle-8.14.3-bin/<hash>/gradle-8.14.3/  ← /opt/gradle-8.14.3 복사본
                                                        /gradle-8.14.3-bin.zip.ok  ← 다운로드 스킵 마커
```
`gradle-wrapper.properties`의 `distributionUrl`은 표준 URL 그대로 두어(이식성), 네트워크가 열린 환경에서는 정상 다운로드된다.

## 디컴파일 바닐라 소스 (실제 시그니처 참조원)

FG6에는 구버전의 `genSources` 태스크가 **없다**(디컴파일 소스는 userdev resolve 시 자동 준비).
읽기용 `.java` 소스는 프로젝트가 컴파일 대상으로 삼는 Parchment-매핑 jar를 ForgeFlower로 디컴파일해 확보했다.

- **디컴파일 소스 아카이브(로컬, git 미추적)**:
  `./.mcp-sources/forge-1.20.1-47.4.0_mapped_parchment_2023.09.03-1.20.1.jar`
  (5,453개 `.java`. 파라미터 이름 = Parchment 복원.)
- **컴파일 대상 원본 jar(FG 캐시)**:
  `$GRADLE_USER_HOME/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.4.0_mapped_parchment_2023.09.03-1.20.1/forge-1.20.1-47.4.0_mapped_parchment_2023.09.03-1.20.1.jar`

핵심 클래스 확인 예:
```
net/minecraft/server/level/ServerPlayer.java
net/minecraft/world/entity/LivingEntity.java
net/minecraft/world/entity/ai/navigation/PathNavigation.java
```

### 특정 클래스 소스 열람
```bash
unzip -p ./.mcp-sources/forge-1.20.1-47.4.0_mapped_parchment_2023.09.03-1.20.1.jar \
  net/minecraft/server/level/ServerPlayer.java | less
```

### 소스 재생성(필요 시)
```bash
FF=$GRADLE_USER_HOME/caches/forge_gradle/maven_downloader/net/minecraftforge/forgeflower/2.0.629.0/forgeflower-2.0.629.0.jar
MJ=$GRADLE_USER_HOME/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.4.0_mapped_parchment_2023.09.03-1.20.1/forge-1.20.1-47.4.0_mapped_parchment_2023.09.03-1.20.1.jar
java -jar "$FF" -dgs=1 -asc=1 "$MJ" ./.mcp-sources
```

## 검증

`scripts/verify_t01.sh` — 빌드 exit code, 툴체인 버전, dev 서버 로그의 모드 로드 라인을 측정해
`[BOTTEST] T0.1 PASS/FAIL measured=... expected=...` 형식으로 판정한다.
