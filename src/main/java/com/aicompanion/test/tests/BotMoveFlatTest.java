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
 * T2.1 flat-move check: on a clean stone runway, order the bot straight toward a target
 * 10 blocks away. PASS iff the horizontal distance to the target decreased (moved toward it
 * via travel() physics — measured before→after, not a teleport).
 */
public class BotMoveFlatTest implements BotTest {

    private double targetX;
    private double targetZ;
    private double distStart;

    @Override
    public String name() {
        return "bot_move_flat";
    }

    @Override
    public int timeoutTicks() {
        return 200;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        buildRunway(ctx.level, o);

        // Reposition to the runway start (environment setup — not movement execution).
        bot.setDeltaMovement(Vec3.ZERO);
        bot.fallDistance = 0.0F;
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);

        targetX = o.getX() + 10.5;
        targetZ = o.getZ() + 0.5;
        bot.mover().moveTo(targetX, targetZ);
        distStart = horizontalDist(bot, targetX, targetZ);
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        return ctx.elapsedTicks >= 80;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            return BotTestResult.fail("bot:null", "bot moves toward target");
        }
        double distEnd = horizontalDist(bot, targetX, targetZ);
        boolean moved = distEnd <= distStart - 3.0;
        String measured = String.format("distToTarget:%.2f→%.2f", distStart, distEnd);
        return moved
                ? BotTestResult.pass(measured, "distToTarget decreased by >=3")
                : BotTestResult.fail(measured, "distToTarget decreased by >=3");
    }

    private static double horizontalDist(AICompanionBot bot, double x, double z) {
        double dx = x - bot.getX();
        double dz = z - bot.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Stone floor at o.y-1 with 3 blocks of clear air above, from x-2..x+13, z-1..z+1. */
    static void buildRunway(ServerLevel level, BlockPos o) {
        for (int dx = -2; dx <= 13; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 2; dy++) {
                    level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
    }
}
