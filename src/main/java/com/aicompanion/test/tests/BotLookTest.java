package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotLookController;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * T2.2 look check: spawn a marker entity to the side, order the bot to look at it, and
 * sample head yaw each tick. PASS iff (a) no tick moved the head yaw by more than the
 * angular cap (smooth, not a snap) AND (b) the head yaw converged to the target angle.
 */
public class BotLookTest implements BotTest {

    private static final float STEP_CAP = BotLookController.MAX_YAW_STEP_DEG;
    private static final float CONVERGE_TOL = 5.0F;

    private float targetYaw;
    private float startYaw;
    private float prevYaw;
    private float maxStep;
    private boolean firstSample = true;

    @Override
    public String name() {
        return "bot_look";
    }

    @Override
    public int timeoutTicks() {
        return 200;
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return new int[]{-1, 1, -1, 1};
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;

        // Small platform so the bot stands still (gravity from doTick).
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY(), o.getZ() + dz),
                        Blocks.AIR.defaultBlockState());
            }
        }
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);
        // Known initial head orientation.
        bot.setYHeadRot(0.0F);
        bot.setYRot(0.0F);
        bot.setYBodyRot(0.0F);
        bot.setXRot(0.0F);

        // Marker entity to the side (a large yaw away from 0) and slightly up.
        double mx = o.getX() + 6.5;
        double my = o.getY() + 2.0;
        double mz = o.getZ() - 6.5;
        ArmorStand marker = ctx.env.spawn(EntityType.ARMOR_STAND, new BlockPos((int) mx, (int) my, (int) mz));
        Vec3 markerPoint = (marker != null)
                ? new Vec3(marker.getX(), marker.getEyeY(), marker.getZ())
                : new Vec3(mx, my, mz);

        bot.look().lookAt(markerPoint.x, markerPoint.y, markerPoint.z);
        targetYaw = BotLookController.yawTo(bot.getX(), bot.getZ(), markerPoint.x, markerPoint.z);

        startYaw = bot.yHeadRot;
        prevYaw = startYaw;
        maxStep = 0.0F;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot != null) {
            float cur = bot.yHeadRot;
            if (!firstSample) {
                float step = Math.abs(Mth.degreesDifference(prevYaw, cur));
                if (step > maxStep) {
                    maxStep = step;
                }
            }
            firstSample = false;
            prevYaw = cur;
        }
        return ctx.elapsedTicks >= 60;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            return BotTestResult.fail("bot:null", "head yaw converges smoothly");
        }
        float finalYaw = bot.yHeadRot;
        float err = Math.abs(Mth.degreesDifference(finalYaw, targetYaw));

        boolean smooth = maxStep <= STEP_CAP + 0.5F;   // no snap: each tick ≤ cap
        boolean converged = err <= CONVERGE_TOL;        // reached the target angle

        String measured = String.format("maxStep:%.2f(cap%.0f),yaw:%.1f→%.1f,target:%.1f,err:%.2f",
                maxStep, STEP_CAP, startYaw, finalYaw, targetYaw, err);
        String expected = "maxPerTickStep<=cap AND |final-target|<=" + CONVERGE_TOL;
        return (smooth && converged)
                ? BotTestResult.pass(measured, expected)
                : BotTestResult.fail(measured, expected);
    }
}
