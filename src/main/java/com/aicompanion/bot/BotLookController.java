package com.aicompanion.bot;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Self-implemented look control (T2.2 / design 5). Body and head move independently:
 * the body yaw ({@code yBodyRot}) follows the movement direction (set by the movement
 * executor), while the head yaw/pitch ({@code yHeadRot} / {@code xRot}) smoothly track
 * a look target here. No snapping — each tick the head approaches the target by at most
 * {@link #MAX_YAW_STEP_DEG} / {@link #MAX_PITCH_STEP_DEG} degrees (angular-rate cap).
 */
public class BotLookController {

    /** Per-tick angular caps (design "틱당 최대 N도"; tunable). */
    public static final float MAX_YAW_STEP_DEG = 12.0F;
    public static final float MAX_PITCH_STEP_DEG = 8.0F;

    @Nullable
    private Vec3 lookTarget; // world point to look at; null → follow movement direction

    // Internal head state. Player.tick() resets yHeadRot = getYRot() every tick, so we
    // cannot progress by reading the entity field — we keep our own head yaw/pitch and
    // overwrite the entity after the physics tick.
    private float headYaw;
    private float pitch;
    private boolean initialized;

    public void lookAt(double x, double y, double z) {
        this.lookTarget = new Vec3(x, y, z);
    }

    public void lookAt(Entity entity) {
        this.lookTarget = new Vec3(entity.getX(), entity.getEyeY(), entity.getZ());
    }

    public void clear() {
        this.lookTarget = null;
    }

    public boolean hasTarget() {
        return lookTarget != null;
    }

    /** Called every tick from {@link AICompanionBot#tick()} AFTER the physics tick. */
    public void tick(AICompanionBot bot) {
        if (!initialized) {
            headYaw = bot.yHeadRot;
            pitch = bot.getXRot();
            initialized = true;
        }

        float targetYaw;
        float targetPitch;
        if (lookTarget != null) {
            double eyeX = bot.getX();
            double eyeY = bot.getEyeY();
            double eyeZ = bot.getZ();
            targetYaw = yawTo(eyeX, eyeZ, lookTarget.x, lookTarget.z);
            targetPitch = pitchTo(eyeX, eyeY, eyeZ, lookTarget.x, lookTarget.y, lookTarget.z);
        } else {
            // 평상시: head follows the movement/look yaw, level pitch.
            targetYaw = bot.getYRot();
            targetPitch = 0.0F;
        }

        // Rate-limited approach (≤ N° per tick), shortest angular path, from OUR state.
        headYaw = Mth.approachDegrees(headYaw, targetYaw, MAX_YAW_STEP_DEG);
        pitch = Mth.approachDegrees(pitch, targetPitch, MAX_PITCH_STEP_DEG);
        bot.setYHeadRot(headYaw);
        bot.setXRot(pitch);
    }

    /** Yaw (degrees) from an eye position toward a target point (Entity.lookAt convention). */
    public static float yawTo(double eyeX, double eyeZ, double targetX, double targetZ) {
        double dx = targetX - eyeX;
        double dz = targetZ - eyeZ;
        return Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F);
    }

    /** Pitch (degrees, +down) from an eye position toward a target point. */
    public static float pitchTo(double eyeX, double eyeY, double eyeZ,
                                double targetX, double targetY, double targetZ) {
        double dx = targetX - eyeX;
        double dy = targetY - eyeY;
        double dz = targetZ - eyeZ;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        return Mth.wrapDegrees((float) (-(Mth.atan2(dy, horiz) * (180.0D / Math.PI))));
    }
}
