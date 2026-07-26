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
