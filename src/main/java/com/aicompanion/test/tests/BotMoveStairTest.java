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
 * T2.1 stair-move check: a 1-block step sits ahead of the bot. Ordered straight forward,
 * the bot must jump onto it. PASS iff the bot's y increased (climbed via jump input +
 * travel() physics — measured before→after).
 */
public class BotMoveStairTest implements BotTest {

    private double yStart;

    @Override
    public String name() {
        return "bot_move_stair";
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
        buildStair(ctx.level, o);

        bot.setDeltaMovement(Vec3.ZERO);
        bot.fallDistance = 0.0F;
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);

        yStart = bot.getY();
        bot.mover().moveTo(o.getX() + 8.5, o.getZ() + 0.5);
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        return ctx.elapsedTicks >= 100;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            return BotTestResult.fail("bot:null", "bot climbs the step");
        }
        double yEnd = bot.getY();
        boolean climbed = yEnd >= yStart + 0.9;
        String measured = String.format("y:%.2f→%.2f", yStart, yEnd);
        return climbed
                ? BotTestResult.pass(measured, "y increased by >=0.9 (climbed 1-block step)")
                : BotTestResult.fail(measured, "y increased by >=0.9 (climbed 1-block step)");
    }

    /**
     * Low floor (o.y-1) for x in [-2..1]; raised floor (o.y) for x in [2..13], creating a
     * single 1-block step at x=+2. Clear air above both sections.
     */
    static void buildStair(ServerLevel level, BlockPos o) {
        for (int dx = -2; dx <= 13; dx++) {
            int floorY = (dx <= 1) ? o.getY() - 1 : o.getY();
            for (int dz = -1; dz <= 1; dz++) {
                // solid support up to floorY
                level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(o.getX() + dx, floorY, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                // clear 3 blocks above the floor
                for (int dy = 1; dy <= 3; dy++) {
                    level.setBlockAndUpdate(new BlockPos(o.getX() + dx, floorY + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
    }
}
