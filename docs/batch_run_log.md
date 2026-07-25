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
