package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotLookController;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 2 integration/regression check: A* pathing (T2.3), the movement executor (T2.1) and
 * the look controller (T2.2) all active at once. The bot A*-routes around a wall to a goal
 * WHILE holding a look target off to the side.
 *
 * PASS iff (a) it reaches the goal (A*+mover still work together) AND (b) at arrival the head
 * yaw points at the look target (±tol) AND is clearly different from the travel yaw (getYRot)
 * — proving head/body stayed independent (no regression from the T2.2 decoupling).
 */
public class BotPhase2ComboTest implements BotTest {

    private BlockPos goal;
    private Vec3 lookPoint;
    private float expectedHeadYaw;

    @Override
    public String name() {
        return "bot_phase2_combo";
    }

    @Override
    public int timeoutTicks() {
        return 400;
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return NO_BUILD;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        // Reuse the reach arena (wall with a +z gap).
        BotPathReachTest.buildArena(ctx.level, o);

        bot.setDeltaMovement(Vec3.ZERO);
        bot.fallDistance = 0.0F;
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);

        goal = new BlockPos(o.getX() + 12, o.getY(), o.getZ());
        bot.planner().setGoal(goal);

        // Fixed look target far to the -z side (a large, stable yaw).
        lookPoint = new Vec3(o.getX() + 6.5, o.getY() + 2.0, o.getZ() - 30.0);
        bot.look().lookAt(lookPoint.x, lookPoint.y, lookPoint.z);
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot != null) {
            double dx = (goal.getX() + 0.5) - bot.getX();
            double dz = (goal.getZ() + 0.5) - bot.getZ();
            if (Math.sqrt(dx * dx + dz * dz) <= 1.0 && Math.abs(bot.getY() - goal.getY()) <= 1.0) {
                return true;
            }
        }
        return ctx.elapsedTicks >= 380;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            return BotTestResult.fail("bot:null", "reach goal while looking aside");
        }
        double dx = (goal.getX() + 0.5) - bot.getX();
        double dz = (goal.getZ() + 0.5) - bot.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        boolean reached = horiz <= 1.5 && Math.abs(bot.getY() - goal.getY()) <= 1;

        // Head should point at the look target; body/travel yaw is getYRot().
        expectedHeadYaw = BotLookController.yawTo(bot.getX(), bot.getZ(), lookPoint.x, lookPoint.z);
        float headErr = Math.abs(Mth.degreesDifference(bot.yHeadRot, expectedHeadYaw));
        float headVsBody = Math.abs(Mth.degreesDifference(bot.yHeadRot, bot.getYRot()));

        boolean lookingAtTarget = headErr <= 12.0F;   // within one step of the target
        boolean independent = headVsBody >= 20.0F;     // head clearly decoupled from travel yaw

        boolean ok = reached && lookingAtTarget && independent;
        String measured = String.format(
                "reached:%b(horiz%.2f),headYaw:%.1f,target:%.1f,headErr:%.1f,bodyYaw:%.1f,head-body:%.1f",
                reached, horiz, bot.yHeadRot, expectedHeadYaw, headErr, bot.getYRot(), headVsBody);
        String expected = "reached AND headErr<=12 AND |head-body|>=20 (independent)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
