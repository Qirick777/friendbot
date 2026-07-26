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

## O-1 bot_dodge — 재는 방식이 먼저다. 산수는 맞고, 임계의 분모는 스펙에 없다

### O-1(1) 50 트라이얼 피격 수 히스토그램 (RUN_ID R20260726T072157Z-4478)

    피격 0발 : 18회
    피격 1발 : 27회
    피격 2발 :  4회
    피격 3발 :  1회
    피격 4발 :  0회
    합계 50회, 발사 화살 200발(트라이얼마다 정확히 4발), 명중 38발, 회피 162발

트라이얼 통과(=피격 0)는 18회로 `passed:18`과 일치한다. **화살 단위 회피율은 162/200 = 0.810**이다.

### O-1(2) 이항 대조 — 화살은 독립과 모순되지 않는다

트라이얼 통과율에서 역산한 p는 0.36^(1/4) = **0.775**이고, 화살 표본에서 직접 센 p는 **0.810**이다.
두 값을 각각 기대분포로 놓고 관측과 맞춰 본다(3발·4발은 기대도수가 작아 합쳐 4칸으로 본다).

    관측                 18 / 27 /  4 / 1
    기대 p=0.810       21.5 / 20.2 / 7.1 / 1.2    카이제곱 4.25, 자유도 2, p≈0.12
    기대 p=0.775       18.0 / 21.0 / 9.1 / 1.9    카이제곱 5.01, 자유도 2, p≈0.08

**둘 다 5% 수준에서 기각되지 않는다. 독립 가정과 모순되는 증거는 없다.** 다만 관측은 1발 칸이
기대보다 높고 2발 칸이 낮다(27 vs 20~21, 4 vs 7~9) — 이항보다 약간 **과소산포**이며, 이는 화살
사이에 약한 음의 상관(한 번 맞으면 다음이 덜 맞음)이 있을 때 나오는 모양이다. 과소산포는 p^4가
p를 **과소평가**하게 만들고, 실제로 0.775 < 0.810이다. 방향이 일관된다.

lateral 산포 2.17~17.21이 독립을 깨지 않느냐는 물음에는: 깨지 않는다. lateral의 최대값 17.21은
t1 한 번뿐이고 나머지 49회는 2.17~8.17에 있으며, 피격 수와의 관계도 단조가 아니다
(피격 0인 t27 lateral 2.17, 피격 1인 t41 lateral 2.44, 피격 3인 t30 lateral 8.17).
**lateral은 판별력이 없다** — 하니스 주석 :36-37이 이미 그렇게 적어 두었고 값이 그것을 재확인한다.

### O-1(3) 화살 단위 판정량을 실었다 (통과 조건은 안 바꿨다)

`BotTest.aggregateExtra()` / `resetAggregate()` 훅을 추가하고 `BotDodgeTest`가
`arrowsTotal / arrowHitsTotal / perArrowEvade / perArrowWilson95Lower`를 판정 라인에 함께 싣는다.
매 트라이얼 새 인스턴스가 생성되므로(`BotTestManager:363` `BotTestRegistry.create`) static 누적이다.
**판정은 여전히 트라이얼 단위로만 내린다** — 어느 쪽이 스펙의 분모인지는 하니스가 정할 일이 아니다.

M-3 표본으로 계산한 값: 회피 162/200, `perArrowEvade 0.810`, **`perArrowWilson95Lower 0.750`**.

이 수 하나가 판별의 전부다:
- 임계 0.80이 **트라이얼당**이면 → wilson95Lower 0.241, 스크리닝선 0.65에 크게 미달 = **기능 결함**.
- 임계 0.80이 **화살당**이면 → wilson95Lower 0.750, 스크리닝선 0.65를 **통과**하고 확정선 0.80은
  미달 = 설계서:910의 정의상 **「T5.6 확정 게이트 이월」**이지 기능 결함이 아니다.

같은 봇, 같은 실행, 정반대 결론이다.

### O-1(4) 방패 팔인가 무방패 팔인가 — 무방패 팔이다

`BotDodgeTest.java:99`
`bot.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY); // no shield → sidestep`
**방패 있음 팔은 이 하니스에 존재하지 않는다.** R1의 방패 갈래는 `bot_shield`라는 별개 하니스다.

L-6이 물었던 「R1의 '아니면'(수직 사이드스텝) 갈래가 실제로 도는가」의 답 일부가 여기 있다:
**돈다.** 50 트라이얼 전부 `lateral > 1.0`이고 최소값이 2.17이다. 사이드스텝은 언제나 일어났고,
실패한 32회는 「사이드스텝을 안 해서」가 아니라 **「사이드스텝을 했는데도 맞아서」**다.
이것은 유형 #10(판정-행동 단절)이 **아니다** — 행동은 발행됐고 결과가 부족한 것이다.

### O-1(5) 임계 0.80의 출처 — 설계서에 없다. 스펙 공백이다

설계서:1389 T4.2 [검증] 전문:
「(b) 스켈레톤이 봇에 화살 발사 → 봇이 사이드스텝해 회피(화살 명중 안 함, 봇 체력 불변)하는지.
**판정: (a) 오프핸드 아이템==토템, (b) 화살 발사 후 봇 체력 불변.**」

확률도 비율도 임계도 없다. 「체력 불변」이 전부이며, 그 문장은 **화살당인지 트라이얼당인지도
말하지 않는다**. 0.80은 `BotDodgeTest.java:54-56`이 스스로 정한 수이고, 같은 파일 :43의 주석이
그것을 「the spec threshold (0.80)」이라 부른 것은 **오라벨(유형 #8)**이다. 주석을 정정했다
(코드 동작은 건드리지 않았다).

**분모도 임계도 스펙 공백이다. 내가 정하지 않는다.** 사용자 판단 대상으로 올린다.

## O-2 격리 결함 — 원인을 값으로 특정했다. 잎의 `distance`다

### O-2(0) 내 서술 정정

「블록 id가 트라이얼마다 단조 증가한다」는 **틀렸다**. 같은 보고 안의 412→408이 감소다.
사용자 지적대로 불변량은 **|Δid| = 4**이고, 서로 다른 기준(276·296·412·244)에서 전부 4다.
그리고 이 결함을 보고 있는 하니스는 둘이 아니라 **셋**이다 —
`bot_creeper_lowfuse`(K, 296↔300), `bot_creeper_wall`(L, 276→280), `bot_path_blocked`(M-3, 412→416 / 412→408).

### O-2(1) 블록 종류 — 세 번째 요구에 대한 답

RUN_ID `O20260726T085316Z-9710`, `bot_creeper_wall`, 트라이얼 2 직전 카나리:

    coreBlocks changed:21  first(-7,-1,+1) id 244->240
    (-7,-1,+1) minecraft:oak_leaves[distance=2,persistent=false,waterlogged=false]
            => minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]
    (-7,-1,+2) oak_leaves[distance=3,...] => oak_leaves[distance=2,...]
    (-7,-1,+3) oak_leaves[distance=4,...] => oak_leaves[distance=3,...]
    (-6,+2,+6) oak_leaves[distance=3,...] => oak_leaves[distance=2,...]
    (-6,+2,+7) oak_leaves[distance=2,...] => oak_leaves[distance=1,...]
    (-5,+2,+5) oak_leaves[distance=3,...] => oak_leaves[distance=2,...]
    (-5,+3,+5) oak_leaves[distance=4,...] => oak_leaves[distance=3,...]
    (-5,+3,+6) oak_leaves[distance=3,...] => oak_leaves[distance=2,...]   (+13 more)

트라이얼 3 직전: `changed:13`, 같은 첫 좌표, 같은 속성, 같은 방향.

**바뀌는 것은 블록이 아니라 속성 하나다 — `distance`. 매번 정확히 한 칸.**
그리고 |Δid| = 4의 정체가 여기서 닫힌다: `oak_leaves`의 상태는 distance(1~7) × persistent(2) ×
waterlogged(2) = 28개이고, 속성은 알파벳 순(distance, persistent, waterlogged)으로 뒤가 빨리 도므로
**distance 한 칸 = 2 × 2 = 4 id**다. |Δ| = 4는 무작위 오염이 아니라 **distance 정확히 한 칸**이었다.

### O-2(2) 이웃 의존 속성인가 — 그렇다. 가설이 맞다. 여기서 멈춘다

`distance`는 「가장 가까운 원목까지의 거리」다. 정의상 **이웃에서 파생되는 속성**이며,
`LeavesBlock`이 이웃 갱신·예약 틱으로 재계산한다. 사용자 가설과 정확히 일치한다.

인과 사슬 전체를 값으로 적는다:
1. 판정 코어는 `TrialCanary.java:299-302`에 의해 dx −7..+7, dz −7..+7이다(선언 상자 ∩ ±8, 1칸 축소).
   세 하니스 모두 `arenaBounds`를 선언하지 않아 기본 {−24,24,−24,24}를 쓴다.
2. `bot_creeper_wall`이 실제로 시공하는 범위는 `BotCreeperWallTest.java:63-64` dx −6..+8, dz −4..+4다.
3. 위 21칸 중 **모든 칸이 dx = −7이거나 dz ≥ +5** — 즉 **하니스가 한 번도 건드리지 않는 자연
   지형인데 판정 코어 안**이다. 판정 코어가 시공 범위보다 넓다.
4. 그 차집합에 월드젠 참나무가 서 있고, 그 잎의 `distance`를 정하는 원목은 복원 상자 밖에 있을 수 있다.
5. `TrialCanary.java:210` `restore()`는 `UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE`로 쓴다 — 이웃 갱신
   없음, 형상 재도출 없음. 저장된 id를 그대로 눌러 넣지만, 이후 예약 틱이 실제 원목 배치에서
   distance를 다시 도출하면 baseline과 어긋난다. `randomTickSpeed=0`은 예약 틱을 멈추지 않는다
   (같은 파일 :38-39가 유체에 대해 이미 적어 둔 사실).

**형태가 정정 7과 같다: 격리 장치 자체가 오염원이다.** 격리 계약 문장을 고치는 일이므로
`restore()`의 플래그도 판정 코어의 범위도 **바꾸지 않았다.** 어긋남을 보고하고 멈춘다.

## O-4 부채 항목 근거 감사

### O-4(0) 감사 범위에 대한 정직한 진술

사용자가 지목한 **「§9 부채」·「핸드오프 §8」 문서는 이 저장소에 없다.** 저장소의 문서는
`AI_Bot_Design.md`(설계서 + 로드맵 R절), `docs/batch_run_log.md`(이 원장),
`docs/bot_motion_sweep.md`, `docs/rule1_redesign_brief.md`, 두 개의 로그 산출 파일뿐이고
어느 것에도 §8·§9 번호 체계가 없다. 따라서 아래 감사는 **저장소 안에서 상태를 주장하는 문장
전부**를 대상으로 한다. 사용자의 §9 원문과 항목이 어긋나면 원문을 주면 그 목록으로 다시 한다.

### O-4(1)(2) 상태 주장 문장 감사 — [값 있음] / [값 없음, 낙관 서술]

    [값 없음, 낙관 서술]  bot_dodge 「T5.6 확정 게이트(n=100) 이월」
      중립 서술로 바꾼다 → 「스크리닝 통과 기록 없음. 임계의 분모가 스펙에 없어 판정 미확정」.
      사용자 지적이 맞다. 「이월」은 설계서:910의 정의상 스크리닝 통과를 전제하는 말인데
      스크리닝 통과 기록이 없었다. 다만 지금은 값이 있고, 그 값이 두 갈래다 —
      트라이얼 단위 Wilson 하한 0.241(스크리닝선 0.65 미달 = 기능 결함),
      화살 단위 Wilson 하한 0.750(스크리닝선 통과, 확정선 0.80 미달 = 이월).
      **어느 쪽인지는 분모가 정해져야 정해진다. 지금은 「미확정」이 유일하게 정확한 상태다.**

    [값 있음]  신호 B의 술어(적용 범위) — O-0(2)에서 「예측 → 확인됨」으로 갱신. T5.6 입력.
      execMonitorFired:false / ticksToExec:-1 / gapAtFlee:0.91, 대조군 execmon_lat PASS.

    [값 없음, 낙관 서술]  Phase 1 완료 (T1.2 검증 3 「복원」)
      중립 서술로 바꾼다 → 「T1.2 검증 3(복원)은 통과 기록 없이 완료로 적혀 있었다」.
      근거는 O-3(3). 저장소 어디에도 bot_persist_load의 PASS 값이 없다.

    [값 없음, 낙관 서술]  bot_kite_execmon 「기준선이 없다」(내가 M-3 보고에서 쓴 문장)
      중립 서술로 바꾼다 → 「PASS/FAIL 판정 기록은 없으나 측정 기준선 hp 15.7이 설계서:958에 있다」.
      사용자 지적이 맞다. 내가 직접 인용해 놓고 같은 보고에서 「기준선 없음」이라고 썼다.
      단, O-0(3)에서 확인한 대로 **비교 가능한 축은 hp 하나뿐**이고 나머지 진단값은 실제로 없다.

    [값 있음]  블록 K-2 (a) 인정 — R1 발화율 before/after (원장 K-3, ①·② 값 실려 있음)
    [값 있음]  M-6 rule2_deny → 기존 부채 귀속 (원장 613행, 확인 측정값 동반)
    [값 있음]  M-7(1) rule_dcell 미구현 (코드 근거 BotRangedCombat.java:186, lateralCommandTicks:1)
    [값 있음]  블록 I 「부채 회수」 4경로 (원장 308~330의 판정값 표가 각 항목을 뒷받침)
    [값 있음]  블록 J scenarioSpec 「유형 #9 회수」 (부적절한 전제 목록이 값과 함께 실려 있음)

    [값 없음, 낙관 서술]  bot_creeper_lowfuse 「PASS 3/3 canary OK로 돌아왔다」(sweep §5)
      중립 서술로 바꾼다 → 「K FAIL, L PASS, O PASS. 세 실행이 같은 코드에서 갈렸으므로
      단독 실행은 어느 쪽도 증거가 아니다」. sweep 문서가 이미 「비결정성」이라 적었으나
      「돌아왔다」는 회복을 함의하는 말이라 중립이 아니다.

    [값 없음, 낙관 서술]  「행동 검증 8종을 이번 배치에 포함했다」(K-6 ①)
      중립 서술로 바꾼다 → 「포함했고, 그중 3종이 지금도 FAIL이다(rule2_deny, rule_dcell,
      그리고 M-3에서 path_blocked)」. 포함 사실은 참이나 회수는 아니다.

### O-4(3) 이것은 문서 작업이다

코드는 건드리지 않았다. 위 중립 서술이 이후 보고의 표준 표현이다.

### O-2(3) L-5 단일 변수 확인 — doTileDrops는 원인이 아니다. K-7은 옳았다

`-Dbottest.tiledrops=true`로 K-7의 두 변수 중 하나만 되돌린 팔. SCENARIO 라인에
`doTileDrops=true`가 찍혀 두 팔이 섞일 수 없다. RUN_ID `O20260726T085316Z-9710`.

    하니스                  doTileDrops=false        doTileDrops=true
    bot_path_blocked        PASS 3/3 canary OK       FAIL 1/3 canary MISMATCH x2
    bot_creeper_wall        FAIL 1/3 MISMATCH x2     FAIL 1/3 MISMATCH x2
    bot_creeper_lowfuse     PASS 3/3 canary OK       PASS 3/3 canary OK

drops=true 팔에서 새로 나온 어긋남의 정체:

    bot_path_blocked  items 1->0; user/types[none|types:item=1,player=1] -> [none|types:player=1]
    bot_creeper_wall  items 4->0; types[creeper=1,item=4,player=2] -> [creeper=1,player=2]

**아이템 엔티티다.** 시공이 자연 블록을 부수며 떨군 드롭이 baseline에 잡히고, 다음 트라이얼의
스윕이 그것을 지우면서 어긋난다 — K-7이 끄기로 한 바로 그 현상이며, **K-7의 판단은 값으로 옳다.**

그리고 사용자 예측대로다: drops=true 팔의 `bot_path_blocked` 트라이얼 3에서 **잎 어긋남이 여전히
같이 나온다** — `(+0,-1,-7) oak_leaves[distance=6] => oak_leaves[distance=5]`, id 260→256, 다시 |Δ|=4.
**두 변수는 분리됐다.** 아이템 어긋남은 doTileDrops에 달려 있고, 잎 `distance` 어긋남은 달려 있지 않다.

### O-2(4) 게이트 1(결정성)의 대상을 좁힐 근거

같은 코드·같은 게이트에서 `bot_path_blocked`가 M-3 FAIL 1/3 → 블록 O 표준 팔 PASS 3/3로 뒤집혔다.
`bot_creeper_lowfuse`는 K FAIL → L PASS → O PASS → O(drops) PASS. **비결정성은 재확인된다.**

다만 이제 원인이 하나로 좁혀지므로 대상도 좁아진다. 어긋나는 칸은 예외 없이
**「판정 코어(±7) 안이면서 하니스가 시공하지 않는 칸」**이었다. 판정 코어는
`TrialCanary.java:299-302`가 선언 상자 ∩ ±8을 1칸 축소해 만들고, 시공 범위는 각 하니스 `setup()`의
루프다. 두 범위를 코드에서 대조하면 「자연 지형을 판정하는 하니스」 목록이 값 없이도 나온다.

시공이 판정 코어를 **완전히 덮는**(= 자연 지형을 판정하지 않는) 하니스:
`bot_equip`(시공 ±8 ⊇ 코어 ±7), `bot_rule4_pierce/normal/axe`(−10..18 × ±12),
`bot_rule_dcell`(−36..20 × ±44), `bot_kite_execmon/flip/recover`(−90..20 × ±10~12),
`bot_warden_band_below`(−50..50 × ±8).

시공이 **아예 없어** 판정 코어 전체가 자연 지형인 하니스:
`bot_alive`, `bot_death`, `bot_persist_save`, `bot_persist_load`, `bot_phase2_combo`,
`bot_ranged`, `bot_single`, `bot_coldstart_dist`.

나머지는 부분 노출이다(예: `bot_creeper_wall` 시공 dx −6..8 × dz −4..4 → dx=−7과 dz ±5..7이 노출,
관측된 21칸이 정확히 그 띠에 있다. `bot_path_blocked` 시공 −2..14 × ±4 → dz=−7 노출,
관측된 칸이 `(+0,-1,-7)`이다).

**제안**: M-5(1)의 52종 × n=10 대신, ① 위 「완전히 덮음」 목록을 결정성 후보에서 제외하고
② 노출된 하니스 중 그 노출 띠에 **이웃 의존 블록**(잎·울타리·유리판·계단·waterlogged)이 실제로
서 있는 것만 n=10을 돌린다. 대상 선정은 사용자 몫이다 — n=10은 돌리지 않았다.

## O-5 게이트 8 — §6 실측 상수 9종

### O-5(1) 오염 위험 판정 — 9종 전부 [무관], 근거는 코드 인용

「속도만 재니까」가 아니라 「이 창에서 IDLE이 이동을 지시할 수 없다」의 코드 인용으로 답한다.
사슬은 셋이고 하나만 끊겨도 16장 이동은 도달하지 않는다.

1. `Perception.java:128-141` `nearestUser()` — `for (ServerPlayer p : level.players())`에서
   봇을 제외한 최근접 플레이어를 고른다. `Perception.java:68` `user = nearestUser(...)`.
   즉 **레벨에 봇 말고 다른 ServerPlayer가 없으면 `user == null`이다.**
2. dev 서버(`-Dbottest.auto`)에는 접속 플레이어가 없다. 가짜 플레이어를 만드는 유일한 경로는
   `TestUser.spawn`이며, **9종의 소스 7개 파일 전부 `TestUser` 참조가 0회다**
   (`grep -c TestUser` = 0: BotSpeedProbeTest, BotColdStartDistTest(+Tail1/2/3),
   BotRule1FastTest, BotWindowVarianceTest, BotWardenLiveSpeedTest, BotWardenPursuitTest,
   BotWardenProbeTest).
3. `BotIdle.java:86-89` — `ServerPlayer user = bot.perception().user; if (user == null) { return; }`.
   목표 설정도 정지 지시도 이 줄 아래에 있다. `BotPickup.java:87`·`:150`도 동일한 관문이다.

**따라서 9종 모두 [무관]이다.** 그리고 이 논증은 이제 값으로도 확인된다 —
판정 라인의 `idleCommandedTicks`가 0이어야 한다.

**이미 값이 있는 1종**: `bot_warden_probe`는 M-3 배치에 들어 있었고
`idleCommandedTicks:0, idleOwnedTicks:574`였다. 분기는 574틱 돌았고 이동 지시는 0틱이다.
나머지 8종은 블록 O 배치에서 값으로 확인한다(§O-5(3)).

### O-5(2) 전제 명시 — 9종 scenarioSpec에 봇 이동 활성 여부를 실었다

7개 클래스에 `scenarioSpec()`을 추가했다. 각 항목이 밝히는 것: 봇 최대체력 400 + 매 틱 만피 회복,
추격자 `setInvulnerable(true)`, 그리고 「유저 없음 → Perception.java:131이 봇 외 플레이어를 찾지
못해 user==null → BotIdle.java:87-89 즉시 반환. 16장 자율 이동 비활성이며 idleCommandedTicks:0로
값 확인된다」. 이제 이 전제는 `(unstated)`가 아니라 START 라인에 찍힌다.

### O-5(4) bot_warden_probe는 게이트 8 명단과 겹친다 — 이미 재측정된 것으로 처리해도 된다

겹친다. 그리고 M-3에서 RUN_ID 게이트 하에 재실행됐다. 판정:

    [BOTTEST] bot_warden_probe PASS runId=R20260726T072157Z-4478
      botSprint:0.2806  wardenChase:0.0477  margin:83.0%  chargeSeen:true(t=568,held=35)
      sonicDamage:10.0  moveOwnerAnomalyTicks:0  idleOwnedTicks:574  idleCommandedTicks:0

**`botSprint:0.2806`은 설계서:775·:816에 기록된 실측 상수와 소수점까지 같다.** 이 프로젝트의
기초 상수(진입선 0.2722 = 0.97 × 0.2806, MEET_SPEED 0.20의 근거, W60 안정성 판정의 기준)가
16장 자율 이동이 들어온 뒤에도 변하지 않았음을 값으로 확인한 셈이다. `sonicDamage:10.0`도
설계서 18장의 「소닉붐 고정 10」과 일치한다.

**다만 `wardenChase:0.0477`은 대조할 기록이 없다.** 설계서의 워든 속도 값들(W60 평균 0.2640,
추격 0.2766 등)은 `bot_warden_live_speed`·`bot_coldstart_dist` 계열이 생산한 것이고,
`bot_warden_probe`의 `wardenChase`가 §6 어디에 대응하는지는 원장에도 설계서에도 적혀 있지 않다.
이 축은 [값 없음]이며, 0.0477이 낮은 것은 워든이 그 창에서 추격 상태가 아니었을 가능성이
크지만 **그것은 추정이지 값이 아니다.** 부채로 남긴다:
「bot_warden_probe의 wardenChase가 §6의 어느 상수에 대응하는지 미기록」.

결론: `bot_warden_probe`는 **이미 재측정된 것으로 처리한다** — 자율 이동 오염은 값으로 배제됐고
botSprint는 기록값과 일치한다. 블록 O 재실행 명단에 중복으로 넣지 않았다.

### 운영 기록 — 블록 O 배치 중 소스 편집 1건

블록 O 배치가 도는 중에 `BotDodgeTest.java`의 **주석 한 곳**을 편집했다(:43의 「the spec
threshold」 오라벨 정정, O-1(5)). 주석은 컴파일 산출물에 들어가지 않으므로 행동 차이는 없고,
편집 시점에 `bot_dodge` 실행은 이미 시작돼 있었다. 그래도 **배치 중 소스 편집은 규율 위반**이므로
숨기지 않고 적는다. 04:43 사건(배치 중 gradle 동시 실행)과 달리 기계적 락은 여기 걸리지 않는다 —
락은 gradle 시작을 막을 뿐 파일 편집을 막지 않기 때문이다. 락의 사각이다.

## 블록 O 배치 결과 — RUN_ID O20260726T085316Z-9710

BATCH-BEGIN 08:53:16Z → BATCH-END, 16 실행. STALE 0, MISSING 0, runId 16/16 일치.
게이트 산출 전문: `docs/bottest_gate_blockO.txt`. 락 해제 확인.

### O-1(3) 화살 단위 집계 — 독립 표본 하나가 더 생겼다

    블록 O   trials:50 passed:18 wilson95Lower:0.241 canary:OK
             arrowsTotal:200 arrowHitsTotal:36 perArrowEvade:0.820 perArrowWilson95Lower:0.761
    M-3      trials:50 passed:18 wilson95Lower:0.241
             화살 200발 명중 38발      perArrowEvade:0.810 perArrowWilson95Lower:0.750

**두 독립 실행이 트라이얼 통과 수까지 똑같이 18/50이다.** 화살 단위도 0.810 vs 0.820으로 붙는다.
즉 `bot_dodge`는 **비결정 하니스가 아니다** — 밑에 깔린 비율이 잘 정해져 있고, 재실행이 값을
바꾸지 않는다. M-5(1)의 결정성 분류에서 이 하니스는 [결정적 비율]로 분류할 근거가 생겼다.

두 표본을 합치면(화살 400발, 명중 74발, 회피 326발):

    화살 단위   p̂ = 0.815   Wilson 95% 하한 = 0.774
    트라이얼    36/100      Wilson 95% 하한 = 0.273

n=100은 설계서:908의 **확정 게이트 표본 수**다. 그 표본으로도 결론은 분모에 따라 갈린다 —
화살 단위면 확정선 0.80에 0.026 미달(스크리닝선 0.65는 크게 통과), 트라이얼 단위면 0.273으로
스크리닝선에도 못 미친다. **분모가 정해지기 전에는 어느 쪽도 확정이 아니다.** 사용자 판단 대기.

### O-5(3) 게이트 8 재측정 — §6 기록값과 나란히

계측 확인 먼저: **9종 전부 `idleCommandedTicks:0`, `moveOwnerAnomalyTicks:0`.**
O-5(1)의 [무관] 판정은 이제 논증이 아니라 값이다. `moveOwner`는
speed_probe REFLEX:703/IDLE:657, coldstart REFLEX:2054/IDLE:9947, rule1_fast REFLEX:14/IDLE:226,
window_variance REFLEX:196/IDLE:2505, warden_live_speed IDLE:360, warden_pursuit RANGED:340/IDLE:80.

    하니스                  §6 기록값                      블록 O 재측정        판정
    bot_speed_probe         봇 스프린트 0.2806 (:775,:816) botSprint:0.2806     일치(소수점까지)
    bot_speed_probe         좀비 pk/attr 0.496 (:270)      0.496                일치
    bot_speed_probe         워든 단발 최고 0.2798 (:864)   peak:0.2798          일치
    bot_warden_pursuit      워든 추격 0.2766 sd 0.0068     30회 평균 0.2784     일치 (0.26σ)
                                                           sd 0.0045
    bot_coldstart_dist      n=30 평균 0.1937 sd 0.0883     0.1950 / 0.0902      일치
                            max 0.2658 (:939 (F))          max 0.2635
    bot_window_variance     워든 W20~W150 sd               .0176/.0092/.0008    일치(자릿수)
                            .0184/.0081/.0038/.0022/.0018  /.0036/.0026
    bot_window_variance     브루트 sd .0366/.0242/.0143    .0375/.0346/.0305    일치(자릿수)
    bot_rule1_fast          속도0.8 좀비 pk/attr 1.688     1.3083/0.80 = 1.635  3.1% 차이, sd 기록 없음

**규칙1 재설계 브리프와 T5.6 튜닝이 딛고 선 기초 상수 셋(봇 스프린트 0.2806, 워든 추격 ≈0.277,
워든 단발 0.2798)은 전부 재현됐다.** 16장 자율 이동이 들어온 뒤에도 변하지 않았다.

두 건은 변했다. 아래에 [INVALIDATES]로 남긴다.

    [INVALIDATES] bot_window_variance — 좀비 행만 8~11배 커졌다
      설계서:881  좀비 n=783  W20 .0018 / W40 .0013 / W60 .0010 / W100 .0008 / W150 .0006
      블록 O      좀비 n=804  W20 .0145 / W40 .0115 / W60 .0098 / W100 .0080 / W150 .0066
      비율        약 8.0 / 8.8 / 9.8 / 10.0 / 11.0 배
      nIndep은 39/19/13/7/5 → 40/20/13/8/5로 일치한다(표본 구조는 같다).
      cv로 보면 좀비만 1.6% → 13.1%로 뛰었고, **워든(6.9%→6.6%)과 브루트(60%→61%)는 그대로다.**
      즉 하니스 전체가 아니라 좀비 팔 하나가 움직였다.
      유형 #12는 배제된다: idleCommandedTicks:0. 후보는 `moveOwner:REFLEX:196` —
      반사 계층이 봇을 움직이면 추격자가 재경로를 잡고 창별 속도가 거칠어진다. R1 트리거는
      블록 G에서 정정됐고 그 이후 이 표는 재측정된 적이 없다. **단일 변수 팔이 필요하다
      (반사 억제 팔 1회). 이번 블록에서는 고치지 않는다.**

      **파급 범위 한정**: 설계서의 W60 창 채택과 밴드 도출은 이 표의 **워든 행**(W60 sd .0038)에서
      나왔다(:886-887). 워든 행은 재현됐으므로 **W60 채택·밴드 도출은 무효화되지 않는다.**

    [INVALIDATES] bot_warden_live_speed — 하니스가 값을 생산하지 못한다
      설계서:860-862  t1 0.2658 / t2 0.2658 / t3 0.2236 (실동 워든 관측 속도)
      블록 O          t1/t2/t3 전부 observedSpeed:0.0000, speedObserved:false, canKite:null
                      indepWholeSpan 0.0046 / 0.0008 / 0.0012 — 워든이 사실상 정지해 있다
      `moveOwner:IDLE:360` — 3 트라이얼 모두 **MELEE도 RANGED도 한 틱도 돌지 않았다.**
      봇이 교전 분기에 아예 들어가지 않았고, 봇이 도망치지 않으니 워든도 추격하지 않는다.
      대조: 같은 배치의 `bot_warden_pursuit`은 `RANGED:340/IDLE:80`으로 봇이 실제로 도주했고
      30/30 PASS에 draw 평균 0.2784를 냈다.
      **따라서 §6의 워든 실동 속도 상수 자체는 무효가 아니다** — 다른 하니스 둘
      (`bot_warden_pursuit` 0.2784, `bot_window_variance` 워든 W60 평균 0.2656)이 같은 값을
      독립적으로 재현한다. 무효가 된 것은 **이 하니스**다. 고치지 않는다.

`bot_coldstart_dist`와 tail1/2/3의 FAIL은 회귀가 아니다. 판정 조건이
「stable iff |mean − 결정선| ≥ 3sd(교전 간)」이고, 설계서:934-940 (G)가 이미 **불안정**으로
결론지은 상태를 그대로 재현한 것이다(`stable:false`, `aboveEnterLine:0`, `canKiteTrue:30/30`).
tail1/2/3은 시드 11/22/33의 새 표본이며 §6에 대응 기록이 없다 — 최초 기록이다
(평균 0.2062 / 0.2050 / 0.2201, sd 0.0701 / 0.0770 / 0.0647, 셋 다 `stable:false`).

# 블록 P — 격리 수리 + 잔여 진단

배치 RUN_ID `P20260726T103202Z-11681`, 5 실행. **게이트 4조건 전부 통과**:
이름 일치, 기대 N == 수신 N, RUN_ID 16/16 일치, 그리고 새 조건 —
`TREE-BEGIN == TREE-END = 9b70089…:da39a3ee…:df0a8596…` → **NOT-DIRTY**.
게이트 전문 `docs/bottest_gate_blockP.txt`.

## P-0 락 사각 봉쇄 — 소스 트리 불변 조건

러너가 배치 시작·끝에 트리 지문(`HEAD : sha1(git status --porcelain) : sha1(git ls-files -s src/** build.gradle)`)을
찍고, 다르면 `BATCH-DIRTY`와 함께 **모든** 게이트 라인에 DIRTY를 붙인다. DIRTY는 PASS가 아니다.
설계서 R.2 「정정 13」으로 등록했다. 이번 배치가 첫 실전이고 NOT-DIRTY로 통과했다.

부수 효과로 규율이 실제로 걸렸다: 배치가 도는 동안 설계서·원장·하니스 어느 것도 편집하지 못했고,
P-6의 scenarioSpec 채우기와 정정 13~15 등록은 전부 BATCH-END 이후로 밀렸다.

## P-1 격리 수리 — MISMATCH 0. 게이트 1의 판정점을 통과했다

### P-1(1)(2) 두 상자를 분리했다

`BotTest.builtBounds()`를 추가하고 판정 코어를 `declared ∩ built ∩ ±8`, 1칸 축소로 바꿨다.
**`arenaBounds`(스캔·복원)는 좁히지 않았다** — `bot_catch_fall`의 물이 dx −24에 있었고 거기까지
복원한 것이 그 하니스를 1/3 → 3/3으로 만든 값 근거다. 좁혔으면 그 결함이 되살아난다.
계약 변경이 아니라 계약 이행이라는 지시를 이렇게 읽었다: **판정 범위만 계약 원문대로 좁힌다.**

미선언 하니스 전수 목록 — **36개 클래스에 선언을 넣었다.** 그중 시공이 아예 없어 `NO_BUILD`를
선언한 11개(블록 판정 영역이 비고 엔티티·봇·유저 상태만 판정):
bot_alive, bot_death, bot_persist_save, bot_persist_load, bot_phase2_combo, bot_ranged,
bot_single, bot_coldstart_dist(+tail1/2/3).
나머지 25개는 시공 루프 범위를 그대로 선언했다(예: bot_creeper_wall {−6,8,−4,4},
bot_path_blocked {−2,14,−4,4}, bot_look {−1,1,−1,1}, bot_warden_probe {−6,70,−6,6}).

이미 판정 코어를 덮고 있어 선언이 불필요한 것들(bot_equip ±8, bot_rule4_*, bot_rule_dcell,
bot_kite_execmon/flip/recover, bot_warden_band_below/above, 그리고 x가 −8 이하에서 시작하고
z가 ±8 이상인 속도 계열 전부)은 기본값을 유지했다.

### P-1(3) 각 n=10 — canary MISMATCH 0

    하니스               수리 전                          수리 후 (n=10)              판정 코어
    bot_creeper_wall     FAIL 1/3, MISMATCH x2            PASS 10/10, canary OK       x−5..7 z−3..3 (455칸)
    bot_creeper_lowfuse  K FAIL / L PASS / O PASS (뒤집힘) PASS 10/10, canary OK       x−5..7 z−3..3 (455칸)
    bot_path_blocked     M-3 FAIL 1/3 / O PASS (뒤집힘)    PASS 10/10, canary OK       x−1..7 z−3..3 (315칸)

**30 트라이얼에서 MISMATCH 0.** 뒤집힘이 사라졌다. 원인이 하나였다는 것이 값으로 확인된다 —
가설이 틀렸다면 MISMATCH가 남았을 것이고, 남지 않았다.

### P-1(4) 판정 밖으로 나간 어긋남은 로깅으로 남는다

침묵 제외가 아니다. ring1이 매 트라이얼 찍는다:

    canary ring1 (in arena, restored, not judged): outerBlocks changed:764
      first(-24,-1,-8) oak_leaves[distance=3,persistent=false,waterlogged=false]
                    => oak_leaves[distance=2,persistent=false,waterlogged=false]

764칸이다. **이 현상은 사라지지 않았고 사라질 수도 없다** — 판정 상자 밖 월드젠 숲 전체가
매 트라이얼 distance를 다시 도출한다. 이전에 보이지 않았던 이유는 하나뿐이다: 판정 코어가
그 숲까지 손을 뻗고 있었기 때문이다.

## P-2 회피 판정 분모 전환 — 이월로 확정

`BotTest.aggregateSample()`을 추가해 매니저가 화살 표본에 **같은 임계 0.80과 같은 슬랙 0.15**를
적용한다. 판정 라인에 `judgedOn:` 필드가 붙어 어느 분모로 판정했는지가 값으로 남는다.

블록 P 실행(n=10 오버라이드): `trials:10,passed:5,judgedOn:aggregate:35/40,wilson95Lower:0.739`
→ **PASS**. 트라이얼 분모였다면 5/10으로 FAIL이었을 것이다.

**주의 — 이 실행의 표본은 40발이다.** n=10 오버라이드는 내가 수리 검증을 위해 건 것이고
화살 표본을 200발에서 40발로 줄였다. `bot_dodge`의 권위 있는 값은 여전히 n=50 실행 둘이다:
0.750 / 0.761, 합산 400발 하한 **0.774**. 판정은 두 경우 모두 PASS(≥0.65)로 같다.

§9(B) 부채 갱신: 「0.36 → 0.80 구현 슬롯」 **해제**.
→ 「T5.6 확정 게이트 이월 (스크리닝 통과, 화살 단위 Wilson 하한 0.774)」. 기능 결함 아님.

## P-3 window_variance 좀비 행 — 입력이지만 파급은 0이다

**(1) 좀비 행은 입력이다.** 설계서:796 「좀비(1.00) mean±3σ | 0.114±0.004 ✓ | ±0.003 ✓ |
±0.002 ✓ | ±0.002 ✓」가 :881 표의 좀비 sd에서 나온 칸이다(3σ ≈ 0.004 ↔ sd .0018).

**그런데 그 표가 답하는 물음은 「구간이 봇 스프린트 0.2806을 가로지르는가」다.** 새 값으로 다시 계산한다:

    W20  0.1110 ± 3(0.0145) = [0.0675, 0.1545]   상한이 0.2806의 55%   ✓
    W40  0.1109 ± 3(0.0115) = [0.0764, 0.1454]                        ✓
    W60  0.1108 ± 3(0.0098) = [0.0814, 0.1402]                        ✓
    W100 0.1106 ± 3(0.0080) = [0.0866, 0.1346]                        ✓

**네 칸 전부 ✓ 그대로다.** ✓가 ✗로 뒤집히려면 좀비 sd가 (0.2806−0.1110)/3 = **0.0565**여야 하고,
이는 새 값의 3.9배·옛 값의 31배다. 그리고 W60 채택을 실제로 정한 것은 워든 행이며(:797, :886-887)
워든 행은 재현됐다.

**따라서 이 무효화의 파급은 0이다.** P-3(2)의 R1 억제 팔은 **돌리지 않는다** — 지시의 전제
「좀비 행이 어떤 결정의 입력도 아니면 재측정은 T5.6으로 미룬다」에 실질적으로 해당한다.
정확히는 「입력이지만 그 입력이 바꾸는 결정이 없다」이므로, 그 구분을 적어 두고 T5.6으로 미룬다.
부채: 「좀비 창별 sd가 8~11배 커진 원인(REFLEX:196 유력) 미규명 — T5.6에서 R1 억제 팔로 확인」.

## P-4 warden_live_speed — 내 이전 진단을 정정한다

**정정 먼저.** 블록 O에서 나는 「봇이 교전 분기에 아예 들어가지 않았고, 봇이 도망치지 않으니
워든도 추격하지 않는다」라고 썼다. **틀렸다.** `BotWardenLiveSpeedTest.java`의 tick()은 매 틱
`bot.setDeltaMovement(Vec3.ZERO)`와 `bot.moveTo(origin…)`으로 **봇을 의도적으로 고정한다**
(주석: 「The bot stays put; only the warden moves, so the observation is purely its approach」).
`moveOwner:IDLE:360`은 결함 징후가 아니라 **하니스 설계 그대로**다. 봇은 애초에 교전할 예정이 없다.

**따라서 사용자가 준 후보 셋(R1 트리거 정정 → 9장 변경 → 타겟 인식 조건)은 값으로 배제된다.**
셋 다 봇 측 코드이고, 이 하니스에서 봇은 전투·반사 코드를 한 틱도 돌리지 않았다
(`moveOwner:IDLE:360`, `idleCommandedTicks:0`, REFLEX 0틱). 봇 쪽에 바뀔 것이 없었다.

**실패는 전적으로 워든 쪽이다.** 화가 나 있고 타겟이 잡힌 워든이 움직이지 않는다:
3 트라이얼 모두 `observedSpeed:0.0000`, `indepWholeSpan:0.0046 / 0.0008 / 0.0012`.
카나리 기준선은 `mobs=1, types:player=1,warden=1`로 워든이 실재함을 확인한다.

**같은 배치에서 워든이 움직인 하니스와의 차이는 정확히 하나다.** `bot_warden_pursuit`의 워든
셋업은 `spawn → setInvulnerable(true) → increaseAngerAt(bot) → setAttackTarget(bot)`,
40틱마다 anger·target 재설정 — `bot_warden_live_speed`와 **줄 단위로 같다**. 다른 것은 봇뿐이다:
pursuit의 봇은 정착 구간 뒤 실제로 도망친다(`botFled:69.07`, `moveOwner RANGED:340/IDLE:80`,
draw 30회 평균 0.2784). live_speed의 봇은 처음부터 끝까지 고정이다.

**후보(코드 근거는 있으나 값으로 확정하지 못함)**: 완전히 정지한 대상은 진동을 발생시키지 않고,
워든의 이동은 진동·소리 기반 경로 갱신에 의존한다. 설계서:942의 「5회는 첫 창이 워든의
등장(emerge/roar) 구간에 걸려 관측 ≈ 0이었다」도 같은 계열의 관측이다.

**확정하지 못하는 이유를 명시한다**: 07-25 실행에 대해 봇 고정 여부·moveOwner 기록이 **없다**.
설계서:860-862은 t1/t2/t3 속도 셋만 싣는다. 그러므로 「같은 전제에서 예전엔 됐다」를 뒷받침할
값이 없고, 「무엇이 바뀌었나」를 봇 코드에서 찾을 근거도 없다.
필요한 것은 단일 변수 팔 하나다 — **봇 고정을 N틱 풀고 워든이 움직이기 시작하는지.**
고치지 않았다. 부채 (A) 하니스로 회수 가능.

## P-5 execmon 대가 지표 — 실었다. 판정에는 넣지 않았다

`hpPerIntentTick = hpLostTotal / intentOpenTicks`, `failingRatio = failingTicksTotal / intentOpenTicks`.
통과 조건은 손대지 않았다(여전히 `startedKiteable && execMonitorFired && ticksToExec <= 20`).

블록 P 첫 기준값: `hpLostTotal:31.7, intentOpenTicks:214 → hpPerIntentTick:0.1480,
failingRatio:0.519, gapAtFlee:1.49`.
블록 O 값으로 같은 지표를 계산하면 `23.7 / 224 = 0.1058`. 절대 hp는 15.7 → 23.7 → 31.7로
계속 커졌지만 정규화 값은 0.106 → 0.148이다. **이제 B 술어 수정의 전후를 비교할 축이 생겼다.**
(블록 O의 intentOpenTicks 224는 기록이 있으나 07-25의 hp 15.7에는 구간 길이 기록이 없어
그 시점 값은 계산할 수 없다 — 기준값 축적은 지금부터다.)

## P-6 scenarioSpec — 전량 채웠다

    이전: 12 / 58 하니스 이름 (46 미보유)
    지금: 58 / 58, 미보유 0

42개 클래스에 채웠다. 내용은 코드에서 도출한 것만 싣는다 — `MAX_HEALTH` setBaseValue 호출값,
`bot.setInvulnerable(...)`, 스폰 몹 종류, 관측 틱·트라이얼 수, `builtBounds` 선언, 그리고
유저 유무와 그에 따른 16장 자율 이동 도달 여부(코드 인용 포함).

**생성 중 한 건을 스스로 잡았다**: `timeoutTicks()`/`repeats()`가 리터럴이 아니라 상수 계산식인
9개 클래스(kite 계열, rule4, rule_dcell, equip)에서 자동 생성기가 기본값 200/1을 적어 넣었다.
그것은 없는 전제를 지어내는 것이므로 「관측 창과 트라이얼 수는 리터럴이 아니라 상수 계산식이다」로
바꿨다. **게이트 7의 분모는 58이다.**

# 블록 Q — 진단 마감

GPG 재서명 금지 — 사용자 지시 + 트리 지문 충돌. 재요청은 이 항목 참조.

배치 RUN_ID `P20260726T110441Z-9778`, 15 실행. 게이트 4조건 통과
(`TREE-BEGIN == TREE-END = 2536ca9…:da39a3ee…:28e533ee…`). 전문 `docs/bottest_gate_blockQ.txt`.

## Q-0 DIRTY 발화 확인 — 게이트를 믿기 전에 게이트가 작동하는 것을 봤다

### Q-0(2) 세 성분 각각 — 하나만 걸려도 지문이 바뀐다

지문은 `HEAD : sha1(git status --porcelain) : sha1(git ls-files -s -- src/** build.gradle)`.
세 성분을 하나씩만 건드려 값으로 확인했다(각 시험 후 원상복구, BASE == RESTORED 확인).

    BASE      90c66ad1…:da39a3ee…:789c821a…
    성분1 HEAD  534bf2b1…:da39a3ee…:789c821a…   ← 빈 커밋 1개. 2·3 성분 동일
    성분2 워킹  90c66ad1…:a76d7ed0…:789c821a…   ← 추적 안 되는 임시 파일 1개. 1·3 성분 동일
    성분3 인덱스 90c66ad1…:e2ae30c2…:a4504ab4…  ← src 파일 스테이징. 2도 함께 바뀜(정상)
    RESTORED  90c66ad1…:da39a3ee…:789c821a…   ← BASE와 동일

성분 1과 2는 **단독으로** 분리 확인됐다. 성분 3은 스테이징이 워킹트리 상태도 바꾸므로 2와 함께
움직인다 — 이는 git의 성질이지 지문의 결함이 아니며, 어느 쪽이 걸려도 DIRTY가 뜬다.

### Q-0(1)(3) 배치 중 편집 → BATCH-DIRTY, 그리고 DIRTY는 PASS가 아니다

폐기용 1-실행 배치(`dummy`)를 돌리는 중 25초 시점에 저장소 루트에 임시 파일 하나를 만들었다.

    TREE-BEGIN 90c66ad1…:da39a3ee95…:789c821a…
    TREE-END   90c66ad1…:6e0844f95…:789c821a…      ← 성분 2만 변함
    BATCH-DIRTY the source tree changed during this batch; every verdict below is DIRTY
    GATE name=dummy verdict=DIRTY rawVerdict=PASS runId=P20260726T105554Z-3062 TREE-DIRTY(begin=… end=…)

**`rawVerdict=PASS`인데 `verdict=DIRTY`다.** 하니스는 실제로 통과했고 게이트가 그것을 PASS로
집계하기를 거부했다 — Q-0(3)이 요구한 값이다. 확인 후 임시 파일을 지웠고 porcelain은 다시 깨끗하다.

## Q-1 경고 링 분류 — 억제가 아니라 분류

### Q-1(1)(3) 판별식

`TrialCanary.isKnownNeighbourDrift(idA, idB)`: **블록 종류 동일 AND 바뀐 속성이 전부 이웃 파생
목록 안**일 때만 [KNOWN]이다. 목록(코드에 명시, 이것이 판별식의 전부):

    distance                          잎·비계 — 가장 가까운 원목/지지대까지의 거리
    north east south west up down     울타리·유리판·담장·덩굴·레드스톤 연결
    shape                             계단·레일 — 이웃에서 도출되는 모서리 형태
    waterlogged                       이웃에서 흘러든 유체

**목록에 없는 속성이 하나라도 바뀌면 [RING1]/[RING2]로 전량 출력된다.** 판별식이 좁을수록
안전하다 — 모르는 것은 전부 출력 쪽으로 떨어진다. [KNOWN] 부류도 개수와
`{minecraft:oak_leaves[distance]=764}` 형태의 블록·속성 집계는 남긴다.

첫 구현은 링1(judged/ring)에만 걸었고 링2(선언 밖)를 빠뜨렸다. 블록 Q 값이 그 구멍을 드러냈다 —
`bot_warden_probe`가 561칸을 **매 트라이얼 전량 출력**하고 있었다. 링2에도 같은 분류를 적용했다.

### Q-1(2) 증가하는가 — 아니다. 정상 상태다

이번 배치의 월드는 매 배치 새로 생성되므로 블록 P의 월드와 다르고, 선언 상자(±24) 안에는
잎이 없어 **링1은 10 트라이얼 전부 0**이었다. 같은 현상이 링2(±36)에서 나타났고, 거기서
트라이얼별 개수를 셌다:

    bot_creeper_wall  트라이얼 2..10:  30, 28, 28, 28, 28, 28, 28, 28, 28
    bot_path_blocked  트라이얼 2..10:  56, 56, 56, 56, 56, 56, 56, 56, 56
    bot_warden_probe  트라이얼 2..10:  561 × 9 (전부 동일)

**증가하지 않는다.** creeper_wall의 30 → 28 한 번은 첫 트라이얼 폭발 잔여가 가라앉은 것이고
이후 평평하다. 나머지 둘은 완전 상수다. 즉 `restore()`는 이 표류에 대해 **멱등**이며,
월드가 누적 열화하지 않는다. 멈추고 보고할 사유는 없다.

부수 확인: 링2의 첫 칸이 `minecraft:birch_leaves[distance=…]`, id 308→304 — **|Δ|=4가 다른
수종에서도 그대로다.** 정정 14의 산술이 참나무 한 종의 우연이 아님을 보여준다.

## Q-2 회피 확정 게이트 — 이월 목표를 값으로 고정

사용자 산술을 받는다. §9(B) 이월 항목 서술을 이렇게 바꾼다:

    (이전) T5.6 확정 게이트 이월 — 0.80 도달 필요
    (지금) T5.6 확정 게이트 이월 — p̂ 0.840 필요, 현재 0.815, 격차 +2.5pp (400발 기준 +10발)

설계서 R.2에 「정정 16 — 확정 게이트 임계의 실질 요구값」으로 등록했고, 일반 규약
「Wilson 하한 임계는 점추정 임계보다 높은 실성능을 요구한다. 이월 목표는 하한이 아니라
필요 점추정으로 기록한다」를 함께 적었다. 회피 로직은 건드리지 않았다.

## Q-3 warden_live_speed — 가설 확인. 그러나 회귀는 설명되지 않았다

시험 팔 `bot_warden_live_unpin`: `pinBot()` 훅(기본 true)만 두고 기존 하니스 동작은 그대로,
서브클래스에서 고정만 해제했다. 다른 변수는 하나도 건드리지 않았다.

    bot_warden_live_speed (고정)   t1/t2/t3  observedSpeed 0.0000 / 0.0000 / 0.0000  FAIL 0/3
    bot_warden_live_unpin (해제)   t1/t2/t3  observedSpeed 0.2562 / 0.2231 / 0.2658  PASS 3/3
                                             speedObserved:true, canKite:true, canary:OK

**워든이 움직인다. 가설이 확인됐다** — 완전히 정지한 대상에게는 워든이 접근하지 않는다.
그리고 t3의 **0.2658은 설계서:866이 「실동 하니스 최악값」으로 적은 값과 정확히 같다.**

**그러나 이것이 회귀를 설명하지는 않는다.** 고정 코드는 파일 최초 커밋 `a4f2bdf`부터
지금까지 **한 글자도 바뀌지 않았고**(`git show a4f2bdf:…`로 확인), §6의 0.2658/0.2658/0.2236은
바로 그 커밋의 실행이 생산한 값이다. 즉 **고정된 봇이 예전에는 추격당했고 지금은 아니다.**
고정 해제가 증상을 없앤다는 것과, 무엇이 바뀌어 고정 상태가 더는 통하지 않는지는 다른 물음이다.
후자는 열려 있다. 다음 팔은 고정된 봇의 **틱당 실제 변위**를 계측하는 것이다 — 예전에 미세하게
움직였다면 그것이 진동원이었을 수 있다. 부채 (A). 고치지 않았다.

### Q-3(2) 원장 서술 정정 — 앵커는 둘이고 하나는 재검증 불가였다

블록 O에서 나는 「§6의 워든 실동 속도 상수 자체는 무효가 아니다 — 다른 두 하니스가 독립 재현」이라
썼다. **절반만 맞았다.** 설계서:866은 마진을 **두 앵커**로 적는다:

    W60 평균 0.2640        → 마진 5.92%
    실동 하니스 최악값 0.2658 → 마진 5.27%

`bot_warden_pursuit`(이동 표적 0.2784)와 `bot_speed_probe`(단발 최고 0.2798)가 재현한 것은
**다른 양**이다. 0.2658은 `bot_warden_live_speed`가 생산하던 값이고, 그 하니스가 죽은 동안
**두 번째 앵커는 재검증 불가 상태였다.** 워든 마진이 나이프에지인 만큼 이것은 정확해야 한다.
지금은 `bot_warden_live_unpin` t3가 0.2658을 냈으므로 **두 번째 앵커에 값이 다시 생겼다** —
다만 전제가 다른 팔(고정 해제)에서 나온 값이라는 단서를 붙여 기록한다. 설계서는 고치지 않았다.

## Q-4 bot_persist_load 수리 — 앞절이 처음으로 값을 가졌다

러너에 `a+b` 2부팅 쌍 문법을 넣었다: 쌍 앞에서 world를 한 번만 지우고, `a` 실행 → 서버 재시작 →
`b`를 **같은 world**에서 실행한다. 쌍 단위 격리는 유지된다(다음 spec 앞에서 다시 지운다).

    PAIR-BEGIN bot_persist_save -> (reboot, same world) -> bot_persist_load
    bot_persist_save  PASS  exists:true,saved:true,botExists:true,uuid:c40b4e54-5cf9-33de-bc69-a435d5c9462d
    bot_persist_load  PASS  restored:true,uuidMatch:true,uuid:c40b4e54-5cf9-33de-bc69-a435d5c9462d
    bot_persist_none  PASS  botExists:false,botPresent:false

**설계서:1265 T1.2 검증 3(「리로드 후 봇 엔티티 존재 AND UUID 일치」)이 처음으로 값을 갖는다.**
O-3(3)에 적은 원장 정정 「T1.2 검증 3(복원)은 통과 기록 없이 완료로 적혀 있었다」는
이제 「없었고, 블록 Q에서 생겼다」로 닫힌다. 앞절이 PASS이므로 Q-4(3)의 세 갈래 재분할은 불필요하다.

뒷절은 별개 하니스 `bot_persist_none`으로 분리했다 — 하나가 두 절을 반대 방향으로 물으면
배치 조건에 따라 반드시 한쪽이 FAIL로 찍힌다(O-3(2)에서 관측한 그대로).
두 하니스의 scenarioSpec에 「2부팅 쌍의 앞쪽/뒤쪽/바깥」을 명시했다(Q-4(4)).

### Q-4(5) T6.3과의 연결

`bot_persist_load`의 복원 검증은 1차 보완 설계서 **T6.3(파괴 델타 P1, ownerUUID 마이그레이션)**의
선행조건이다. 마이그레이션은 「기존 저장 데이터를 새 스키마로 읽어 들이는가」를 묻는데, 복원 경로
자체가 검증돼 있지 않으면 마이그레이션 실패와 복원 실패를 구분할 통로가 없다.
지금 복원이 값으로 PASS이므로 T6.3의 검증 통로가 열렸다.

### 부수 발견 — 판정 라인의 한글이 로그에서 깨진다

`bot_persist_none`의 expected가 로그에 `(???:1260 ??)`로 찍혔다. 서버 JVM이
`-Dfile.encoding=US-ASCII`로 뜨기 때문이며, 이미 `[BOT] restore skipped ? no bot recorded`에서도
같은 일이 있었다. 판정에는 영향이 없으나 **expected/measured 문자열에는 한글을 쓰지 않는 것이
안전하다**. 부채 (A).

## Q-5 execmon 정규화 — 해소가 아니었다. n=3이 그것을 보여준다

원장 서술을 고친다: **정규화 값도 +40%였고, n=1씩이었으므로 판정 불가였다.**

n=3 실측(RUN_ID P20260726T110441Z-9778, 전 트라이얼 `execMonitorFired:false`):

    t1  hpLostTotal 11.7  intentOpenTicks 222  hpPerIntentTick 0.0526  failingRatio 0.491
    t2  hpLostTotal 26.0  intentOpenTicks 160  hpPerIntentTick 0.1625  failingRatio 0.513
    t3  hpLostTotal 30.0  intentOpenTicks 221  hpPerIntentTick 0.1357  failingRatio 0.484

    hpPerIntentTick  평균 0.1170  sd 0.0573  cv 49%   (최대/최소 = 3.1배)
    failingRatio     평균 0.496   sd 0.0151  cv 3.0%

**두 지표의 성질이 정반대다.** `failingRatio`는 cv 3%로 붙어 있어 기준선으로 쓸 수 있고,
`hpPerIntentTick`은 cv 49%로 단발 비교가 무의미하다. 그리고 결정적으로 —
블록 O의 0.1058과 블록 P의 0.1480은 **이 단일 배치의 산포 0.0526~0.1625 안에 둘 다 들어간다.**
「+40%」는 신호가 아니었다. 「정규화했으니 됐다」로 읽혔다면 그것이 틀렸다.

앞으로 execmon 계열은 n≥3으로 돌리고 두 지표의 산포를 함께 낸다. B 술어 수정 전후 비교의
기준선은 **`failingRatio` 0.496 ± 0.015**로 잡는다.

## Q-6 결정성 — (a)는 닫혔고, (b)는 지목한 전량이 결정적으로 나왔다

### Q-6(1) 지목과 근거

기준: 「판정량이 난수·타이밍·경로탐색 분기에 의존하는가」. 코드 신호로 좁혔다 —
`getRandom()/nextInt/RandomSource` 계열(난수), 몹 브레인 구동(`setTarget/increaseAngerAt`),
지연·틱수 판정량(`ticksTo*`), 경로탐색 분기(`planner()/isUnreachable/expansions`).
이 중 **판정량 자체가 연속 운동량이거나 지연인 것**만 후보로 삼았다 — 몹을 스폰하되 분류값
(장비 슬롯·규칙 문자열·타겟 목록)을 재는 하니스는 제외했다.

이미 n≥10으로 측정된 것(별도 실행 불필요): bot_dodge(50×2), bot_coldstart_dist·tail1/2/3(30),
bot_warden_pursuit(30), bot_kite_flip(10), bot_creeper_wall·lowfuse·path_blocked(10, 블록 P).

새로 지목해 n=10을 돌린 것 10종: bot_kite_band_latch, bot_kite_recover, bot_kite_execmon_lat,
bot_kite_approach, bot_warden_band, bot_warden_charge, bot_warden_probe, bot_rule1_fast,
bot_rule_dcell, bot_survival.

### Q-6(2) 결과 — 흔들리지 않았다

    bot_kite_band_latch   PASS 10/10      bot_warden_charge  PASS 10/10
    bot_kite_recover      PASS 10/10      bot_warden_probe   PASS 10/10
    bot_kite_execmon_lat  PASS 10/10      bot_rule1_fast     PASS 10/10
    bot_kite_approach     PASS 10/10      bot_survival       PASS 10/10
    bot_warden_band       PASS 10/10      bot_creeper_wall   PASS 10/10 (재확인)
    bot_rule_dcell        FAIL  0/10

**10종 전부 10/10 또는 0/10이다. 판정이 한 번도 흔들리지 않았다.**
`bot_rule_dcell`의 0/10은 비결정성이 아니라 **결정적 FAIL**이며, M-7(1)의 「회귀가 아니라 미구현」
판정과 일치한다 — 발행 경로가 없으면 흔들릴 것도 없다.

즉 **지목한 후보는 전부 [결정적]으로 판명됐다.** 값으로 확인된 [확률적] 하니스는 현재
`bot_dodge`(18/50 두 번)와 `bot_coldstart_*`(설계서:930 이봉 분포) 둘뿐이다.
나머지 전량은 [결정적] 잠정 분류로 두고, **T5.6 기준선 실행에서 어차피 전량이 돌므로 그때 확정한다.**
별도 n=10 배치를 52종으로 돌리지 않았다.

## Q-7 클래스 수 정산 — 산술 불일치가 세 번째로 실재를 드러냈다

세 집계가 서로 다른 단위였고, 그중 둘은 **집계기 자체가 고장나 있었다.**

    M-8 / sweep    [I] 46 + [II] 19 = 65   ← 클래스 단위. [I] 46은 sweep이 손으로 적은 명단
    P-6            58                      ← 하니스 이름 단위. **자동 집계, 그리고 틀렸다**
    실제 (레지스트리) 96 이름 / 51 최상위 클래스 / 15 중첩 하니스 클래스

차이 7의 정체: sweep의 [I] 46 명단 중 **BotAbilityPathTest, BotLivingEatTest, BotR1TriggerTest,
BotRule1ActionTest** 4개는 이름을 삼항연산·switch로 만든다. 나머지 3은 [II]가 「19 클래스(33 팔)」로
클래스와 팔을 섞어 센 데서 나온다. 즉 65는 클래스, 58은 이름, 96은 등록 이름이며 **어느 둘도
같은 단위가 아니었다.**

그런데 정산의 값은 단위 정리가 아니라 **구멍 둘**이었다:

    [구멍 1] P-6의 「58/58, 미보유 0」은 틀렸다.
      자동 생성기가 `return "bot_..."` 리터럴만 찾았으므로, 이름을 삼항연산으로 만드는
      BotProtectArmedTest / BotRule1ActionTest / BotRule2ActionTest **3클래스 6이름**을
      통째로 건너뛰었다: bot_protect_armed, bot_protect_unarmed, bot_rule1_kite,
      bot_rule1_nokite, bot_rule2_allow, bot_rule2_deny.
      → 채웠다. **지금 96/96, 미보유 0** (레지스트리 등록 이름 기준으로 재감사).

    [구멍 2] P-1의 builtBounds sweep도 같은 이유로 같은 클래스들을 건너뛰었다.
      그중 판정 코어가 시공 범위를 벗어나는 것이 둘이었다 —
      BotAbilityPathTest(시공 dx −6..22, dz −6..20 → dx=−7·dz=−7 노출),
      BotProtectInterveneTest(시공 dz ±4 → dz ±5..7 노출).
      → 선언을 넣었다. 나머지 12클래스는 시공이 ±7 코어를 덮고 있어 조치 불필요임을 확인했다
      (BotCatchMeetTest ±34, BotHealTest ±12, BotIdleTest ±22, BotLivingEatTest ±8,
      BotLivingSleepTest ±8, BotPickupTest −16..20/±16, BotProtectArmedTest ±16,
      BotProtectLowUserTest ±12, BotProtectRangedTest ±18, BotR1TriggerTest ±12,
      BotRule1ActionTest −36..16/±16, BotRule2ActionTest ±16).

**교훈**: 자동 sweep의 분모는 sweep 자신이 만든 것이므로 그것으로 자기를 검증할 수 없다.
이번엔 레지스트리(런타임이 실제로 아는 이름)를 제3의 기준으로 대서 잡았다.
앞으로 하니스 전수 집계는 **레지스트리 등록 이름**을 분모로 한다.

# 블록 R — 정리

배치 RUN_ID `P20260726T120240Z-5673`, 5 실행. 게이트 4조건 통과
(`TREE-BEGIN == TREE-END = 200369cf…:da39a3ee…:f2aab0cd…`). 전문 `docs/bottest_gate_blockR.txt`.

## R-0 유형 #14 등록 — 설계서 R.2 「정정 17」

계측기가 자기 관측 범위를 정하고 그 범위로 자기를 검증한다. 설계서에 계보(#4 → #8 → 정정 7 → #14),
관측 근거(3클래스 6이름), 「같은 생성기 골격을 쓴 builtBounds sweep이 같은 맹점을 공유했고
그중 둘이 수리 대상이었는데 수리에서 빠졌다」, 그리고 규약 셋을 적었다.

빠진 여섯이 무엇인지도 적었다 — `bot_protect_armed/unarmed`, `bot_rule1_kite/nokite`,
`bot_rule2_allow/deny`. **규칙1·규칙2 배선과 유저 보호 무장/비무장 분기다.** 무작위가 아니다.
이름을 동적으로 만드는 하니스는 「팔이 여러 개인 하니스」이고, 팔이 여러 개인 이유는 대조군이
필요할 만큼 중요하기 때문이다 — **맹점과 중요도가 상관된다.**

## R-1 새 선언 둘 재검증 — 그리고 [INVALIDATES] 하나

### R-1(1) 두 하니스 n=10

    bot_path_ability_water   PASS 10/10  canary OK  MISMATCH 0  judgedCore x-5..7 z-5..7 (845칸)
    bot_path_ability_detour  PASS 10/10  canary OK  MISMATCH 0  judgedCore x-5..7 z-5..7 (845칸)
    bot_protect_intervene    FAIL  9/10  canary OK  MISMATCH 0  judgedCore x-7..7 z-3..3 (525칸)

선언은 셋 다 의도대로 작동한다 — 판정 코어가 시공 범위 안으로 들어갔고 30 트라이얼에서
MISMATCH 0이다.

### R-1(2) aggroDrop 산포 — 좁아지지 않았다. **넓어졌다**

    선언 전 (n=3)   79.7  97.4  88.6                                   범위 17.7
    선언 후 (n=10)  44.3  70.8  97.4  97.4  97.4  44.3  44.3  0.0  97.4  97.4   범위 97.4

**이전 산포에 격리 잡음이 섞여 있었던 것이 아니다.** 판정 코어를 좁히고 MISMATCH 0을 확인한
상태에서 산포가 오히려 5배 넓어졌고, 8번 트라이얼은 `aggroDrop:0.0`으로 **어그로를 전혀
끌지 못했다**. n=3이 저쪽 꼬리를 한 번도 뽑지 못했을 뿐이다.

    [INVALIDATES] bot_protect_intervene  (K/L/O PASS 3/3 -> R FAIL 9/10, canary OK)

**K-2(c)의 「잔류값 기각」은 유지된다. 근거는 더 강해졌다.** 잔류값 가설이 옳다면 카나리가
표류를 잡았어야 하는데 10 트라이얼 MISMATCH 0이고, 그럼에도 산포가 크다 — 즉 산포의 출처는
격리가 아니라 **실제 행동 변동**이다. 다만 결론이 하나 추가된다:
**`bot_protect_intervene`은 [확률적] 하니스이며, 나는 Q-6(1)에서 그것을 지목하지 못했다.**
판정량이 「어그로 감소율」이라는 연속량인데도 전투 분류 하니스로 보고 후보에서 뺐다.
Q-6의 지목 기준이 좁았다는 뜻이며, 이 사실 자체가 유형 #14의 두 번째 사례다.

### R-1(3) 선언 전 실행의 카나리 상태 — 확인 가능하다

`docs/bottest_lines_blockK.txt`의 해당 라인이 `canary:OK`를 싣고 있다
(`trials:3,passed:3,successRate:1.00,wilson95Lower:0.438,canary:OK`).
즉 선언 전에도 그 3 트라이얼에서는 표류가 검출되지 않았다 — 이것이 R-1(2)의 결론과 일치한다.

### R-1(4) 나머지 12클래스 — 논증이 아니라 값

각 클래스의 선언 상자, 시공 범위, 실효 판정 코어를 계산해 대조했다. 12/12 전부 시공이 코어를 덮는다.

    BotCatchMeetTest       AB[-40,40,-40,40]  시공 x-34..34 z-34..34  코어 x-7..7 z-7..7  덮음 YES
    BotHealTest            AB[-16,16,-16,16]  시공 x-12..12 z-12..12  코어 x-7..7 z-7..7  덮음 YES
    BotIdleTest            AB[-26,26,-26,26]  시공 x-22..22 z-22..22  코어 x-7..7 z-7..7  덮음 YES
    BotLivingEatTest       AB[-12,12,-12,12]  시공 x-8..8   z-8..8    코어 x-7..7 z-7..7  덮음 YES
    BotLivingSleepTest     AB[-12,12,-12,12]  시공 x-8..8   z-8..8    코어 x-7..7 z-7..7  덮음 YES
    BotPickupTest          AB[-20,24,-20,20]  시공 x-16..20 z-16..16  코어 x-7..7 z-7..7  덮음 YES
    BotProtectArmedTest    AB[-20,20,-20,20]  시공 x-16..16 z-16..16  코어 x-7..7 z-7..7  덮음 YES
    BotProtectLowUserTest  AB[-16,16,-16,16]  시공 x-12..12 z-12..12  코어 x-7..7 z-7..7  덮음 YES
    BotProtectRangedTest   AB[-22,22,-22,22]  시공 x-18..18 z-18..18  코어 x-7..7 z-7..7  덮음 YES
    BotR1TriggerTest       AB[-16,16,-16,16]  시공 x-12..12 z-12..12  코어 x-7..7 z-7..7  덮음 YES
    BotRule1ActionTest     AB[-40,20,-20,20]  시공 x-36..16 z-16..16  코어 x-7..7 z-7..7  덮음 YES
    BotRule2ActionTest     AB[-20,20,-20,20]  시공 x-16..16 z-16..16  코어 x-7..7 z-7..7  덮음 YES

## R-2 고정 봇 변위 — 가설이 기각됐고, 내 Q-3 결론을 철회한다

### R-2(1) 틱내 변위는 양쪽 모두 0이다

`intraTickMax` / `intraTickMean`을 실었다. 하니스는 `ServerTickEvent(END)`에서 돌므로 봇의 틱은
이미 끝난 뒤이고, 그 잔여가 진동원 후보였다.

    고정 팔 (bot_warden_live_speed)  intraTickMax 0.00000  intraTickMean 0.00000
    해제 팔 (bot_warden_live_unpin)  intraTickMax 0.00000  intraTickMean 0.00000

**둘 다 0이다.** 해제 팔에서도 0인 이유는 명확하다 — 고정을 풀어도 이 하니스에는 유저도
교전 대상도 없으므로 봇은 스스로 움직일 이유가 없다(`moveOwner:IDLE`). 즉 **「고정 팔」과
「해제 팔」은 봇의 실제 운동에 관해 같은 세계였다.**

### R-2(2) 그리고 고정 팔이 이번엔 통과했다 — Q-3 결론 철회

    블록 O  고정   FAIL 0/3   observedSpeed 0.0000 / 0.0000 / 0.0000
    블록 Q  고정   FAIL 0/3   observedSpeed 0.0000 / 0.0000 / 0.0000
    블록 Q  해제   PASS 3/3   observedSpeed 0.2562 / 0.2231 / 0.2658
    블록 R  고정   PASS 3/3   observedSpeed 0.2597 / 0.2658 / 0.2658   ← 코드 변화 없음
    블록 R  해제   PASS 3/3   observedSpeed 0.2369 / 0.2600 / 0.2658

**Q-3에서 「가설이 확인됐다 — 완전히 정지한 대상에게는 워든이 접근하지 않는다」라고 쓴 것을
철회한다.** 고정을 그대로 둔 채 통과했고, 틱내 변위는 양쪽 다 0이었다. 고정은 변수가 아니었다.

**내 단일 변수 팔이 단일 변수가 아니었다.** 러너가 하니스마다 `rm -rf run/world`를 하므로
**실행마다 지형이 새로 생성된다.** 고정/해제 비교는 서로 다른 월드에서 이뤄졌고, 나는 그것을
통제 변인으로 세지 않았다. 사용자가 Q-5에서 인정한 것과 같은 형태다 — n=1 두 점을 신호로 읽었다.

호출 순서(R-2(2))는 확인했다: `BotTestHarness.java:37-38`이 `ServerTickEvent(END)`에서
매니저를 돌리고, 봇 틱은 `AICompanionBot.java:301-308`에서 레벨 틱에 실린다. 즉 **봇 틱 → 하니스
고정** 순서이며, 이 순서는 T5.4 이전·이후가 같다(고정 코드는 파일 최초 커밋 `a4f2bdf`부터 불변).

### R-2(4) 설계서:866 두 앵커 — 원장 서술 확정

    W60 평균 0.2640        → 마진 5.92%   (bot_window_variance 워든 행이 생산, 재현됨)
    실동 하니스 최악값 0.2658 → 마진 5.27%   (bot_warden_live_speed가 생산)

블록 R에서 **고정 팔이 0.2658을 두 번 냈다** — 원래 전제 그대로다. 따라서 Q-3(2)에서 적은
「전제 불일치 상태」는 해소된다. 대신 남는 것은 다른 문제다: **같은 코드·같은 전제가 월드에 따라
0.0000과 0.2658 사이에서 갈린다.** 마진 5.27%가 나이프에지인 만큼, 이 앵커의 T5.6 입력 자격은
**월드 시드를 고정한 뒤에** 다시 판정해야 한다.

## R-3 링 카운트 정산 — 9의 정체

n=10인데 값이 9개인 것은 정합적이다. `BotTestManager.java:256`이
`if (baseline == null) { baseline = now; } else { …diff… }` 구조이므로 **트라이얼 1은 기준선을
만들기만 하고 비교하지 않는다.** 비교는 트라이얼 2..10에서 9번 일어난다.
즉 n 트라이얼 → n−1 비교이며, 10 → 9다. 이전 보고에 이 문장이 없었다.

## R-4 로그 인코딩 — 파일 appender가 이미 옳았다

### R-4(1) 전수

`BotTestRegistry` 등록 이름 96개 기준, `scenarioSpec`에 비ASCII가 섞인 것은
**63클래스 / 90이름**이다. ASCII만인 것은 2클래스 6이름(`BotProtectRangedTest`,
`BotR1TriggerTest`)뿐이다. 즉 90/96이 콘솔에서 깨지고 있었다.

### R-4(2) 대응 — JVM 인코딩이 아니라 로그 경로였다

먼저 `runServer`에 `-Dfile.encoding/-Dsun.stdout.encoding/-Dsun.stderr.encoding=UTF-8`을 넣었다.
**그래도 stdout은 `?`로 나왔다.** 그런데 같은 실행의 두 로그를 나란히 읽으니 정체가 드러났다:

    gradle stdout 캡처   SCENARIO … | ?????? 200???, ???????????? 1???. ?????? ??????(Te
    run/logs/latest.log  SCENARIO … | 관측 200틱, 트라이얼 1회. 유저 없음(Te

**JVM 안의 데이터는 처음부터 온전했다.** 깨진 것은 콘솔 appender의 인코더뿐이고, Log4j 파일
appender는 이미 UTF-8로 쓰고 있었다. 그래서 러너가 매 실행 뒤 `run/logs/latest.log`를
`p_<name>.utf8.log`로 복사하고 **게이트가 그 파일을 읽도록** 바꿨다(없으면 stdout으로 폴백).

검증(RUN_ID `P20260726T121455Z-11329`): `GATE name=dummy verdict=PASS`이고 UTF-8 로그의
SCENARIO가 `관측 200틱, 트라이얼 1회. 유저 없음(TestUser.spawn 미호출) → Pe…`로 온전하다.
**값으로 보인 것은 이 실행 하나**이며, 나머지 95개에 대해서는 「같은 appender·같은 경로」라는
기전 동일성과 「96개 문자열이 전부 UTF-8 표현 가능한 한글·화살표」라는 정적 확인까지다.
전수 실행으로 확인한 것은 아니다.

### R-4(3) T6.1 연결

1차 보완 **T6.1의 「기준선 항목에 scenarioSpec 동봉」**은 이 경로를 써야 한다.
정정 9의 원칙이 「판정 라인만 읽고 이 값이 무슨 세계에서 나왔는지 재구성할 수 있어야 한다」인데,
`?????`로 동봉된 전제는 재구성 불가다. 동봉 대상은 stdout 캡처가 아니라 파일 appender 산출이다.

## R-5 성분 3 (한 줄)

DIRTY 지문의 성분 3(src 인덱스)은 항상 성분 2(워킹트리)와 함께 발화하므로 독립 탐지력이
미증명이다. 결함은 아니다 — **우리가 가진 것은 3중이 아니라 2중 탐지기다.**

## R-6 T5.6 입력 목록

`docs/t56_inputs.md`로 분리했다. 게이트 최종 상태, 구현 슬롯 4항목(I-1~I-4),
기준선·측정 입력 9항목(B-1~B-9), [BROKEN] 5종, 부채 4항목.

내가 추가한 것 넷: **월드 시드 미고정(B-6)** — R-2가 드러낸 통제 변인 누락으로,
T5.6 기준선 실행 전에 정해야 한다. **판정 라인 인코딩(B-7)**, **T1.2 복원 통로(B-8)**,
**DIRTY 2중 탐지(B-9)**. 그리고 [BROKEN]에 `bot_protect_intervene`을 추가했다(R-1(2)).
`bot_warden_live_speed`는 [BROKEN]에 남기되 **「사상자」 서술을 철회**했다 — 지금 PASS이며
문제는 하니스가 아니라 시드다.
