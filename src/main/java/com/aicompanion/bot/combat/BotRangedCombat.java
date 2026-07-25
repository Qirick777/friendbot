package com.aicompanion.bot.combat;

import com.aicompanion.bot.AICompanionBot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Predictive projectile ranged combat (T3.4 / design 6.4). Estimates the target velocity from
 * per-tick position deltas, leads the shot with {@link ProjectileAiming} (drag-aware ballistic
 * intercept), and drives the vanilla bow draw/hold/release cycle.
 *
 * <p><b>R3 charge trap:</b> a bow charges on the item-use ticker (not the attack-strength ticker),
 * and {@code startUsingItem} restarts the charge only when NOT already using. So the controller
 * calls {@code startUsingItem} exactly once per shot and then lets {@code getTicksUsingItem()} grow
 * over subsequent ticks; calling it every tick would keep the power pinned at 0.</p>
 *
 * <p>Aim is set (yaw/pitch) and the shot released inside {@link #tick} which runs BEFORE the bot's
 * physics tick, so {@code releaseUsingItem} → {@code shootFromRotation} reads exactly the rotation
 * written here. Launch origin matches {@code AbstractArrow} ctor: {@code (x, eyeY - 0.1, z)}.</p>
 */
public class BotRangedCombat {

    private static final int DRAW_TICKS = 20;          // full charge (BowItem.MAX_DRAW_DURATION)
    // Fallback band, used only when the target is not in perception this tick. Normally the band
    // comes from rule 3 (T4.6): [bandMin, bandMax] computed from the target's measured reach and its
    // layer-2 ranged range, so e.g. a warden is kited at the documented 16~20 instead of a constant.
    private static final double BAND_MIN_FALLBACK = 6.0;
    private static final double BAND_MAX_FALLBACK = 20.0;
    private static final double MAX_TARGET_VEL = 1.0;  // sanity clamp: ignore teleport/bounce deltas
    /** Signal B — kiting counts as failing when the gap shrinks for this many consecutive ticks. */
    private static final int KITE_FAIL_TICKS = 10;
    /** …at least this fast (blocks/tick) — slower drift is normal band correction, not a failure. */
    private static final double KITE_FAIL_TREND = -0.02;

    @Nullable
    private LivingEntity target;
    @Nullable
    private Vec3 prevTargetPos;
    private Vec3 targetVel = Vec3.ZERO;
    private int shotsFired;
    private int closingTicks;      // consecutive ticks the target has been closing the gap
    private boolean kiteFailing;   // signal B verdict: kiting is not working right now

    public void setTarget(@Nullable LivingEntity target) {
        if (target != this.target) {
            this.prevTargetPos = null;
            this.targetVel = Vec3.ZERO;
            this.shotsFired = 0;
        }
        this.target = target;
    }

    @Nullable
    public LivingEntity target() {
        return target;
    }

    public boolean hasTarget() {
        return target != null && target.isAlive();
    }

    public int shotsFired() {
        return shotsFired;
    }

    public void stop() {
        this.target = null;
        this.prevTargetPos = null;
        this.targetVel = Vec3.ZERO;
        this.closingTicks = 0;
        this.kiteFailing = false;
    }

    /**
     * Signal B (execution monitor). Rule 1 answers "can this target be kited in principle" from a
     * slow, stable capability estimate; this answers "is the kiting actually working right now" from
     * the gap trend, in a few ticks. The slow signal is allowed to be slow because this one is fast.
     */
    public boolean kiteFailing() {
        return kiteFailing;
    }

    /** Called from {@link AICompanionBot#tick()} (before the physics tick) when a ranged target is set. */
    public void tick(AICompanionBot bot) {
        if (!hasTarget()) {
            idle(bot);
            return;
        }
        LivingEntity t = target;

        // (1) Estimate target velocity from the last tick's position delta (exact for constant-speed
        // linear motion). Clamp implausible jumps (a direction reversal / teleport) to the last good
        // estimate so a single outlier tick doesn't corrupt the lead.
        Vec3 cur = t.position();
        if (prevTargetPos != null) {
            Vec3 d = cur.subtract(prevTargetPos);
            if (d.length() <= MAX_TARGET_VEL) {
                targetVel = d;
            }
        }
        prevTargetPos = cur;

        // (2) Lead-solve the aim. Aim at the target's vertical center; launch from the arrow spawn point.
        Vec3 aimPoint = new Vec3(t.getX(), t.getY() + t.getBbHeight() * 0.5, t.getZ());
        Vec3 origin = new Vec3(bot.getX(), bot.getEyeY() - 0.1, bot.getZ());
        ProjectileAiming.Aim aim = ProjectileAiming.solveLead(origin, aimPoint, targetVel);

        // (3) Write the aim rotation NOW (before doTick), so a release this tick fires along it.
        bot.setYRot(aim.yaw);
        bot.setYBodyRot(aim.yaw);
        bot.setYHeadRot(aim.yaw);
        bot.setXRot(aim.pitch);

        // (4) Kiting: hold the band; freeze movement near full draw so the shot leaves at ~0 velocity
        // (the arrow inherits the shooter's horizontal velocity, which would otherwise skew the aim).
        double dx = t.getX() - bot.getX();
        double dz = t.getZ() - bot.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        int charge = bot.getTicksUsingItem();
        boolean steadying = charge >= DRAW_TICKS - 3;

        // Rule 3 (T4.6): consume the tactical band for THIS target instead of a fixed constant.
        double bandMin = BAND_MIN_FALLBACK;
        double bandMax = BAND_MAX_FALLBACK;
        for (com.aicompanion.bot.perception.TargetInfo ti : bot.perception().targets) {
            if (ti.entity == t) {
                bandMin = CombatRules.bandMin(ti);
                bandMax = CombatRules.bandMax(ti);
                break;
            }
        }

        // Signal B: watch whether the distance is actually being held/opened.
        double trend = bot.perception().speeds.gapTrend(t);
        if (trend < KITE_FAIL_TREND) {
            closingTicks++;
        } else {
            closingTicks = 0;
        }
        kiteFailing = closingTicks >= KITE_FAIL_TICKS;

        bot.setSprinting(false);
        bot.setJumping(false);
        bot.xxa = 0.0F;
        if (steadying && dist >= bandMin) {
            bot.zza = 0.0F;                 // steady aim at release (only when already safe)
        } else if (dist < bandMin) {
            bot.zza = -1.0F;                // too close → back away (body faces target: -1 = retreat)
        } else if (dist > bandMax) {
            bot.zza = 1.0F;                 // too far → close in
        } else {
            bot.zza = 0.0F;                 // in band → hold
        }

        // (5) Draw/hold/release cycle.
        if (!bot.isUsingItem()) {
            bot.startUsingItem(InteractionHand.MAIN_HAND);  // begin drawing (ONCE — see R3 note)
        } else if (charge >= DRAW_TICKS && aim.reachable) {
            bot.releaseUsingItem();                          // full-charge shot along the rotation above
            shotsFired++;
        }
        // else: keep holding the draw (charge accumulates in doTick's item-use tick).
    }

    private void idle(AICompanionBot bot) {
        if (bot.isUsingItem()) {
            bot.stopUsingItem();
        }
        bot.zza = 0.0F;
        bot.xxa = 0.0F;
        bot.setSprinting(false);
        bot.setJumping(false);
        prevTargetPos = null;
        targetVel = Vec3.ZERO;
    }
}
