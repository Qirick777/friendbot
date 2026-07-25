package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * T2.3 reach check: a wall blocks the straight line to the goal, with a gap at one end.
 * PASS iff the bot A*-routes around the wall and ends within ±1 of the goal block.
 */
public class BotPathReachTest implements BotTest {

    private BlockPos goal;

    @Override
    public int repeats() {
        return 3;
    }

    @Override
    public double successThreshold() {
        return 1.00;
    }

    @Override
    public String name() {
        return "bot_path_reach";
    }

    @Override
    public int timeoutTicks() {
        return 400;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        buildArena(ctx.level, o);

        bot.setDeltaMovement(Vec3.ZERO);
        bot.fallDistance = 0.0F;
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);

        goal = new BlockPos(o.getX() + 12, o.getY(), o.getZ());
        bot.planner().setGoal(goal);
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot != null) {
            double dx = (goal.getX() + 0.5) - bot.getX();
            double dz = (goal.getZ() + 0.5) - bot.getZ();
            if (Math.sqrt(dx * dx + dz * dz) <= 1.0 && Math.abs(bot.getY() - goal.getY()) <= 1.0) {
                return true; // arrived early
            }
        }
        return ctx.elapsedTicks >= 380;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            return BotTestResult.fail("bot:null", "reach goal within +/-1");
        }
        BlockPos b = bot.blockPosition();
        double dx = (goal.getX() + 0.5) - bot.getX();
        double dz = (goal.getZ() + 0.5) - bot.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        int dy = Math.abs(b.getY() - goal.getY());

        boolean reached = horiz <= 1.5 && dy <= 1;
        String measured = String.format("goal:(%d,%d,%d),final:(%d,%d,%d),horiz:%.2f,dy:%d",
                goal.getX(), goal.getY(), goal.getZ(), b.getX(), b.getY(), b.getZ(), horiz, dy);
        return reached
                ? BotTestResult.pass(measured, "final within +/-1 of goal")
                : BotTestResult.fail(measured, "final within +/-1 of goal");
    }

    /**
     * Flat stone floor (o.y-1) across x[-2..14], z[-8..8] with 3 clear blocks above, and a
     * 3-high wall at x=o.x+6 spanning z[-8..3] — leaving a gap at z[4..8] so a detour exists.
     */
    static void buildArena(ServerLevel level, BlockPos o) {
        for (int dx = -2; dx <= 14; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 2; dy++) {
                    level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
        // Wall across the straight line, with a gap at the +z end.
        for (int dz = -8; dz <= 3; dz++) {
            for (int dy = 0; dy <= 2; dy++) {
                level.setBlockAndUpdate(new BlockPos(o.getX() + 6, o.getY() + dy, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
            }
        }
    }
}
