package com.aicompanion.bot.combat;

import com.aicompanion.bot.AICompanionBot;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import com.aicompanion.bot.perception.TargetInfo;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;

/**
 * Critical-hit melee timing (T3.3 / design 6.4 engine B). Direct-vector tactical movement
 * (no A*): sprint in, then at reach with a full attack charge drop sprint, hop, and strike on
 * the descending frame (vanilla crit = falling + airborne + not-sprinting + full charge → 1.5×).
 * Between hits it circle-strafes while the attack cooldown recharges — never spamming.
 */
public class BotMeleeCombat {

    private static final double MELEE_REACH = 3.0;
    private static final float CRIT_CHARGE = 0.95F;   // cooldown charge gate (~1.0)
    private static final float STRAFE = 0.6F;          // sideways input between hits
    private static final int MIN_ATTACK_INTERVAL = 13; // > target hurt-invulnerability (10t); no spam

    @Nullable
    private LivingEntity target;
    private int strafeDir = 1;    // circle-strafe direction
    private int lastAttackTick = -1000;

    public void setTarget(@Nullable LivingEntity target) {
        this.target = target;
    }

    @Nullable
    public LivingEntity target() {
        return target;
    }

    public boolean hasTarget() {
        return target != null && target.isAlive();
    }

    public void stop() {
        rule2Override = false;
        this.target = null;
    }

    // --- A-4: rule 2 (승패 게이트) as a live gate on THIS controller -------------------------
    /**
     * 배선 위치. M-6이 값으로 확인한 결함은 「규칙2 판정은 옳은데 근접 컨트롤러가 그것을 읽지
     * 않는다」였다 (bot_rule2_deny: allowMelee:false, moveOwner MELEE:200, ticksInMeleeRange:182,
     * damageDealt:88.6). 규칙2가 9장 보호 계층에만 걸려 있어 직접 교전 경로가 우회했다.
     * 이제 접근·타격 **전에** 여기서 확인한다.
     *
     * <p><b>스펙 충돌과 그 해소를 여기 적는다.</b> {@code BotProtection}은 규칙2가 거부해도
     * 원거리 수단이 없으면 「그래도 교전한다」를 **의도적으로** 선택하고 그 근거를 코드 주석에
     * 값과 함께 적어 두었다 — 「gating intervention on rule 2 produced aggroDrop 97.4 -> 0.0 …
     * i.e. the bot stood still while the user was hit」. 규칙2를 컨트롤러에서 무조건 막으면 그
     * 결정이 무력화되고 목표 1(유저 생존)이 깨진다. 그래서 게이트는 **보호 계층이 명시적으로
     * 무효화할 수 있는** 형태로 넣는다. 기존 배선은 제거하지 않았다.</p>
     */
    private boolean rule2Override;
    private int rule2DeniedTicks;
    private int rule2AllowedTicks;
    private int rule2UnknownTicks;
    private String lastRule2 = "n/a";

    /** 보호 계층 전용: 「져도 교전한다」 결정을 이 컨트롤러에 전달한다. */
    public void setRule2Override(boolean on) {
        this.rule2Override = on;
    }

    public int rule2DeniedTicks() {
        return rule2DeniedTicks;
    }

    public int rule2AllowedTicks() {
        return rule2AllowedTicks;
    }

    public int rule2UnknownTicks() {
        return rule2UnknownTicks;
    }

    public String lastRule2() {
        return lastRule2;
    }

    public void resetRule2Counters() {
        rule2DeniedTicks = 0;
        rule2AllowedTicks = 0;
        rule2UnknownTicks = 0;
        lastRule2 = "n/a";
    }

    /**
     * @return true when this tick must NOT approach or attack. Fail-OPEN when the target is not in
     *     perception: a missing {@link TargetInfo} is a perception gap, not a rule-2 verdict, and
     *     denying on it would silently disable melee whenever perception lags. The unknown case is
     *     counted separately so it can never hide inside "allowed".
     */
    private boolean rule2Denies(AICompanionBot bot, LivingEntity t) {
        if (rule2Override) {
            lastRule2 = "override(protection)";
            rule2AllowedTicks++;
            return false;
        }
        for (TargetInfo ti : bot.perception().targets) {
            if (ti.entity == t) {
                boolean allow = CombatRules.allowMelee(ti, CombatStats.of(bot),
                        CombatRules.DEFAULT_SAFETY);
                lastRule2 = allow ? "allow" : "deny";
                if (allow) {
                    rule2AllowedTicks++;
                } else {
                    rule2DeniedTicks++;
                }
                return !allow;
            }
        }
        lastRule2 = "unknown(not perceived)";
        rule2UnknownTicks++;
        return false;
    }

    /** Called from {@link AICompanionBot#tick()} (before the physics tick) when a target is set. */
    public void tick(AICompanionBot bot) {
        if (!hasTarget()) {
            idle(bot);
            return;
        }
        LivingEntity t = target;

        // A-4(1): the gate runs BEFORE approach and BEFORE attack.
        if (rule2Denies(bot, t)) {
            // A-4(4): what the bot does after a refusal is NOT defined by 6.3 — 설계서:940 records
            // exactly this end state (「봇은 카이팅도 근접도 하지 않은 채 소닉 사거리에 서 있게
            // 된다」). No fallback is implemented here on purpose; the state is recorded so it can
            // be the input to the D-cell emit path and the 「원거리 강제」 fallback.
            bot.look().lookAt(t);
            bot.zza = 0.0F;
            bot.xxa = 0.0F;
            bot.setSprinting(false);
            return;
        }

        // T2.2: keep the head/aim on the target.
        bot.look().lookAt(t);

        // Face the target (body yaw drives travel direction).
        double dx = t.getX() - bot.getX();
        double dz = t.getZ() - bot.getZ();
        float yaw = Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F);
        bot.setYRot(yaw);
        bot.setYBodyRot(yaw);

        double dist = Math.sqrt(dx * dx + dz * dz);
        float charge = bot.getAttackStrengthScale(0.5F);

        if (dist > MELEE_REACH) {
            // (1) Sprint approach — direct vector, no pathing.
            bot.zza = 1.0F;
            bot.xxa = 0.0F;
            bot.setSprinting(true);
            bot.setJumping(false);
            return;
        }

        // In reach. Crit requires NOT sprinting.
        bot.setSprinting(false);
        bot.zza = 0.0F;

        if (bot.onGround()) {
            if (charge >= CRIT_CHARGE) {
                // (2) Cooldown full → hop (aiStep jumps when noJumpDelay clears).
                bot.setJumping(true);
                bot.xxa = 0.0F;
            } else {
                // (4) Between hits: circle-strafe while the cooldown recharges.
                bot.setJumping(false);
                bot.xxa = STRAFE * strafeDir;
                if (bot.tickCount % 30 == 0) {
                    strafeDir = -strafeDir;
                }
            }
        } else {
            // Airborne. Strike on the descending frame → critical hit.
            bot.setJumping(false);
            bot.xxa = 0.0F;
            boolean descending = bot.getDeltaMovement().y < -0.05;
            boolean cooledDown = (bot.tickCount - lastAttackTick) >= MIN_ATTACK_INTERVAL;
            if (descending && charge >= CRIT_CHARGE && cooledDown) {
                // The bot has no network connection, so vanilla's client-driven fallDistance
                // never accumulates server-side. Since we KNOW it is genuinely descending,
                // set a minimal fallDistance so the vanilla crit condition (fallDistance>0)
                // reflects the real physical state.
                if (bot.fallDistance <= 0.0F) {
                    bot.fallDistance = 0.1F;
                }
                // NOTE: ServerPlayer.swing() resets the attack-strength ticker, so it MUST come
                // AFTER attack() — calling it before would fire the hit at ~0 charge (no crit,
                // ~1 damage). attack() itself resets the ticker, enforcing the cooldown.
                bot.attack(t);                          // 1.5× critical hit at full charge
                bot.swing(InteractionHand.MAIN_HAND);   // swing animation (also resets cooldown)
                lastAttackTick = bot.tickCount;
            }
        }
    }

    private void idle(AICompanionBot bot) {
        bot.zza = 0.0F;
        bot.xxa = 0.0F;
        bot.setSprinting(false);
        bot.setJumping(false);
    }
}
