package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.aicompanion.test.TestUser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * T4.5 [검증] (b) CONTROL: the same driven fall but the bot is far away (cannot catch). The user
 * must land and take REAL fall damage — proving that in bot_catch_fall it was the bot's catch that
 * prevented the damage (same contrast pattern as T4.4 bot_fall_nowater).
 */
public class BotCatchNoneTest implements BotTest {

    private static final int DROP = 12;

    private ServerPlayer user;
    private float hp0;
    private float minHp;
    private boolean everMounted;
    private double botUserHoriz;   // control-condition distance (must be unreachable, see judge log)
    private float maxFallDistance; // peak accumulated fallDistance, for the damage-formula check

    @Override
    public String name() {
        return "bot_catch_none";
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
        ctx.level.setDayTime(18000L);
        for (int dx = -4; dx <= 30; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= DROP + 4; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        // Bot far away (out of catch range and out of approach time).
        bot.survival().reset();
        bot.setInvulnerable(true);
        bot.getInventory().clearContent();
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 28.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        user = TestUser.spawn(ctx.server, ctx.level, o);
        user.stopRiding();
        user.setInvulnerable(false);
        user.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        user.setHealth(20.0F);
        user.setDeltaMovement(Vec3.ZERO);
        user.fallDistance = 0.0F;
        user.moveTo(o.getX() + 0.5, o.getY() + DROP, o.getZ() + 0.5, 0.0F, 0.0F);

        hp0 = user.getHealth();
        minHp = hp0;
        everMounted = false;
        maxFallDistance = 0.0F;
        // Record the control condition: the bot must be far enough that NO catch path could reach —
        // so this control never depends on a catch path being broken.
        botUserHoriz = Math.hypot(bot.getX() - user.getX(), bot.getZ() - user.getZ());
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        if (user == null) {
            return true;
        }
        if (user.getVehicle() == null) {
            double px = user.getX();
            double py = user.getY();
            double pz = user.getZ();
            user.doTick();
            // fallDistance is read BEFORE doCheckFallDamage consumes/resets it on landing, so the
            // peak here is exactly the value vanilla feeds into calculateFallDamage.
            maxFallDistance = Math.max(maxFallDistance, user.fallDistance);
            user.doCheckFallDamage(user.getX() - px, user.getY() - py, user.getZ() - pz, user.onGround());
        } else {
            everMounted = true;
        }
        minHp = Math.min(minHp, user.getHealth());
        return ctx.elapsedTicks >= 120;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        double damage = hp0 - minHp;
        boolean ok = !everMounted && damage > 0.5; // uncaught fall MUST hurt
        // Vanilla: calculateFallDamage = ceil(fallDistance - 3). Logging the peak fallDistance
        // pins whether an unexpected damage value comes from spawn/landing geometry (fall shorter
        // than the nominal drop) or from fallDistance under-accumulating (the fake-player hazard).
        double expectedDamage = Math.ceil(maxFallDistance - 3.0F);
        String measured = String.format(
                "mounted:%b,hp:%.1f->%.1f,damage:%.1f,fallDistance:%.2f,formulaDmg:%.0f,botUserHoriz:%.1f",
                everMounted, hp0, minHp, damage, maxFallDistance, expectedDamage, botUserHoriz);
        String expected = "no catch (bot far, horiz>>catch range) → fall damage>0 "
                + "(proves the catch was the cause in bot_catch_fall)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
