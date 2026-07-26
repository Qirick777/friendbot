# 봇 자율 이동 전수 sweep (유형 #12)

명제: 「이전 PASS는 봇이 움직이지 않던 시절의 것이라 드러나지 않았다」를 `bot_pickup_natural`
한 건의 사유가 아니라 **전 하니스에 적용되는 명제**로 취급한다.

설계서 근거: `AI_Bot_Design.md:1644` 「정정 8 · 계측 정합 — 결함 유형 #12」.

## 0. 16장 평상시 이동이 도는 조건 (코드)

`AICompanionBot.java` 틱 사슬은 상호 배타적 분기다. 16장 이동은 **마지막 else**에서만 돈다.

```
AICompanionBot.java:230  if (evading)                     -> REFLEX
AICompanionBot.java:233  else if (creeperActing)          -> CREEPER
AICompanionBot.java:236  else if (rescuing)               -> RESCUE
AICompanionBot.java:239  else if (survivalActive)         -> SURVIVAL
AICompanionBot.java:242  else if (meleeCombat.hasTarget()) -> MELEE
AICompanionBot.java:247  else if (rangedCombat.hasTarget())-> RANGED
AICompanionBot.java:255  else { pickup.tick / idle.tick }  -> PICKUP / IDLE
```

그 안에서 다시:

```
BotIdle.java:74-75    ServerPlayer user = bot.perception().user;
                      if (user == null) { return; }        <- 유저 없으면 16장 이동 없음
BotPickup.java:128-132 accept(): fromUser는 user != null을 요구하고,
                      자연 드롭 분기도 user == null이면 null 반환   <- 유저 없으면 17장 이동도 없음
```

**따라서 유저가 없는 하니스에서는 16장·17장 어느 쪽도 목표를 쓰지 않는다.** `planner`/`mover`는
하니스가 스스로 세운 목표만 실행한다 — T5.4 이전과 동일하다.

## 1. 계측 (주장 → 측정값)

분류를 논증으로 남기지 않기 위해 **이동 소유자 히스토그램**을 계측했다.

- `AICompanionBot.java:56` `enum MoveOwner { REFLEX, CREEPER, RESCUE, SURVIVAL, MELEE, RANGED, PICKUP, IDLE }`
- `AICompanionBot.java:88` `noteMoveOwner()` — 각 분기에서 1회 호출. 틱당 정확히 하나.
- `BotTestManager.java:157` `withMoveOwner()` — **모든** 판정 라인에
  `moveOwner:...,idleOwnedTicks:n,pickupOwnedTicks:n`을 덧붙인다.
- `BotTestManager.java:204` 카운터는 `setup()` 직후 리셋 — 관측 창만 센다.

이제 「이 하니스에서 16장 이동이 돌았는가」는 판정 라인에서 바로 읽힌다.

## 2. 3분류

### [I] 16장 이동이 아예 돌지 않음 — 이전 PASS 유효 (46 클래스)

**코드 근거는 공통이다**: `TestUser.spawn`을 호출하지 않으므로 `bot.perception().user == null`이고,
`BotIdle.java:74-75`에서 **즉시 반환**한다. `BotPickup`도 `accept()`가 항상 null을 반환한다
(`BotPickup.java:132` `fromUser = user != null && thrower == user`, `:150` `if (user == null) return null`).
「아마 무관」이 아니라 **미도달**이다.

BotAbilityPathTest, BotAliveTest, BotColdStartDistTest, BotDeathTest, BotDodgeTest, BotEquipTest,
BotFallNoWaterTest, BotFallWaterTest, BotKiteApproachTest, BotKiteBandLatchTest,
BotKiteExecLatencyTest, BotKiteExecMonitorTest, BotKiteFlipTest, BotKiteRecoverTest,
BotLivingEatTest, BotLookTest, BotMeleeTest, BotMoveFlatTest, BotMoveStairTest, BotPathBlockedTest,
BotPathReachTest, BotPearlTest, BotPerceptionTest, BotPersistLoadTest, BotPersistSaveTest,
BotPhase2ComboTest, BotR1TriggerTest, BotRangedTest, BotRule1ActionTest, BotRule1FastTest,
BotRule4ActionTest, BotShieldTest, BotSingleTest, BotSpeedProbeTest, BotSurvivalTest,
BotTacticsTest, BotTotemTest, BotWardenBandReturnTest, BotWardenBandTest, BotWardenChargeTest,
BotWardenLiveSpeedTest, BotWardenProbeTest, BotWardenPursuitTest, BotWardenTacticsTest,
BotWindowVarianceTest, DummyTest

**주의 — 이 [I]은 「16장 이동이 없다」만 말한다.** 유형 #12는 배제되지만, 이 46종이 유저 없는
세계에서만 검증됐다는 사실 자체는 별개의 부채다(L-9② 참조).

### [II] 16장 이동이 돌 수 있고 판정량이 위치·이동·근접에 의존 — 재실행 대상

`TestUser.spawn`을 호출하는 19 클래스(33 팔). 이들은 유저가 있으므로 `BotIdle.java:75`를 통과할
수 있고, 전투 대상이 없는 틱에는 마지막 else로 떨어진다. **전부 재실행했다.** 값은 §3.

### [III] 이미 깨진 것 (4)

`bot_rule2_deny`, `bot_rule_dcell`, `bot_pickup_natural`, `bot_pickup_gift`.
앞의 셋은 블록 K에서 값으로 확인됐다. `bot_pickup_gift`는 현재 PASS이지만 같은 기전 위에 서 있다
— 획득이 fetch 결정이 아니라 **접촉**으로 일어나므로, 배회가 우연히 아이템을 지나가도 PASS가 난다.
즉 **PASS의 근거가 검증 대상과 다르다**. 재판정 대상으로 [III]에 넣는다.

## 3. [II] 재실행 결과 — 33팔, 계측 포함

`idleOwnedTicks`는 16장 평상시 이동이 봇을 몬 틱 수다. 이제 분류는 값이다.

| 하니스 | 결과 | moveOwner | idleOwnedTicks |
|---|---|---|---|
| bot_catch_fall | PASS | RESCUE:21/IDLE:21 | 21 |
| bot_catch_none | PASS | IDLE:120 | 120 |
| bot_catch_meet | PASS | RESCUE:38/IDLE:162 | 162 |
| bot_catch_water | PASS | RESCUE:1/IDLE:199 | 199 |
| bot_creeper_wall | **FAIL** | CREEPER:1 (t1) | 0 |
| bot_creeper_lowfuse | PASS 3/3 canary OK | CREEPER:5/IDLE:25 | 25 |
| bot_escape_ride | PASS | RESCUE:44/IDLE:96 | 96 |
| bot_escape_none | PASS | MELEE:100 | 0 |
| bot_escape_farthreat | PASS | IDLE:120 | 120 |
| bot_survival_heal | PASS | SURVIVAL:64/MELEE:136 | 0 |
| bot_survival_nopotion | PASS | MELEE:200 | 0 |
| bot_rescue_heal | PASS | RESCUE:46/MELEE:153/IDLE:1 | 1 |
| bot_rescue_nopotion | PASS | RESCUE:47/IDLE:153 | 153 |
| bot_idle_follow | PASS | IDLE:260 | 260 |
| bot_idle_follow_hungry | PASS | IDLE:260 | 260 |
| bot_idle_wander | PASS | IDLE:260 | 260 |
| bot_live_sleep | PASS | IDLE:140 | 140 |
| bot_live_sleep_nobed | PASS | IDLE:140 | 140 |
| bot_pickup_gift | PASS | PICKUP:47/IDLE:153 | 153 |
| bot_pickup_combat | PASS | MELEE:200 | 0 |
| bot_pickup_natural | **FAIL** | IDLE:200 | **200** |
| bot_protect_armed | PASS | RANGED:200 | 0 |
| bot_protect_unarmed | PASS | MELEE:200 | 0 |
| bot_protect_intervene | PASS 3/3 | MELEE:218/IDLE:22 | 22 |
| bot_protect_intervene_none | PASS 3/3 | IDLE:240 | 240 |
| bot_protect_lowuser | PASS | MELEE:80 | 0 |
| bot_protect_highuser | PASS | MELEE:80 | 0 |
| bot_protect_priority | PASS 3/3 | MELEE:93 | 0 |
| bot_protect_ranged_guard | PASS | RANGED:100 | 0 |
| bot_protect_ranged_melee | PASS | RANGED:100 | 0 |
| bot_rule2_deny | **FAIL** | MELEE:200 | **0** |
| bot_rule2_allow | PASS | MELEE:66 | 0 |
| bot_rule_dcell | **FAIL** | MELEE:173/IDLE:67 | 67 |

## 4. 계측이 내 가설을 반증했다 (L-4 선행 결론)

블록 K에서 나는 이렇게 썼다: 「9장이 평상시 이동 목표를 놓으면서 16장 배회/추종이 그 자리를
차지했고 그 이동이 봇을 대상 쪽으로 밀어 넣은 것이 가장 유력한 경로다.」

**틀렸다. 값이 그렇게 말한다.**

    bot_rule2_deny  FAIL  moveOwner:MELEE:200  idleOwnedTicks:0
                          allowMelee:false, botApproachSum:15.42, damageDealt:88.6

**16장 이동은 그 하니스에서 한 틱도 돌지 않았다(0/200).** 200틱 전부 MELEE 분기가 이동을
소유했다. 접근과 타격은 `BotMeleeCombat.tick`이 직접 만든 것이고, 이동 소유권 변경과는 무관하다.
즉 `bot_rule2_deny`는 **유형 #12가 아니라 유형 #10**이다 — 규칙2가 `allowMelee:false`로 옳게
판정했는데 근접 컨트롤러가 그 판정을 읽지 않는다.

`bot_rule_dcell`은 혼합이다: MELEE:173 / IDLE:67. 주된 이동 소유자는 여전히 근접 컨트롤러이며,
`lateralCommandTicks:1`은 D 칸 분기가 횡이동을 **거의 발행하지 않는다**는 뜻이다(196틱 D 칸 체류
대비 1틱). L-4(2)의 물음 「발행되는가」에 대한 답은 **사실상 아니오**이고, 따라서 원인은
이동 소유권 밖이다.

`bot_pickup_natural`만이 순수한 유형 #12다: `idleOwnedTicks:200`, 배회가 봇을 아이템 위로 몰았다.

**교훈**: 블록 K의 「가장 유력한 경로」는 그럴듯했지만 값이 아니었다. 계측을 먼저 넣었어야 했다.

## 5. 이번 sweep에서 새로 나온 것

    [INVALIDATES] bot_creeper_wall  (블록 K 05:xx PASS -> 지금 FAIL 1/3, canary MISMATCH x2)

`coreBlocks changed:4 / 13, id 276->280`. 트라이얼 간 블록 상태가 복원되지 않는다.
`bot_creeper_lowfuse`는 반대로 블록 K의 FAIL에서 **PASS 3/3 canary OK**로 돌아왔다.
두 하니스가 서로 반대로 뒤집힌 것은 **비결정성**을 뜻한다 — 어느 쪽 결과도 단독으로는 증거가 아니다.
크리퍼 폭발이 남기는 블록 상태가 트라이얼마다 다르며, K-7의 `doTileDrops=false`가 후보다.
L-5의 단일 변수 확인(doTileDrops를 되돌린 팔)은 아직 실행하지 않았다.

## 6. M-3 — [I] 46종을 논증이 아니라 값으로 재확인

RUN_ID `R20260726T072157Z-4478`, BATCH-BEGIN 2026-07-26 07:21:57Z, BATCH-END 08:33Z, 52 이름.
게이트 3조건(이름 일치 AND 기대 N == 수신 N AND RUN_ID 일치) 통과: STALE 0, MISSING 0, 52/52 신선.

### 6.1 [I] 판정의 근거가 논증에서 값으로 바뀌었다

`idleCommandedTicks`(BotIdle.java:62)는 16장이 **실제로 목표를 놓거나 정지를 지시한** 틱만 센다.
`idleOwnedTicks`는 「마지막 else 분기가 돌았다」만 뜻한다. 둘은 다른 값이다.

계측이 실린 49개 판정 라인 **전부** `idleCommandedTicks:0`.
그중 40개는 `idleOwnedTicks > 0`이다 (최대 574, `bot_warden_probe`).

즉 §2의 논증 「`user == null`이면 `BotIdle.java:87-89`에서 즉시 반환한다」는 값으로 확인됐다.
동시에, **`idleOwnedTicks`만 봤다면 49개 중 40개가 오탐으로 [II]에 재분류될 뻔했다.**
M-3이 실제로 회수한 것은 [I] 46종의 유효성이 아니라 **[I]을 재는 자의 눈금**이다.

`idleOwnedTicks == 0`인 9종 (전투 분기가 창 전체를 소유): bot_live_eat_combat, bot_melee,
bot_pearl, bot_ranged, bot_shield, bot_survival, bot_warden_band, bot_warden_band_above,
bot_warden_band_below.

### 6.2 계측 불변식 (M-4)

`moveOwnerAnomalyTicks:0` — 49개 라인 전부. `bot_dodge`의 50 트라이얼 각각도 전부 0.
틱당 소유자는 정확히 하나였다. 계측 자체는 자기 모순을 내지 않았다.

### 6.3 계측이 실리지 않은 3건 — sweep §1의 「**모든** 판정 라인」은 과장이었다

dummy, bot_death, bot_persist_load 의 판정 라인에는 moveOwner 필드가 없다.
`BotTestManager.java:169` `if (bot == null) { return r; }` 때문이다.
셋 다 판정 시점에 봇이 존재하지 않는다 (dummy는 봇을 안 만들고, bot_death는 `botExists:false`가
판정 대상이며, bot_persist_load는 `restored:false`로 실패했다). 코드상 필연이며 누락이 아니다.
정확한 수치는 49/52다. sweep §1의 문장을 정정한다 — **삭제하지 않고 여기에 덧붙인다.**

### 6.4 FAIL 4건

    [INVALIDATES] bot_path_blocked
      직전 기록: docs/bottest_lines_blockK.txt:39  03:51:45  PASS trials:3,passed:3,canary:OK
      지금:      FAIL trials:3,passed:1,successRate:0.33,wilson95Lower:0.061,canary:MISMATCH x2
                 t1:P(pathNull:true,expansions:8000)
                 t2:F(canary:MISMATCH(coreBlocks changed:3 first(+5,+3,-7) id 412->416))
                 t3:F(canary:MISMATCH(coreBlocks changed:5 first(+5,+2,-7) id 412->408))
      t1 자체는 이전과 같은 값(pathNull:true, expansions:8000)을 냈다. 무너진 것은 **판정이 아니라
      격리**다. 서명은 `bot_creeper_wall`(§5, id 276->280)과 동일하다: coreBlocks가 트라이얼 간
      복원되지 않고 블록 id가 단조 증가한다. 두 하니스는 같은 결함 하나를 보고 있다.

FAIL 3건은 **이전 PASS 기록이 없다.** docs/bottest_lines_blockK.txt(85 라인/48 하니스),
docs/batch_run_log.md, AI_Bot_Design.md 어디에도 판정값이 없다. 회귀라고 부를 근거가 없으므로
무효화가 아니라 **최초 기록**으로 남긴다.

    bot_persist_load  FAIL  restored:false,uuidMatch:false,uuid:none
      expected=restored AND uuid==c40b4e54-5cf9-33de-bc69-a435d5c9462d
      짝인 bot_persist_save는 PASS. 저장은 되고 복원이 안 된다. T0.3(설계서:1259 「영속 저장」)의
      절반이 값으로 미검증 상태였다는 뜻이다.

    bot_dodge  FAIL  trials:50,passed:18,successRate:0.36,wilson95Lower:0.241  canary:OK
      expected=95% Wilson lower >= 0.65 (스크리닝 티어; 스펙 0.80)
      arrows:4는 50트라이얼 전부 동일 — 자극은 결정적으로 들어갔다. hits가 0/1/2/3으로 갈린다.
      lateral은 2.17~17.21로 흩어진다. canary OK이므로 §5의 격리 결함과는 다른 원인이다.
      T4.2(설계서:1379 「투사체 회피」, :1499 「회피: 공격 후 봇 체력 불변」)의 대표 하니스다.

    bot_kite_execmon  FAIL  execMonitorFired:false,ticksToExec:-1,gapAtFlee:0.91,gapClosed:0.57,
      hpLostTotal:23.7,intentOpenTicks:224,maxFailingTicks:3,failingTicksTotal:116
      expected=A의 게이트를 우회시킨 상태에서 B가 20틱 안에 잡아야 한다
      설계서:958은 이 하니스를 「대상이 이미 밀착 … 간격이 실제로 좁혀짐: **아니오** … hp 15.7 손실」로
      적고 있다. 하니스의 expected와 설계서의 서술이 서로 다른 것을 말한다 —
      **스펙 충돌 후보다. 여기서 멈추고 기록만 한다(진행 규율 예외 (a)).**
      직전 기록 hp 15.7 대비 지금 23.7. 둘 다 봇 최대체력 200 전제(batch_run_log.md:301)다.
