package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.aicompanion.test.TestUser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * T4.5 [검증] (a) CONTROL: identical to bot_escape_ride but the user is HEALTHY (&gt;15%). The escape
 * mount must NOT trigger — {@code user.getVehicle()} stays null the whole time. This proves the
 * kidnap-escape fires only below the critical line, not indiscriminately.
 */
public class BotEscapeNoneTest implements BotTest {

    private Zombie enemy;
    private ServerPlayer user;
    private boolean everMounted;

    @Override
    public String name() {
        return "bot_escape_none";
    }

    @Override
    public int timeoutTicks() {
        return 120;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 3; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        user = TestUser.spawn(ctx.server, ctx.level, o);
        user.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        user.setHealth(20.0F);       // full — ABOVE the critical line (>15%)
        user.setInvulnerable(true);

        bot.survival().reset();
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() - 1.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.getInventory().clearContent();
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        enemy = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + 5, o.getY(), o.getZ()));
        if (enemy != null) {
            enemy.setNoAi(true);
        }
        everMounted = false;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        if (user != null && user.getVehicle() != null) {
            everMounted = true;
        }
        return ctx.elapsedTicks >= 100;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        boolean ok = !everMounted; // healthy user must never be kidnapped
        String measured = String.format("everMounted:%b,userHp:%.1f", everMounted,
                user != null ? user.getHealth() : -1);
        String expected = "user>15% → no escape mount (getVehicle stays null)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
