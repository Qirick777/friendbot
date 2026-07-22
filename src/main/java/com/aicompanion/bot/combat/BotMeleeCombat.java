package com.aicompanion.bot.combat;

import com.aicompanion.bot.AICompanionBot;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
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
        this.target = null;
    }

    /** Called from {@link AICompanionBot#tick()} (before the physics tick) when a target is set. */
    public void tick(AICompanionBot bot) {
        if (!hasTarget()) {
            idle(bot);
            return;
        }
        LivingEntity t = target;

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
