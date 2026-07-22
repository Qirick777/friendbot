package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.BotPathfinder;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * T2.3 blocked check: the goal cell is fully enclosed (walls on all sides + roof + floor).
 * PASS iff the pathfinder returns null (unreachable) rather than a bogus/partial path.
 */
public class BotPathBlockedTest implements BotTest {

    private boolean pathNull;
    private int expansions;

    @Override
    public String name() {
        return "bot_path_blocked";
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
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);

        BlockPos start = o;
        BlockPos goal = new BlockPos(o.getX() + 10, o.getY(), o.getZ());

        BotPathfinder.Search s = new BotPathfinder.Search(ctx.level, start, goal, 8000);
        while (!s.isDone()) {
            s.step(8000);
        }
        List<BlockPos> path = s.result();
        pathNull = (path == null);
        expansions = s.expansions();
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        return ctx.elapsedTicks >= 5;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        String measured = "pathNull:" + pathNull + ",expansions:" + expansions;
        return pathNull
                ? BotTestResult.pass(measured, "enclosed goal → path == null")
                : BotTestResult.fail(measured, "enclosed goal → path == null");
    }

    /**
     * Open stone floor with the bot's start clear, but the goal column at x+10 fully sealed:
     * a 1-block air pocket surrounded by stone on all 6 faces (no way in).
     */
    static void buildArena(ServerLevel level, BlockPos o) {
        // Open floor + clearance for the start side.
        for (int dx = -2; dx <= 14; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 2; dy++) {
                    level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
        // Seal the goal cell (x+10) in solid stone on all sides: fill a 3x3x3 cube around it
        // with stone, leaving a single air pocket at the exact goal, unreachable.
        int gx = o.getX() + 10, gy = o.getY(), gz = o.getZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setBlockAndUpdate(new BlockPos(gx + dx, gy + dy, gz + dz),
                            Blocks.STONE.defaultBlockState());
                }
            }
        }
        // Carve the single goal air pocket (with a floor below), enclosed on all lateral sides.
        level.setBlockAndUpdate(new BlockPos(gx, gy, gz), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(gx, gy + 1, gz), Blocks.AIR.defaultBlockState());
    }
}
