# 무인 배치 실행 로그

각 블록의 시작·끝, 한 일, 어긋난 것을 누적한다.

## 블록 A — 규칙 배선

- **A1 규칙2 배선**: `BotProtection`의 9.3 모드 선택 뒤에 `CombatRules.allowMelee` **거부권**을
  추가했다. 9.3은 「어느 대상 / 활 보유 여부」를, 규칙2는 「근접 진입 가부」를 답하므로 교체가
  아니라 게이트다. 거부 시 로그 `[PROTECT] rule2 veto`.
- **A2 갑옷 반영**: `CombatStats.of`의 `effectiveHp`가 `health / (1 - min(0.8, armor×0.04))`.
  바닐라 방어도 항만 쓰고 강도/피해량 항은 뺐다 — 규칙2가 판정하는 시점에 피해량을 모르고,
  생략 방향이 보수적(생존성을 과대평가하지 않음)이다.
- **A3 규칙4 결합**: `BotReflex`의 방패 분기에 `ruleAllowsShield`를 **AND**로 추가. 규칙4는
  「이 대상에게 방패가 유효한가」, 현행 조건은 「지금 올릴 수 있는가」로 층이 다르다. 투사체
  소유자를 대상으로 해석하고 미지 몹은 6.6대로 「방패 유효 가정」.
  → **별건 부채 등록**: 도끼 지식(`axeEnemy`)을 `Layer2Profile`로 이관 (6.1 「층2는 코드가 아니라 데이터」).
- **A4 규칙1 배선**: `BotRangedCombat`이 `TargetInfo.canKite`를 읽는다. `canKite=false`면
  밴드 하한 침범 시 **후퇴하지 않고**(못 이기는 달리기) 횡이동한다. D 칸은 `distanceCritical`
  플래그로 `BotProtection`이 전달.
- **A5 설계서**: 18장에 **64줄 추가**(정정 4 블록) — 유형 #10 등록, R.2 확장, 책임 소재 표,
  규칙1×규칙2 결정 테이블과 D 결정 근거·도달성. 기존 문장 수정·삭제 없음.
  재설계 브리프에 7절(「D 칸의 존재 자체가 증상」) 추가.

**어긋난 것**: 없음. 빌드 성공.

## 블록 B — 행동 검증 하니스 (B1 완료, B2 진행)

신규 하니스 8종. 전부 **덤프가 아니라 행동**을 판정한다 (R.2 확장).

| 하니스 | 결과 | 측정값 |
|---|---|---|
| `bot_rule2_deny` | PASS | `allowMelee:false, botApproachSum:-0.76, damageDealt:0.0` |
| `bot_rule2_allow` | PASS | `allowMelee:true, botApproachSum:+6.06, damageDealt:17.7` |
| `bot_rule4_pierce` | PASS | `blockingTicks:0` (층2 관통 → 방패 안 올림) |
| `bot_rule4_normal` | PASS | `blockingTicks:195` (대조) |
| `bot_rule4_axe` | PASS | `blockingTicks:0, nearestHasAxeTicks:220` (결합 보존) |
| `bot_rule1_nokite` | PASS | `canKite:false, retreatCommandTicks:0` |
| `bot_rule1_kite` | PASS | `canKite:true, retreatCommandTicks:43` |
| `bot_rule_dcell` | PASS | `everInDCell:true, dCellTicks:192, damageDealt:0.0, distanceCritical:true` |

**하니스 자체에서 발견·수정한 결함 4건** (전부 유형 #8 계열 — 측정량이 이름과 다름):

1. `bot_rule2_deny` 최초 FAIL. `minDist 2.90`으로 「봇이 근접했다」를 판정했으나 봇은 서 있었고
   **좀비가 걸어온 것**이었다. → 봇 자신의 변위를 대상 축에 투영한 `botApproachSum`으로 교체.
2. `bot_rule1_nokite` 최초 FAIL. `radialAwaySum 3.16`이 후퇴로 보였으나 `lateralSum 0.00`이 증거 —
   봇은 입력을 낸 적이 없고 **주입 추격자의 충돌에 밀린 변위**였다. → 컨트롤러의 후퇴 **명령**
   (`zza < -0.5`) 틱 수로 교체. 변위는 「충돌 포함」 라벨을 달아 진단으로만 남김.
3. `bot_rule4_axe` 최초 FAIL. `nearestEnemyHasAxe`는 **메인핸드**를 읽는데 도끼를 오프핸드에 줬다.
   → 도끼를 별도 근접 몹의 메인핸드로 옮김. 두 번째 FAIL은 `nearestHasAxeTicks:69/220` —
   봇이 밀려나며 최근접 자리가 스켈레톤으로 넘어갔다. → 봇 고정.
4. `bot_rule_dcell` 최초 FAIL 2회. (a) 주입 추격이 봇에 도달하면 방향이 매 틱 반전해 창 내 순변위가
   0에 수렴 → `canKite=true`. (b) 레인 대상이 멈추면 관측 속도가 떨어져 히스테리시스가 true로 복귀.
   → 대상을 멈추지 않는 직선 레인으로 바꾸고, 판정을 「마지막 표본」이 아니라 **셀이 실제로 성립한
   창**(`everInDCell`/`dCellTicks`)으로 이동.

**신규 부채 (A)**: `BotReflex.nearestEnemyHasAxe`가 **최근접 적 하나만** 검사한다. 도끼를 든 적이
최근접이 아니면 방패 무효화 지식이 적용되지 않는다. 값 근거: `nearestHasAxeTicks 69/220`.

**신규 부채 (B)**: 도끼 지식을 `Layer2Profile`로 이관 (6.1 「층2는 코드가 아니라 데이터」).

### 블록 B2 — 회귀 대조 (예측표 대비)

| 하니스 | 예측 | 실측 | 대조 |
|---|---|---|---|
| `bot_tactics` | 바뀜 | PASS (botDps 9.6) | 판정값 불변 — 덤프 하니스라 배선 영향 없음 |
| `bot_melee` | **FAIL 예상** (더미 2000) | **PASS 3/3** | **예측 빗나감** |
| `bot_ranged` | 안 바뀜 | PASS 3/3 | 일치 |
| `bot_shield` | 바뀜 | PASS | 판정값 불변 |
| `bot_protect_intervene` | 바뀜 | **FAIL 0/3** | 일치(방향), 정도는 예측 초과 |
| `bot_protect_priority` | 바뀜 | **FAIL 0/3** | 일치(방향), 정도는 예측 초과 |

**[INVALIDATES] `bot_protect_intervene`·`bot_protect_priority`의 이전 PASS는 규칙2가 단절된
상태에서 나온 값이다.** 배선 후 측정: `aggroDrop:0.0, mode:ENGAGE_RANGED`(이전 `aggroDrop:97.4`).

원인은 두 겹이고 성격이 다르다.

1. **하니스 전제 × 규칙 상호작용** — 어그로 더미가 체력 100, 봇은 체력 20 + 철검(DPS 9.6, 갑옷 0).
   `timeToKill = 100/9.6 = 10.4s`, `timeToDie = 20/3.0 = 6.7s` → `allowMelee=false`가 **산술적으로
   옳다**. 이전 PASS는 규칙2가 아무것도 게이트하지 않던 시절의 값이다. 전제 수정 **제안만**:
   봇에 갑옷을 주거나 더미 체력을 낮춰 「이길 수 있는 싸움」으로 만들어야 개입 로직을 시험한다.
2. **실제 설계 공백** — 규칙2의 「원거리 강제」는 원거리 수단이 있다는 전제 위에 있다. 이 하니스의
   봇은 활이 없으므로 강제된 원거리가 **아무 행동도 아님**이 된다(`mode:ENGAGE_RANGED`인데
   `aggroDrop:0.0`). 목표 1에 직결된다 — 유저를 지켜야 하는데 봇이 아무것도 하지 않는다.
   → **신규 부채 (B)**: 원거리 수단이 없을 때의 「원거리 강제」 폴백이 정의되지 않았다.

`bot_melee`의 FAIL 예측이 빗나간 이유: 그 하니스는 `BotProtection`을 거치지 않고
`meleeCombat().setTarget()`을 직접 호출한다. 규칙2 거부권은 보호 계층에만 배선돼 있으므로
직접 호출 경로는 게이트되지 않는다. → **신규 부채 (B)**: 규칙2가 보호 계층에만 걸려 있고
직접 교전 경로(개발 커맨드·다른 계층)는 우회한다.

## 블록 C — T5.2 장비 관리자

[설계서 재참조] 로드맵 T5.2 목적/선행조건(T3.1)/분할근거/구현 스펙/[검증], 14장 전문 인용 완료.

구현 `BotEquipment`: 갑옷 4슬롯 점수화(방어도+강도+인챈트−내구위험, **attribute 직접 비교로
재질 이름 불문** — 14장 명시), 근접 점수(공격력+속도+인챈트, 언데드면 강타 가중치↑),
원거리 점수(힘/무한/펀치), 방어용 블록 재고(고가치 제외), 화살 재고, 인벤토리 지문 변화 시에만
재평가(14장 「인벤토리 변화 시에만 재평가」). `AICompanionBot.tick`의 갈래 분기 위에 배치.

### 블록 C 검증 결과

- **`bot_equip` PASS** — `head/chest/legs/feet:diamond_*, mainHand:diamond_sword`.
  로드맵 T5.2 판정문(「착용 갑옷==다이아 AND 메인핸드==다이아검」) 충족. 철·나무를 먼저,
  다이아를 나중에 동시 지급했으므로 「마지막에 온 것을 입었다」로는 통과 불가.
- `bot_warden_band` PASS, `bot_warden_charge` PASS — 규칙3 소비 경로는 배선 후에도 불변.

**[INVALIDATES] `bot_warden_tactics`·`bot_warden_noname`·`bot_warden_generic`의 13:48 PASS 무효.**
원인은 규칙 배선이 아니라 **관측 창 정정의 지연 여파**다. 하니스가 40틱에 판정하는데
`SpeedObserver.MIN_SPAN_TICKS`는 60이라, 관측이 성립하기 전에 물어 콜드스타트 보수값
`canKite=false`를 받는다. 그 PASS는 최소 표본 구간이 20이던 시절 값이고, ch.18 정정이 60으로
올린 뒤 이 셋을 재실행한 적이 없어 잠복했다. → 판정 시점을 80틱으로 수정(결과에 맞춘 튜닝이
아니라 관측기에 선언된 창을 주는 수정). 이 셋은 `setNoAi`로 대상을 고정하므로 관측값이 0.0000이며
실동 검증은 `bot_warden_live_speed`가 맡는다는 기존 구분은 그대로다.

## 블록 D~J — 미착수

T5.1 능력 인식 A* / C4·C5 / T5.3 생활 기능 / T5.4 기본 상태 / T5.5 자원 조달 / 유저 보호 3종 /
scenarioSpec. **사유**: 세션 내에서 검증까지 마칠 수 있는 한계가 블록 C였다. 값 없이 코드만 남기는
것은 이 프로젝트가 반복해서 대가를 치른 방식이므로 착수하지 않았다.

## 운영 실수 기록

1. 배치 실행 중 다른 하니스를 겹쳐 실행해 서버끼리 죽였다(gradle exit 137).
2. 목적을 잃은 대기 명령이 뒤늦게 깨어나 같은 충돌을 반복했고, 그 여파로 배치 하나(5종)가
   판정 없이 지나갔다.
→ **규칙 추가**: `runlong3.sh`가 서버 JVM을 정확히 죽이는 것과 별개로, **대기 명령이 남아 있는
   동안 새 배치를 띄우지 않는다.**

## 블록 A' — 유저 보호 회귀 복구 (목표 1)

[결정] 규칙2는 「개입 여부」가 아니라 「전술 선택」에만 적용한다. 원거리 수단이 있으면 원거리로
내리고, 없으면 그대로 근접한다. **잠정 — 최종 조율은 T5.6의 「계층 간 우선순위 충돌 해소」.**

| 하니스 | 이전 | 이후 |
|---|---|---|
| `bot_protect_intervene` | FAIL 0/3, `aggroDrop:0.0, mode:ENGAGE_RANGED` | **PASS 3/3, canary OK** |
| `bot_protect_priority` | FAIL 0/3 | **PASS 3/3, canary OK** |
| `bot_protect_armed` (신규) | — | **PASS** `mode:ENGAGE_RANGED, threatHpDrop:78.7` |
| `bot_protect_unarmed` (신규) | — | **PASS** `mode:ENGAGE_MELEE, threatHpDrop:88.6` |

**중간 발견 — 유형 #10 재발, 이번엔 내가 만든 것.** `bot_protect_armed`가 최초 FAIL
(`threatHpDrop:0.0`)이었다. 원인은 14장의 「교전 모드가 무기를 요청하면 해당 카테고리 최고를
메인핸드로 스왑」을 `BotEquipment.requestCategory`로 구현해놓고 **호출자를 만들지 않은 것**이다.
활은 인벤에, 검은 손에 있었다. `BotProtection.assignTarget`에서 요청하도록 배선해 해소.
블록 B에서 규칙 셋의 판정-행동 단절을 잡아놓고, 블록 C 구현에서 같은 유형을 새로 만들었다.

## 블록 A'' — 배선 범위와 잠복 결함

- **A''1** 교전 대상 배정 호출부 전수: 실전 경로는 `BotProtection` 하나(규칙2 통과). 우회는
  `BotCommand`의 `/bot attack`(개발자 수동 명령)과 하니스 12곳뿐. **이전 부채 기술을 좁힌다** —
  「규칙2가 실제 전투 경로 전부를 게이트하지 않는다」가 아니라 「수동 명령과 하니스만 우회한다」.
- **A''2** 관측 기반 판정 하니스 11종 스윕: 판정 시점 60틱 미만은 `BotWardenTacticsTest`(40) 하나뿐,
  이미 80으로 수정·재실행해 3종 PASS 복귀. 나머지는 140~420틱. **잠복은 일반화되지 않았다.**
- **A''3** `bot_rule1_kite`에 결과 측정 추가. `retreatCommandTicks:43` + `gapAtFirstRetreat:0.56`
  이후 간격 증가를 판정에 포함. 의도만 재는 것은 유형 #10의 축소판이다.
- **A''4** `nearestEnemyHasAxe`가 사거리 내 **모든** 적을 검사하도록 수정(이전엔 최근접 하나,
  값 69/220틱). `bot_rule4_axe` `nearestHasAxeTicks:220`으로 확인.

## 블록 D — T5.1 능력 인식 A* 확장 (4.4)

[설계서 재참조] T5.1 구현 스펙: 「능력 스냅샷(물통·보트·흔한블록수) → 하강 규칙(3칸초과+물통→MLG태그,
대형+보트→보트태그), 상승 규칙(막힘+블록→파일러업태그), 특수이동 비용 페널티.」

| 하니스 | 결과 |
|---|---|
| `bot_path_ability_water` | **PASS** `pathLen:15, tookCliff:true, biggestSingleDrop:6, actionTag:water, hp:20.0→20.0` |
| `bot_path_ability_detour` | **PASS** `pathLen:39, tookCliff:false, biggestSingleDrop:3, actionTag:none` |
| `bot_path_reach` (회귀) | PASS 3/3, canary OK |
| `bot_path_blocked` (회귀) | PASS 3/3, canary OK |

**중간 정정 — 유형 #8 (진단값 오라벨), 다섯 번째.** `tookCliff`를 처음엔 **위치**로 쟀다(경로에
낮고 직선에 가까운 노드가 있는가). 그건 계단 우회 후 아래 선반을 따라 돌아오는 구간에도 참이라,
39노드짜리 우회를 「절벽을 탔다」로 판정했다. **하강 사건**(한 스텝의 Δy > maxSafeFall)으로 바꾸고
`biggestSingleDrop`을 함께 실었다. 우회 팔의 최대 단일 하강은 3(=maxSafeFall)이다.

## 블록 E — C4·C5 낙하 폴백 사슬 (13.2, 목표 1)

[설계서 재참조] 13.2 폴백 사슬:
「ELIF 봇이 근처 + 착지지점 갈 시간 됨 → 마중 나가 받기, 실패 시 아래에 물/블록
  ELIF 유저에게 물/블록 깔아줄 수 있음 → 착지 지점에 물/블록」
13.3 재활용: 착지 지점 레이캐스트=R2의 아래 거리 재기, 폴백 실행=구조물 배치기, 신규는 「봇이
하강선 아래인가」 비교뿐.

구현: `predictLanding()`(하강 레이캐스트), `ticksToFall()`(바닐라 `v'=(v−0.08)×0.98` 반복),
C4 분기(A* 목표 설정 + 스프린트 + 기존 startRiding 팔로 인계), C5 분기(기존 MLG 실행기 재사용).

**세 번의 FAIL로 드러난 것 — 전부 값이 잡았다.**

1. **하니스 결함.** 첫 실행 `userDamage:0.0, meetTicksToLand:-1`. C4 실패가 아니라 **유저가 애초에
   떨어지지 않았다** — 연결 없는 가짜 유저는 아무도 틱하지 않는다. `bot_catch_none`과 같은 방식으로
   `doTick()`+`doCheckFallDamage()`를 하니스가 구동하도록 고치고, 「실제로 떨어졌는가」를 판정
   전제(`userFell > FALL_HEIGHT−4`)로 명시했다. 20.0→20.0은 무사한 것이 아니라 아무 일도 없었던 것이다.
2. **`LANDING_SEARCH=40`의 사각.** 두 팔 모두 첫 판정이 `ticksToLand=23`이었다 — 50블록 낙하인데
   32가 나와야 한다. 원인은 착지 레이캐스트 깊이를 40으로 잘라둔 것. 지면이 40블록 안에 들어와야
   C4/C5가 보이므로, 그 시점엔 「갈 시간 됨」이 이미 거짓이다. **이 40은 13.2에 근거가 없는 내가
   정한 수이고, 행동 임계가 아니라 탐색 깊이다.** 월드 바닥까지 훑도록 고쳤다(384 상한).
3. **A* 목표가 고체 블록이었다.** `[BOT] path unreachable … expansions=0`. `predictLanding`은 유저가
   **딛을** 블록을 돌려주는데 그걸 그대로 목표로 넣었다. `landing.above()`로 정정.
   덧붙여 C4 분기가 틱 소유권을 가져가면서 planner/mover를 직접 돌리지 않아 목표만 세우고 서 있었다
   — **유형 #10을 또 내가 만들었다**. 분기 안에서 `planner().tick()`+`mover().tick()`을 구동한다.

**E4 — `bot_catch_none`의 지위 재판정.** 그 대조는 C4 미구현이라 「어떤 거리든 도달 불가」여서
성립했다. C4/C5가 생긴 지금은 대조 조건을 명시해야 한다: `botUserHoriz(28.0) > MEET_RANGE(24)`
**AND** 물통 없음. 셋(C2/C3·C4·C5)이 **각자의 조건으로** 거절되는 상태다. 전제를 판정에 넣어,
전제가 깨지면 조용히 PASS하지 않고 FAIL하도록 했다.

## 블록 F — T5.3 생활 기능 (배고픔·수면)

[설계서 재참조] T5.3 구현 스펙: 「배고픔 임계 이하→최적 식량 섭취(위험식량 회피, 전투중 억제).
포만도 6 이하 스프린트 불가. 유저 침대 취침 감지→빈 침대 있으면 봇도 취침, 없으면 옆 바닥 눕는 포즈.
유저 기상 시 기상.」 15장 원문은 `BotLiving` 클래스 주석에 그대로 인용.

| 하니스 | 결과 |
|---|---|
| `bot_live_eat` | **PASS** `food:6→20, gain:14, eaten:cooked_beef, beef:2→0, rotten:3→3, sprintClamped:true` |
| `bot_live_eat_combat` (반대) | **PASS** `food:6→6, gain:0, eaten:none, beef:2→2` |
| `bot_live_sleep` | **PASS** `botSleptInBed:true, botSleepingTicks:60, wokeWithUser:true, premiseOk:true` |
| `bot_live_sleep_nobed` (반대) | **PASS** `botSleptInBed:false, botLayBeside:true, botSleepingTicks:0, wokeWithUser:true` |

- 식량 선택은 이름이 아니라 **점수**로 갈랐다: 썩은 고기 4.8(위험·제외), 황금사과 13.6, 익힌 소고기
  20.8. 셋을 동시에 주고 소고기만 줄어든 것으로 「포만감·포화도 높은 것 우선 + 위험 식량 회피」를
  한 번에 측정한다. `rotten:3→3`이 회피의 값 증거다.
- **전투 중 억제**를 반대 케이스로 분리했다. 같은 배고픔·같은 인벤에서 좀비를 교전 대상으로 붙이면
  `gain:0`. 이게 없으면 「먹었다」가 무조건 먹는 구현과 구별되지 않는다.
- 「포만도 6 이하 스프린트 불가」는 **바닐라가 클라이언트에서** 거는 규칙이라 연결 없는 봇에는 걸리지
  않는다. `BotLiving.applySprintClamp`로 분기 사슬 **뒤에** 적용해야 어떤 컨트롤러가 켠 스프린트든
  덮는다. 앞에 두면 나중 분기가 다시 켠다.
- 먹는 중에는 장비 관리자가 손을 못 대게 막았다. 바닐라 `updatingUsingItem`은 든 아이템이
  `useItem`과 달라지는 즉시 사용을 취소하므로, 한입 중 무기 스왑은 **애니메이션만 먹고 회복 0**이 된다.

## 블록 G — T5.4 기본 상태 (16장) + R1 트리거 정정 (7장·6.2)

[설계서 재참조] T5.4 구현 스펙: 「유저 중심 8블록 배회. 유저 12블록 밖으로 달리며 나감→따라옴,
유저 3블록 근처서 정지 후 배회. 유저 정지→배회. 배고픔(스프린트 불가) 상태로 유저 멀어짐→걸어서
따라옴, 완전히 놓쳐도 걷기 추적(텔레포트 안 함).」

**「달리면서」의 해석을 기록한다.** 구현한 진입 조건은 **거리 단독**이며 「유저가 스프린트 중인가」를
보지 않는다. 16장 마지막 줄이 「**완전히 놓쳐도** 걷기로 계속 추적」을 요구하는데, 이미 시야에서
사라진 유저가 달리는 중인지는 관측할 수 없다. 관측된 스프린트를 게이트로 걸면 추종이 **설계서가
계속하라고 말한 바로 그 시점에** 멈춘다. 클래스 주석에 근거와 함께 남겼다.

### R1 트리거 — 구현이 7장 문장과 달랐다

7장: 「**적 공격 모션** or 투사체가 봇 히트박스로 향함」. 구현: 「투사체 접근 OR 원거리적
(`RangedAttackMob`)이 **근처**」. 두 번째 항은 설계서에 없는 조건이고, 첫 번째 항(공격 모션)은
아예 구현되지 않았다. 아울러 6.2 표의 「원거리 여부 = **투사체 발사 관측**」을 클래스 검사가
대신하고 있었다 — 봇이 볼 수 없는 인터페이스로 판정한 것이다.

| 하니스 | 결과 |
|---|---|
| `bot_r1_swing` | **PASS** `r1FiredTicks:119/120, attackMotionTicks:119, blockingTicks:115, swingsIssued:18` |
| `bot_r1_idle` (반대) | **PASS** `r1FiredTicks:0, attackMotionTicks:0, blockingTicks:0` |
| `bot_r1_proximity` (회귀) | **PASS** `r1FiredTicks:0, observedRanged:false` — 옛 조건이라면 매 틱 발동했을 상황 |

세 팔의 차이는 **자극 하나뿐**이다(스윙 애니메이션). 몹은 `NoAi`라 움직이지도, 실제로 때리지도,
쏘지도 않는다.

## 블록 H — T5.5 자원 조달 (17장)

[설계서 재참조] 17.1 「유저 조준선 오차범위 내(관대하게, 봇 중심 반경 넉넉히)로 떨어지면 →
"나에게 주는 것"으로 인식. 비전투 상황 → 달려가서 받음. (전투 중엔 받으러 가지 않음)」
17.2 「자연 드롭은 유저가 그것을 줍지 못하는 경우에만 봇이 주움(뺏어가는 느낌이 들지 않게)」.

수용 조건은 **두 항의 OR**로 구현했다 — 봇 근처에 떨어졌거나, 대략 봇 방향으로 던져졌거나.
둘을 AND로 묶으면 17.1이 명시적으로 금지한 「빡빡하게 조준을 요구」가 된다.

**하니스 계약 충돌 발견.** 첫 실행 세 팔 모두 `itemEntityGone:true, fetchTicks:0`. 봇의 실패가
아니라 **카나리가 아이템을 지운 것**이다 — `sweepConstructionDebris`는 `setup()` **뒤에** 돌면서
아레나 안의 모든 `ItemEntity`를 「건설 부산물」로 간주해 폐기한다. 그 전제는 아이템 자체가 시험
대상인 하니스에서는 거짓이다. `BotTest.itemsAreSubject()`를 추가해 **그 하니스만** 예외로 두고,
대신 그 하니스가 자기 아이템을 놓기 전에 주변 아이템을 스스로 청소한다(이전 로그에
`[PICKUP] fetching stick (abandoned) d=21.15` — 월드 부산물이 17.2 경로를 발동시킨 흔적이 있다).

## 블록 I — 부채 회수: 9.2 밴드 · 9.3 · 회복 경로

지금까지 코드는 있으나 하니스가 없던 네 경로에 값 판정을 붙였다.

- `bot_protect_lowuser` / `bot_protect_highuser` — 9.2의 40% 밴드. **같은 두 마리**(공격력 18인데
  유저를 안 노리는 좀비 / 공격력 2인데 유저를 노리는 좀비)를 두고 **유저 체력만** 바꾼다.
  판정은 규칙 문자열이 아니라 **실제 배정된 교전 대상**으로 한다 — 규칙 문자열은 봇이 스스로에
  대해 하는 진술이고, 유형 #10은 바로 그 진술이 옳고 행동이 아닌 경우다.
- `bot_protect_ranged_guard` / `_melee` — 9.3. 활 보유 시 유저 곁을 지키는지(봇-유저 최대거리),
  근접 위협이 생기면 그쪽을 먼저 고르는지.
- `bot_survival_heal` / `_nopotion`, `bot_rescue_heal` / `_nopotion` — 8장 전투 중 회복과 9.2
  치명선 투척 회복. **포만도를 10으로 고정**해 바닐라 자연 재생(포만도 18 이상)을 배제했다.
  이게 없으면 「체력이 올랐다」가 아이템 때문인지 재생 때문인지 갈리지 않는다.

## 블록 J — `scenarioSpec()` (결함 유형 #9 회수)

`BotTest.scenarioSpec()`를 추가하고 매니저가 START 직후 `[BOTTEST] <name> SCENARIO <spec>`로
찍는다. 비어 있으면 `(unstated)`로 찍는다 — 침묵시키지 않는 것이 요점이다.

**부적절한 전제 목록 (고치지 않았다).** 이 값들은 하니스 이름이 약속하는 것과 다른 세계에서
측정이 이뤄졌다는 뜻이며, 각각 T5.6에서 판단할 사안이다.

| 하니스 | 전제 | 왜 부적절한가 |
|---|---|---|
| `bot_melee` | 더미 최대체력 **2000** | 「근접 교전이 성립하는가」를 재려고 했는데, 죽지 않는 표적은 **전투가 아니라 타격 반복**이다. 크리 판정·재장전·처치 후 전환이 관측 범위에서 아예 빠진다 |
| `bot_ranged` | 봇 최대체력 **4000** | 봇이 죽지 않으므로 「원거리 유지에 실패해도 대가가 없다」. 밴드 유지의 값이 측정에서 사라진다 |
| `bot_coldstart_dist`, `bot_rule1_fast`, `bot_speed_probe`, `bot_warden_live_speed`, `bot_warden_pursuit`, `bot_window_variance` | 봇 최대체력 **400** | 속도 측정 목적이므로 사망 방지 자체는 합당하나, **같은 봇으로 전술 판정도 함께 찍힐 때** 규칙2(승패 게이트)의 입력이 실전과 다르다 |
| 카이팅 6종 (`bot_kite_*`), `bot_rule1_action`, `bot_rule4_action` | 봇 최대체력 **200** | 위와 같은 이유. 특히 `bot_kite_execmon`의 「hp 15.7 손실」은 200 기준의 손실이라 20 기준으로는 **치명상**이다 |
| `bot_warden_tactics` | 표적 최대체력 **500** | 워든 실제값(500)과 일치하므로 **적절**. 대조용으로 남긴다 |
| 대부분의 하니스 | `bot.setInvulnerable(true)` | 측정 대상이 아닌 데미지를 배제하는 정당한 격리지만, **격리했다는 사실이 판정 라인에 없었다**. 이제 `scenarioSpec`에 실린다 |

`scenarioSpec()` 본문은 이번 세션에 작성한 11개 하니스에 채웠다. 나머지 레거시 하니스는
`(unstated)`로 찍히며, 위 표가 그 미기록 목록이다 — **고치지 않는다**는 지시를 지켰다.

## 블록 G~J 검증 결과

| 하니스 | 결과 |
|---|---|
| `bot_r1_swing` | **PASS** `r1FiredTicks:119, attackMotionTicks:119, blockingTicks:115, swingsIssued:18` |
| `bot_r1_idle` (반대) | **PASS** `r1FiredTicks:0, attackMotionTicks:0, blockingTicks:0` |
| `bot_r1_proximity` (회귀) | **PASS** `r1FiredTicks:0, observedRanged:false` |
| `bot_idle_follow_hungry` | **PASS** `arriveTick:71, arriveDist:2.86, sprintTicks:0, maxStep:0.226, botTravel:25.17` |
| `bot_idle_wander` (반대) | **PASS** `botTravel:17.63, maxDist:6.18, followTicks:0, maxStep:0.216` |
| `bot_pickup_gift` | **PASS** `gained:3, fetchTicks:47, itemEntityGone:true` |
| `bot_pickup_combat` (반대) | **PASS** `itemEntityGone:false, gained:0, fetchTicks:0` |
| `bot_pickup_natural` (반대) | **PASS** `itemEntityGone:false, gained:0, fetchTicks:0` |
| `bot_protect_lowuser` | **PASS** `pickedUserHunter:80, pickedMaxDps:0, rule:P2` |
| `bot_protect_highuser` (반대) | **PASS** `pickedUserHunter:0, pickedMaxDps:80, rule:P1` |
| `bot_protect_ranged_guard` | **PASS** `pickedSkeleton:100, engageRangedTicks:100, maxBotUserDist:7.31` (사수는 14블록) |
| `bot_protect_ranged_melee` | **PASS** `pickedZombie:100, pickedSkeleton:0, maxBotUserDist:2.00` |
| `bot_survival_heal` | **PASS** `botHp:8.0→15.0, apples:2→0` |
| `bot_survival_nopotion` (반대) | **PASS** `botHp:8.0→8.0, botGain:0.0` |
| `bot_rescue_heal` | **PASS** `userHp:2.0→5.0, potions:1→0, everHealUser:true` |
| `bot_rescue_nopotion` (반대) | **PASS** `userHp:2.0→2.0, protMode:FLEE, everHealUser:false` |
| 회귀 `bot_protect_intervene` | PASS 3/3 `aggroDrop:97.4, neutralDrop:0.0`, canary OK |
| 회귀 `bot_protect_priority` | PASS 3/3, canary OK |

### 이번 블록에서 값이 잡아낸 측정 결함 (전부 내 것)

1. **유저의 자연 재생을 통제하지 않았다.** `bot_rescue_nopotion`이 **포션 없이** `userHp 2.0→6.5`.
   `TestUser.spawn`이 포만도를 20으로 두므로 유저가 스스로 회복한 것이다. 포션 팔의 7.5도 그만큼
   재생이 섞여 있었다. 유저 포만도도 10으로 내리자 `2.0→5.0`(포션) / `2.0→2.0`(대조)으로 갈렸다.
   경보가 울렸을 때 **먼저 의심한 것은 구현이 아니라 측정**이었고, 그게 맞았다.
2. **`HEAL_USER`는 순간 모드다.** 관측 창 끝에서 `mode()`를 읽으면 이미 `ENGAGE_MELEE`다
   (회복 후 유저가 치명선을 벗어났으므로). `everHealUser`로 바꿔 「그 순간이 있었는가」를 잰다.
3. **「정지」를 관측 창 끝에서 쟀다.** 16장은 도착 후 「**다시 배회**」를 요구하므로 창 끝의 이동은
   실패가 아니라 스펙 준수다. 판정을 **도착 시점 기준**으로 옮겼다.
4. **스프린트는 즉시 멈추지 않는다.** 도착 +1틱부터 재면 `postArrivalSpeed 0.1514` — 0.28
   스프린트가 바닐라 마찰로 감속하는 값이다. 창을 +10~+30으로 옮겼다. **행동이 아니라 물리를
   재고 있었다.**

### T5.4 최종 (정지 판정 창 정정 후)

| 하니스 | 결과 |
|---|---|
| `bot_idle_follow` | **PASS** `arriveTick:57, arriveDist:2.86, postArrivalSpeed[+10..+30]:0.0000, maxDistAfterArrival:4.04, botTravel:27.22, sprintTicks:57, maxStep:0.280` |
| `bot_idle_follow_hungry` | **PASS** `arriveTick:71, postArrivalSpeed:0.0000, sprintTicks:0, maxStep:0.216, botTravel:27.28` |
| `bot_idle_wander` (반대) | **PASS** `followTicks:0, maxDist:6.80, botTravel:25.25, maxStep:0.266` |

두 추종 팔의 차이는 **스프린트 틱뿐**(57 대 0)이고 최대 한 틱 이동이 0.280 대 0.216으로
갈린다 — 「걸어서 따라옴(느려도)」이 값으로 나타난 자리다. 어느 팔에서도 한 틱에 1블록을
넘지 않았다(「텔레포트 안 함」).

# 블록 K — 검증 회수

## K-1 발견 (최우선): 회귀 PASS 두 건이 **stale 로그**였다

`docs/bottest_lines_blockK.txt`를 만들려고 로그 파일을 직접 grep 하다가 나온 것이다.

- `p5_bot_protect_intervene.log`, `p5_bot_protect_priority.log` (둘 다 04:45/04:46, 83576바이트로
  **크기까지 동일**)에는 `[BOTTEST]` 라인이 **0개**다. 내용은 `BUILD SUCCESSFUL in 30s`에서 끝난다.
- 그런데 나는 04:38 폴링에서 두 하니스의 `PASS 3/3 … mode:NONE` 라인을 읽고 「9장 변경 후에도
  회귀 유지」라고 보고했다. 그 시점에 러너는 아직 1번 항목(`bot_idle_wander`)을 돌고 있었다.
  `runlong3.sh`는 **각 하니스 차례가 왔을 때** 로그를 지우므로, 그때 파일에 있던 내용은
  **이전 세션 실행분**이었다.
- 「`mode:NONE`이니 내 변경 이후 실행이 맞다」는 내 추론도 틀렸다. `mode = Mode.NONE`은 변경
  **이전 코드에도 있던 줄**이다. 즉 그 값은 변경 여부를 구분하지 못한다.
- 04:45의 실제 실행이 빈 로그로 끝난 이유: **04:43에 내가 `./gradlew compileJava`를 배치와 동시에
  돌렸다.** 빌드 락 경합으로 `runServer`가 30초 만에 조용히 끝났다. 같은 운영 실수의 반복이다.

**결론**: 「9장 변경 후 회귀 2종 유지」는 **값 근거가 없었다.** 취소하고 재실행했다.

## K-2 해명: (a) 인정 / (b) 배제 / (c) 배제

재실행 (3 트라이얼, 커밋 시점 코드):

| 트라이얼 | aggroDrop | modeEngageTicks | modeNoneTicks | **engagePathTicks** | invadeRadiusTicks | modeAtEnd |
|---|---|---|---|---|---|---|
| t1 | 79.7 | 182 | 58 | **182** | 181 | NONE |
| t2 | 97.4 | 206 | 34 | **206** | 205 | NONE |
| t3 | 88.6 | 240 | 0 | **240** | 240 | ENGAGE_MELEE |

**(a) 유형 #8 — 인정한다.** `aggroDrop = aggroHp0 - aggroMinHp`
(`BotProtectInterveneTest.java:137`)는 이름과 계산이 1:1이다. 문제는 `mode`다:
`protMode = bot.protection().mode().name()`을 **`judge()`에서 한 번** 읽었다(:140). 필드 이름은
「이 트라이얼의 모드」를 약속하는데 계산은 「창이 닫히는 순간의 모드」였다. 240틱 중 182~240틱이
ENGAGE였는데 마지막 순간만 보고 NONE으로 적힌 것이다. `BotHealTest`의 `HEAL_USER` 순간 모드와
같은 형태이며, 창 전체 표본(`modeEngageTicks`/`modeNoneTicks`)으로 교체했다.

**(b) 유형 #10 — 배제한다.** `engageAssignTicks` 카운터를 `BotProtection.assignTarget` **안에만**
심었다. 그 메서드는 9장이 교전 대상을 전투 컨트롤러에 넘기는 **유일한 지점**이다.
측정값 182/206/240은 `modeEngageTicks`와 **정확히 일치**한다. 어그로 감소는 9장 개입 경로가
실행된 틱 수만큼 일어났다. 근접·피격 부수효과가 아니다.

**(c) 유형 #3 — 배제한다.** 97.4는 잔류값이 아니다. 같은 하니스 3 트라이얼이
**79.7 / 97.4 / 88.6**으로 흩어진다. 97.4는 그중 한 트라이얼의 값이었을 뿐이다.
추가로 음성 대조 `bot_protect_intervene_none`(어그로 좀비를 9.4 침범 반경 밖 22블록에 배치)을
신설해 개입 불가 조건에서의 값을 따로 잰다.

**모드 NONE과 aggroDrop 97.4는 모순이 아니었다** — 다만 그 사실은 이번 측정으로 처음 값이 됐고,
지난 보고에서 「정상 결과」라고 쓴 것은 **근거 없는 단정**이었다.

**K-2 음성 대조 (신설)** — `bot_protect_intervene_none`, 3 트라이얼 전부:
`aggroDrop:0.0, modeEngageTicks:0, modeNoneTicks:240, engagePathTicks:0`.
개입 가능 조건 79.7~97.4 / 182~240틱 대 개입 불가 조건 0.0 / 0틱. 대조가 성립한다.

## K-5 예측 대조표 재분류 (5/5 주장 철회)

「5건 중 4건이 측정 결함」과 「원칙이 5/5로 맞았다」는 같은 문단에서 충돌했다. **5/5 주장을 철회한다.**
사전확률은 **수사 순서**이지 성적표가 아니다. 행을 원인 성분으로 쪼개 다시 적는다.

| 행 | 분류 | 성분 |
|---|---|---|
| C4 3연속 FAIL | **혼합** | ① 하니스가 가짜 유저를 틱하지 않음 → [측정/하니스] ② `LANDING_SEARCH=40`이 40블록 초과 낙하를 안 보이게 함 → **[구현]** ③ A* 목표가 고체 블록 + C4 분기가 planner/mover 미구동 → **[구현]** (유형 #10) |
| 아이템 하니스 3팔 FAIL | **[측정/하니스]** | 카나리 스윕 순서. 봇 코드는 무관 |
| 배회 `botTravel:0.00` | **혼합** | 주: **[구현]** — 9장과 16장이 A* 목표를 이중 기록(유형 #11). 부: **[스펙 해석]** — 16장 신설로 평상시 이동 소유권이 미정의가 됐고 설계서가 그 이양을 적지 않았다. 하니스는 정확히 옳게 쟀다(0.00은 진짜 0.00) |
| 「정지」를 창 끝에서 잼 | **혼합** | 주: **[스펙 해석]** — 16장 「3블록 근처서 정지, **다시 배회**」의 뒷절을 판정에 넣지 않았다. 부: [측정] — 도착 +1틱은 스프린트 감속(물리)이지 행동이 아니다 |
| 회복 대조 오염 | **[측정/하니스]** | 유저 포만도 미통제 |

**원인 단위 집계**: 하니스/측정 4, 구현 3, 스펙 해석 2 (혼합 행은 성분별로 셈).
**사전확률의 실제 성적**: 5행 중 **먼저 의심한 측정이 원인이었던 것은 3행**(아이템·회복·「정지」의
부성분), C4는 첫 원인만 측정이고 나머지 둘은 구현, 배회는 구현이었다.
즉 「1순위 용의자는 측정」은 **수사를 옳은 순서로 시작하게 했지만 판정을 대신하지 못했다.**
이력 전체로도 4회 중 1회는 측정 문제가 아니었다는 기록과 일치한다.

## K-6 정산 둘

**① 회귀 「6종」 명단**: `bot_path_reach`, `bot_path_blocked`(블록 D) / `bot_catch_none`,
`bot_catch_fall`(블록 E) / `bot_protect_intervene`, `bot_protect_priority`(블록 I).
**행동 검증 8종의 부분집합이 아니라 전혀 다른 집합이다.** 즉 「빠진 2종」이 아니라
**행동 검증 8종이 통째로 9장 변경 후 재실행되지 않았다.** 그것이 K-4가 지적한 구멍이며,
이번 배치에 포함했다. 8종 중 9장 코드가 실제로 도는 것은 `bot_rule2_deny`, `bot_rule2_allow`,
`bot_rule_dcell` 셋뿐이다 — 나머지 다섯(`bot_rule4_pierce/normal/axe`, `bot_rule1_nokite/kite`)은
`TestUser`를 띄우지 않아 `BotProtection.tick`이 `user == null`에서 즉시 반환한다(코드 근거).

**② 18장 「64줄」 대 이동 「63줄」**: `git show 12f4f7b -- AI_Bot_Design.md`로 정산한다.
추가된 줄은 **64줄**(diff `+` 라인에서 `+++` 헤더 제외)이고 그중 **1줄이 빈 구분줄**이다.
내가 이동시킨 블록은 첫 `>` 줄부터 마지막 내용 줄까지의 **63줄**이며, 앞의 빈 구분줄은 18장에
남았다. 차이는 그 1줄이다. 내용 손실은 없다.

## K-3 R1 트리거 정정의 after 값 (발화율)

**설계서 7장 R1 조건 인용**: 「적 공격 모션 or 투사체가 봇 히트박스로 향함: 방패 보유 → 방패 즉시
올림(오프핸드 use) / 아니면 → 수직 방향 사이드스텝(궤적 법선으로 1~2틱)」

**기대값을 먼저 적는다.**
- ① 양성(자극 있음): 발화율은 **자극이 지속되는 동안 1.0에 가까워야** 한다. 공격 모션은 스윙
  애니메이션이 끝나면 잠깐 꺼지므로 1.0 미만이 정상이고, 투사체는 화살이 비행 중일 때만
  「히트박스로 향함」이 참이므로 **발사 간격에 비례해 1.0보다 훨씬 낮은 값이 정상**이다.
  중요한 것은 **0이 아니고 자극과 함께 움직인다**는 것.
- ② 반대(자극 없음): 발화율 **0에 수렴**. 여기서 0이 아니면 정정은 미완이다.

**measured**

| 팔 | 자극 | r1FiredTicks / runTicks | **r1FireRate** | 부수 값 |
|---|---|---|---|---|
| `bot_r1_swing` | 좀비 공격 모션 (18회) | 119 / 120 | **0.992** | attackMotionTicks:119, blockingTicks:115 |
| `bot_r1_projectile` | 화살 6발이 봇을 향해 비행 | 35 / 120 | **0.292** | observedRanged:**true**, blockingTicks:96 |
| `bot_r1_idle` (반대) | 같은 좀비, 모션 없음 | 0 / 120 | **0.000** | attackMotionTicks:0, blockingTicks:0 |
| `bot_r1_proximity` (반대) | 스켈레톤 10블록, 발사 없음 | 0 / 120 | **0.000** | observedRanged:**false** |

**②가 0에 수렴한다 — 두 반대 케이스 모두 정확히 0이다.** 부채에 기록된 before(「관측 틱 100%
발화」)는 `bot_r1_proximity`와 같은 조건에서 나온 값이었고, 지금 같은 조건의 after는 0.000이다.
투사체 팔의 0.292는 화살 6발의 비행 시간 합에 해당한다 — 자극이 없는 틱에는 발화하지 않는다는
뜻이므로 기대와 일치한다. `observedRanged`가 발사 팔에서만 true인 것이 6.2 관측 술어의 값 증거다.

## K-4 9장 변경 무효화 범위 sweep

**변경의 실제 델타**: `follow()`는 이미 `clearCombat()`를 호출하고 있었으므로, 내 변경이 제거한
것은 **`planner().setGoal(user.blockPosition())` 한 줄뿐**이다. 즉 「대상 없음일 때 9장이 A* 목표를
쓰는가」만 달라졌다.

**전수 나열**: `TestUser.spawn`을 호출하는 하니스 19종에서만 `BotProtection.tick`이 `user != null`을
지나 그 분기에 도달할 수 있다. 나머지는 `user == null`에서 즉시 반환한다(코드 근거).
19종 = catch_fall, catch_meet, catch_none, catch_water, creeper_lowfuse, creeper_wall,
escape_farthreat, escape_none, escape_ride, heal(4팔), idle(3팔), live_sleep(2팔), pickup(3팔),
protect_armed/unarmed, protect_intervene(+none), protect_lowuser/highuser, protect_priority,
protect_ranged(2팔), rule2(2팔), rule_dcell. **전부 재실행했다(37종 배치).**

**결과: 33 PASS, 4 FAIL.**

### [INVALIDATES] 규칙 배선 행동 검증 2종이 깨졌다

    [INVALIDATES] bot_rule2_deny  (이전 PASS: 07-25 22:20, 9장 변경 이전)
    [INVALIDATES] bot_rule_dcell  (이전 PASS: 07-25 22:45, 9장 변경 이전)

| 하니스 | 측정 | 의미 |
|---|---|---|
| `bot_rule2_deny` | `allowMelee:false` 인데 `botApproachSum:12.04, ticksInMeleeRange:182, damageDealt:88.6, targetHp:400→311` | **규칙2가 거부했는데 봇이 접근해 때렸다** |
| `bot_rule_dcell` | `everInDCell:true, dCellTicks:175` 인데 `botApproachSum:25.40, damageDealt:8.9, lateralCommandTicks:0` | D 칸 결정(근접 금지·횡이동)이 **행동으로 나타나지 않는다** |

두 하니스는 유저를 띄우고 교전 대상을 수동 설정한다. 9장이 평상시 이동 목표를 더 이상 쓰지
않으면서 **16장 배회/추종이 그 자리를 대신 차지**했고, 그 이동이 봇을 대상 쪽으로 밀어 넣은 것이
가장 유력한 경로다. 다만 22:45~05:27 사이에 T5.3·T5.4·T5.5·R1 정정이 함께 들어갔으므로
**원인을 하나로 단정하지 않는다** — 원인 분리는 다음 슬롯의 일이다.
**이것은 유형 #10의 재발이다: 규칙2 판정은 여전히 옳게 계산되는데, 행동이 그것을 따르지 않는다.**

### 그 밖의 두 FAIL

| 하니스 | 측정 | 판정 |
|---|---|---|
| `bot_pickup_natural` | `fetchTicks:0` 인데 `itemEntityGone:true, gained:3` | **17.2의 새 구멍.** `BotPickup`은 올바르게 「가지러 가지 않음」을 결정했다(fetchTicks 0). 그런데 16장 배회가 봇을 아이템 위로 지나가게 했고 **바닐라 자동 획득**이 집어갔다. 17.2를 만족하려면 결정만이 아니라 **획득 자체를 거부**해야 한다. 이전 PASS는 봇이 움직이지 않던 시절의 것이라 드러나지 않았다 |
| `bot_creeper_lowfuse` | `passed:1/3, canary:MISMATCH x2 (coreBlocks changed, id 296↔300)` | **격리 계약 위반.** 트라이얼 간 블록 상태가 복원되지 않는다. 원인 미확정 — `doTileDrops=false` 도입과 시점이 겹치므로 그것부터 의심한다 |

**「변경 없음」으로 결론 낸 항목 없음** — 19종 전부 실제로 재실행했다.

# 블록 L — 계측 정합

RUN_ID 도입 이전 기록은 신선도 미확정이다. 과거 기록을 재해석하지 않는다.

## L-1 유형 #12 등록 + 봇 정지 전제 전수 sweep

설계서 추가: `AI_Bot_Design.md:1644` 「정정 8 — 결함 유형 #12」(29줄),
`:1673` 「정정 9 — scenarioSpec 규약」(25줄). R.2 절, 추가만. 총 54줄. 문서 1698줄.

**분류를 논증이 아니라 측정으로 바꿨다.** `AICompanionBot.MoveOwner` 열거형과
`noteMoveOwner()`를 틱 사슬 각 분기에 심고, `BotTestManager.withMoveOwner()`가 **모든** 판정
라인에 `moveOwner:.../idleOwnedTicks:n`을 덧붙인다. 이제 「이 하니스에서 16장 이동이 돌았는가」는
판정 라인에서 읽힌다.

- [I] 46 클래스 — `TestUser.spawn` 없음 → `BotIdle.java:74-75`에서 즉시 반환. 미도달이 코드 근거.
- [II] 19 클래스 33팔 — 전부 재실행. 값은 `docs/bot_motion_sweep.md` §3.
- [III] 4 — rule2_deny, rule_dcell, pickup_natural, pickup_gift(PASS이나 근거가 검증 대상과 다름).

**결과: 29 PASS, 4 FAIL.**

### 계측이 블록 K의 내 가설을 반증했다

`bot_rule2_deny`: `moveOwner:MELEE:200, idleOwnedTicks:0`. **16장 이동은 한 틱도 돌지 않았다.**
블록 K에서 「16장 배회가 봇을 대상 쪽으로 밀어 넣은 것이 가장 유력」이라고 쓴 것은 틀렸다.
접근(`botApproachSum:15.42`)과 타격(`damageDealt:88.6`)은 근접 컨트롤러가 직접 만든 것이므로
이 건은 **유형 #12가 아니라 유형 #10**이다 — `allowMelee:false`를 근접 컨트롤러가 읽지 않는다.

`bot_rule_dcell`: `MELEE:173/IDLE:67`, `dCellTicks:196`인데 `lateralCommandTicks:1`.
L-4(2)의 물음에 대한 답 — **횡이동 명령이 사실상 발행되지 않는다(196틱 중 1틱).**
따라서 원인은 이동 소유권 밖이다.

`bot_pickup_natural`: `idleOwnedTicks:200` — 유일하게 순수한 유형 #12.

    [INVALIDATES] bot_creeper_wall  (블록 K PASS -> 지금 1/3, canary MISMATCH x2, id 276->280)

`bot_creeper_lowfuse`는 반대로 K의 FAIL에서 PASS 3/3 canary OK로 돌아왔다. 두 하니스가 서로
반대로 뒤집혔다 — **비결정성**이며 어느 쪽도 단독으로는 증거가 아니다. L-5의 단일 변수 확인
(doTileDrops 되돌린 팔)은 미실행.

# 블록 M — 계측 정합 계속

## M-1 RUN_ID 신선도 게이트 + 빌드 락 (M-3 이전에 완료)

- `BotTestManager.java:164` `RUN_ID = System.getProperty("bottest.runid","NORUNID")`.
  판정 라인(`:404`, `:406`)과 SCENARIO 라인(`:147`)에 `runId=`를 찍는다.
  `build.gradle:48-49`가 `-PbottestRunId`를 `-Dbottest.runid`로 전달한다.
- 완료 판정이 3조건이 됐다: 하니스 이름 일치 AND 기대 N == 수신 N AND **RUN_ID 일치**.
  불일치는 PASS가 아니라 **STALE**. `runlong4.sh`의 게이트가 `GATE name=… verdict=STALE …`로 찍는다.
- **폴링으로 완료를 판정하지 않는다.** 종료 마커는 `BATCH-END <RUN_ID>`이며, 그 라인이 나오기
  전의 어떤 중간 판독도 완료로 취급하지 않는다. 시작 마커는 `BATCH-BEGIN <RUN_ID> count=N`.
- **기계적 락**: `runlong4.sh`가 `batch.lock`을 잡고, 존재하면 빌드를 시작하지 않는다.
  값으로 확인함 — 락 보유 상태에서 배치 시도:
      BUILD-REFUSED lock held by pid 99999 since 2026-07-26 07:20:45
      exit=9
  락 해제 후 같은 명령:
      RUN_ID=R20260726T072045Z-3549 / BATCH-BEGIN R20260726T072045Z-3549
- (5) 「RUN_ID 도입 이전 기록은 신선도 미확정」은 블록 L 항목 첫 줄에 이미 있다. 확인함.

## M-4 계측 불변식 — 「틱당 정확히 하나」를 값으로 잠갔다

`AICompanionBot.java:65` `moveOwnerNotesThisTick`, `:107` `checkMoveOwnerInvariant()`(틱 말미 호출,
`:314`). 1이 아닌 틱만 `moveOwnerAnomalies`에 센다. `BotTestManager.java:172`가 모든 판정 라인에
`moveOwnerAnomalyTicks:n`을 싣는다. 합이 창 길이와 맞는 것은 증명이 아니다 — 0회 틱과 2회 틱이
상쇄되어도 합은 맞는다. **계측 자체가 유형 #8의 대상**이므로 서술이 아니라 값으로 잠근다.

## M-2 유형 #12 범위 보정

(1) `MoveOwner` 전체 항목: REFLEX, CREEPER, RESCUE, SURVIVAL, MELEE, RANGED, PICKUP, IDLE (8).
    T5.1 능력 A*는 **별도 소유자가 아니다** — 목표를 실행하는 planner/mover이므로 목표를 세운
    소유자에 귀속된다. T5.3은 이동을 지시하지 않는다(수면은 포즈만). 9장은 대상을 배정해
    MELEE/RANGED로 나타나고, D칸은 RANGED 안이다. **빠진 주체 없음.**
(2) 설계서 `AI_Bot_Design.md:1695` 「정정 10 — 유형 #12 범위 보정」 **21줄** 추가. 정정 8 무수정.
(3) [I]의 근거가 「16장이 안 돈다」만 증명한다는 지적을 받아들인다. 계측을 정밀화했다 —
    `BotIdle.commandedTicks()`(:62)는 **16장이 실제로 이동을 지시한 틱**만 센다.
    판정 라인에 `idleCommandedTicks`로 실린다. 「자율 이동이 없었다」의 증거는 이 값이다.

## M-5(3) 결정성 분류 규약

설계서 `AI_Bot_Design.md:1716` 「정정 11 — 하니스 결정성 분류와 n의 근거」 **20줄** 추가.
원칙: n=1로 닫는 하니스는 결정적임을 값으로 보인 것에 한한다. 결정성을 측정하지 않은 하니스의
현재 기록은 **PASS가 아니라 미확정**이다. n=3이 뒤집힘을 잡지 못한다는 것이 관측 근거다.

## M-7(1) rule_dcell — 회귀가 아니라 미구현이다 (bisect 불필요)

D 칸의 횡이동·거리확보 명령을 발행하는 코드는 **정확히 한 곳**이다:

    BotRangedCombat.java:186   bot.xxa = distanceCritical ? 1.0F : 0.0F;

그 지점의 도달 조건은 (a) `BotRangedCombat.tick`이 도는 것 = **RANGED 분기**
(`AICompanionBot.java:247`, `rangedCombat.hasTarget()` 필요), (b) `dist < bandMin`, (c) `!kiteable`.

`bot_rule_dcell`의 측정 소유자 히스토그램은 **MELEE:173 / IDLE:67 — RANGED:0**이다.
`BotRangedCombat.tick`이 한 틱도 돌지 않았으므로 **유일한 발행 지점이 도달 불가**다.
즉 근접 컨트롤러가 대상을 쥐고 있을 때 D 칸 결정을 행동으로 옮기는 경로가 **존재하지 않는다**.
회귀가 아니라 미구현이며, **bisect는 무의미하다.**

이전 PASS의 근거를 다시 읽었다: 판정량이 `distanceCritical`(플래그)이었다. 그 플래그는
`BotProtection`이 분기와 무관하게 세우므로, 그 PASS는 **행동이 아니라 판정을 재고 있었다.**
지적대로다. 유형 #10.

## M-6 rule2_deny는 기존 부채로 설명된다

(1) §9(B) 「규칙2가 보호 계층에만 걸려 직접 교전 경로 우회(bot_melee가 meleeCombat 직접 호출)」를
    **예측 → 확인됨**으로 갱신한다. 확인 측정값:
    `allowMelee:false, moveOwner:MELEE:200, idleOwnedTicks:0, ticksInMeleeRange:182, damageDealt:88.6`.
(2) 고치지 않는다. 규칙2를 근접 컨트롤러에 배선하는 것은 계층 우선순위 변경이고
    설계서 1487행 T5.6 「계층 간 우선순위 충돌 해소」의 소관이다. **T5.6 최우선 입력**으로 표시.
(3) `damageDealt`는 포화량이 맞다. `BotMeleeCombat.java:21` `MIN_ATTACK_INTERVAL = 13`틱이므로
    182틱 근접권 체류에서 타격 횟수는 ⌊182/13⌋≈14로 상한이 걸린다. K→L에서 접근량이
    12.04→15.42(+28%)로 변했는데 damageDealt는 88.6으로 **소수점까지 동일**한 것이 포화의 증거다.
    규칙2 위반의 크기 지표를 `ticksInMeleeRange`로 교체한다(하니스 측정 정의 수정, 배치 종료 후 적용).

## 블록 M-3 — [I] 46종 재실행 (RUN_ID 게이트 하)

RUN_ID R20260726T072157Z-4478. BATCH-BEGIN 07:21:57Z → BATCH-END 08:33Z, 52 이름, 약 71분.
게이트 산출 전문: docs/bottest_gate_blockM3.txt (52 GATE 라인).
STALE 0, MISSING 0. PASS 48, FAIL 4.

핵심 값 — 계측이 실린 49개 판정 라인 전부 idleCommandedTicks:0, moveOwnerAnomalyTicks:0.
그중 40개는 idleOwnedTicks>0이다. 두 값을 구분하지 않았으면 [I] 46종 중 대부분이 오탐으로
재분류될 뻔했다. 상세는 docs/bot_motion_sweep.md §6.

FAIL 4건: bot_path_blocked([INVALIDATES], canary MISMATCH — 격리 결함, bot_creeper_wall과 동일 서명),
bot_persist_load / bot_dodge / bot_kite_execmon (이전 PASS 기록 없음 → 최초 기록).
bot_kite_execmon은 설계서:958 서술과 하니스 expected가 충돌한다 — 스펙 충돌로 기록만 하고 멈춘다.

# 블록 O — 진단 배치 (수정 없음)

## O-0 bot_kite_execmon — 스펙 충돌 철회. 하니스가 옳다

사용자 판정을 받아들인다. 설계서:958의 「대상이 이미 밀착 → 발화 아니오」는 **요구사항이 아니라
결함 기록**이다. 바로 아래 :959-961이 그것을 결함이라 부르고 올바른 술어를 적고 고치지 않은 이유를
밝힌다 — 「B의 결함은 속도가 아니라 적용 범위다 … 두 술어는 다르다 … 술어 변경은 B의 정의 변경이므로
여기서 임의로 하지 않는다」. 내가 표의 한 칸만 읽고 그 아래 세 문장을 판정에 넣지 않았다.
하니스를 고치지 않고 설계서도 고치지 않으며 FAIL을 유지한다.

**부채 갱신 — 「신호 B의 술어(적용 범위)」: 예측 → 확인됨. T5.6 입력.**
확인 측정값 (RUN_ID R20260726T072157Z-4478):

    startedKiteable:true  execMonitorFired:false  ticksToExec:-1  ceiling:20
    gapAtFlee:0.91  gapAtExec:-1.00  gapClosed:0.57  observedAtFlee:0.0000
    intentOpenTicks:224  maxFailingTicks:3  failingTicksTotal:116  enterLine:0.2722

설계서가 예측한 그대로다: 간격이 이미 0에 가깝고(gapAtFlee 0.91) 벌어지지 않는 상태에서
d(gap)/dt 술어는 발화하지 않는다(execMonitorFired:false, ticksToExec:-1). 대조군
`bot_kite_execmon_lat`은 같은 배치에서 PASS다 — 간격이 실제로 좁혀지는 조건에서는 B가 잡는다.
**즉 B는 「느린」 것이 아니라 「이 실패 상태를 볼 수 없는」 것이다.** 술어 변경은 T5.6에서 결정한다.

### O-0(3) hp 손실 15.7 → 23.7 (+51%)

전제는 같다 — 봇 최대체력 200 (이 원장 301행). 나란히 댈 수 있는 이전 값은 **hp 15.7 하나뿐**이다.
`gapAtFlee` / `gapClosed` / `failingTicksTotal` / `intentOpenTicks`의 이전 기록은 설계서에도
이 원장에도 `docs/bottest_lines_blockK.txt`에도 **없다**. 설계서:958의 표 칸은 「없음 (hp 15.7 손실)」
한 항목만 싣는다. 그러므로 「무엇이 달라졌는지」를 값으로 답할 수 있는 축은 현재 hp 하나뿐이고,
그 하나로는 원인을 특정할 수 없다.

특정을 막는 것이 무엇인지는 값으로 말할 수 있다. `intentOpenTicks:224`는 관측 창(대략 240틱)의
93%다. 즉 봇은 창의 거의 전 구간을 「카이팅 의도가 열린 채 밀착」 상태로 서 있었고, 그동안 받는
피해는 **B의 발화 여부가 아니라 그 구간 길이 × 대상 DPS**로 정해진다. 발화가 0인 두 실행에서
hp 손실만 다른 것은 구간 길이나 대상의 타격 성공에 달린 문제이지 B의 상태 변화가 아니다.
따라서 **+51%는 B의 악화 증거가 아니며, 개선 증거도 아니다.** 이 축은 대가의 크기를 재는 데
쓸 수 없다 — 부채로 남긴다: 「execmon 계열에 구간 길이 정규화 지표(hp손실/intentOpenTick)가 없다」.

## O-3 bot_persist_load — 코드 결함이 아니다. 하니스 전제가 배치에 의해 파괴됐다

세 갈래 중 **(a)**다. 다만 「botExists가 저장 후 false로 남는다」가 아니라
**「저장 자체가 이 실행 이전에 존재하지 않았다」**이다.

증거 (파일:행):
- `BotPersistLoadTest.java:9-12` 클래스 주석 — 「runs on the boot AFTER BotPersistSaveTest」.
  이 하니스는 **두 번의 부팅**을 전제한다. 1회차가 저장하고 2회차가 복원한다.
- `runlong4.sh:40` — 매 하니스마다 `rm -rf run/world`. 즉 배치는 하니스마다 **월드를 지우고**
  단일 부팅으로 실행한다. bot_persist_save가 만든 저장은 bot_persist_load 차례가 오기 전에 삭제된다.
- `BotManager.java:136-138` — `if (!data.botExists()) { LOGGER.info("[BOT] restore skipped — no bot recorded"); return; }`
- 실측: `scratchpad/p5_bot_persist_load.log:870`
  `[BOT] restore skipped ? no bot recorded` — 정확히 그 줄이 찍혔다. 복원 훅은 **호출됐고**,
  기록이 없어서 스펙대로 아무것도 하지 않았다.

즉 (b) 「훅이 호출되지 않는다」도 (c) 「배관 재실행이 실패한다」도 아니다. 훅은 돌았고 판단은 옳았다.
**FAIL은 유형 #9(시나리오 전제 미기록)다** — 하니스는 2부팅 전제를 코드 주석에만 적었고
`scenarioSpec()`으로 선언하지 않았으며, 배치 러너는 그 전제를 알 방법이 없었다.

### O-3(2) 스펙 뒷절은 이번 실행이 확인했다

「false면 스폰 안 함」(설계서:1260). `botExists=false`인 부팅에서 `restored:false, uuid:none`.
**뒷절은 값으로 PASS다.** 판정 라인이 FAIL인 것은 앞절만 재기 때문이다 —
하나의 하니스가 두 절을 반대 방향으로 물어보고 있고, 지금 배치 조건에서는 뒷절만 성립한다.

### O-3(3) 원장 정정 — 크게

    T1.2 검증 3(복원)은 통과 기록 없이 완료로 적혀 있었다.

`bot_persist_load`의 PASS 기록은 `docs/bottest_lines_blockK.txt`(85 라인/48 하니스)에도,
이 원장에도, 설계서에도 없다. Phase 1을 완료로 적은 근거에 이 항목의 값이 들어간 적이 없다.
지금 이 하니스는 **단독 실행으로는 구조적으로 통과할 수 없다** — 러너가 월드를 지우기 때문이다.
고치지 않는다(O-3(3) 지시). 회수 경로만 적어 둔다:
`bot_persist_save` → (월드 보존) → `bot_persist_load`의 2부팅 쌍을 하나의 배치 단위로 다루고,
그 전제를 `scenarioSpec()`에 싣는 것. 부채 (A) 하니스로 회수 가능.

### O-3(4) 일상 사용에 미치는 영향

**아직 알 수 없다** — 이번 값은 복원 경로를 시험하지 않았으므로 「서버 재시작 시 봇이 사라지는가」에
대한 답이 아니다. 이 실행이 말하는 것은 「기록이 없으면 스폰하지 않는다」뿐이고, 기록이 있을 때의
동작은 이 배치에서 한 번도 실행되지 않았다. 영향 문장을 지금 쓰면 그것이 곧 유형 #13이다.
