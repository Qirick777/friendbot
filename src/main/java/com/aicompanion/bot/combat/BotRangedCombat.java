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
    private static final double BAND_MIN = 6.0;        // kite: back away if the target closes inside
    private static final double BAND_MAX = 20.0;       // kite: close in if the target drifts beyond
    private static final double MAX_TARGET_VEL = 1.0;  // sanity clamp: ignore teleport/bounce deltas

    @Nullable
    private LivingEntity target;
    @Nullable
    private Vec3 prevTargetPos;
    private Vec3 targetVel = Vec3.ZERO;
    private int shotsFired;

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
        bot.setSprinting(false);
        bot.setJumping(false);
        bot.xxa = 0.0F;
        if (steadying) {
            bot.zza = 0.0F;                 // steady aim at release
        } else if (dist < BAND_MIN) {
            bot.zza = -1.0F;                // too close → back away (body faces target: -1 = retreat)
        } else if (dist > BAND_MAX) {
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
