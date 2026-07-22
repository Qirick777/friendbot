package com.aicompanion.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Tactical movement executor (T2.1 / design 4.2). Sets the bot's input fields each tick;
 * the actual movement is done by vanilla {@code travel()} inside {@code aiStep()}
 * (gravity, collision, step-up, friction). Never sets coordinates directly.
 *
 * <pre>
 *   body yaw = move direction
 *   forward input (zza) / strafe input (xxa)
 *   jump input when a 1-block step is ahead
 *   crouch input (setShiftKeyDown)
 * </pre>
 */
public class BotMovementController {

    private static final float FORWARD = 1.0F;
    private static final double ARRIVE_H = 0.6D; // horizontal arrival epsilon

    @Nullable
    private Vec3 target;    // horizontal target (y ignored)
    private boolean crouch;

    /** Set a horizontal move target (design "좌표 (x,z)를 향해 직진"). */
    public void moveTo(double x, double z) {
        this.target = new Vec3(x, 0.0D, z);
    }

    public void stop() {
        this.target = null;
    }

    public void setCrouch(boolean crouch) {
        this.crouch = crouch;
    }

    public boolean hasTarget() {
        return target != null;
    }

    /** Called every tick from {@link AICompanionBot#tick()} before {@code super.tick()}. */
    public void tick(AICompanionBot bot) {
        // Crouch input is applied regardless of movement.
        bot.setShiftKeyDown(crouch);

        if (target == null) {
            bot.zza = 0.0F;
            bot.xxa = 0.0F;
            bot.setJumping(false);
            return;
        }

        double dx = target.x - bot.getX();
        double dz = target.z - bot.getZ();
        double distH = Math.sqrt(dx * dx + dz * dz);

        if (distH <= ARRIVE_H) {
            // Arrived — stop and clear.
            target = null;
            bot.zza = 0.0F;
            bot.xxa = 0.0F;
            bot.setJumping(false);
            return;
        }

        // M1: body yaw = move direction (matches Entity.lookAt convention).
        // getYRot() drives travel() direction; yBodyRot is the visible torso.
        // Head yaw (yHeadRot) is owned by BotLookController (T2.2) — not set here.
        float yaw = Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F);
        bot.setYRot(yaw);
        bot.setYBodyRot(yaw);

        // M2: forward input; strafe 0 for straight-line travel.
        bot.zza = FORWARD;
        bot.xxa = 0.0F;

        // M3: jump when a 1-block step is directly ahead.
        bot.setJumping(isOneBlockStepAhead(bot, dx, dz, distH));
    }

    /**
     * True when the block directly ahead at foot level blocks motion, but the two blocks
     * above it are clear — i.e. a single-block step the bot must jump onto.
     */
    private boolean isOneBlockStepAhead(AICompanionBot bot, double dx, double dz, double distH) {
        double nx = dx / distH;
        double nz = dz / distH;
        Level level = bot.level();

        BlockPos foot = BlockPos.containing(bot.getX() + nx, bot.getY(), bot.getZ() + nz);
        boolean footBlocked = level.getBlockState(foot).blocksMotion();
        boolean aboveClear = !level.getBlockState(foot.above()).blocksMotion()
                && !level.getBlockState(foot.above(2)).blocksMotion();
        return footBlocked && aboveClear;
    }
}
