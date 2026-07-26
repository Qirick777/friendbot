package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.aicompanion.test.TestUser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * T4.5 [검증] (b): the user falls from above the bot; the bot (already under the drop line) must
 * catch it by mounting — the user takes ZERO fall damage. The fake user has no client driving its
 * physics, so the test drives {@code doTick} + {@code doCheckFallDamage} each tick (same pattern as
 * the bot itself) — this makes the fall REAL: gravity, fallDistance accumulation, and genuine fall
 * damage on landing (proven by the catch_none control). PASS iff user hp unchanged AND
 * {@code user.getVehicle()==bot} was observed (actually caught, not merely unharmed).
 * Timing (전환 타이밍) is logged: the fall distance and bot-user gap at the mount tick.
 */
public class BotCatchFallTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DROP = 12;

    private ServerPlayer user;
    private float hp0;
    private float minHp;
    private boolean caught;
    private int mountTick = -1;

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
        return "bot_catch_fall";
    }

    @Override
    public int timeoutTicks() {
        return 200;
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return new int[]{-4, 4, -4, 4};
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= DROP + 4; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        // Bot on the ground, directly under the drop line.
        bot.survival().reset();
        bot.setInvulnerable(true);
        bot.getInventory().clearContent(); // no water bucket → catching is the only saver
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        // User high above the bot; physics driven by this test (fake player has no client).
        user = TestUser.spawn(ctx.server, ctx.level, o);
        user.stopRiding();
        user.setInvulnerable(false); // real fall damage must be possible
        user.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        user.setHealth(20.0F);
        user.setDeltaMovement(Vec3.ZERO);
        user.fallDistance = 0.0F;
        user.moveTo(o.getX() + 0.5, o.getY() + DROP, o.getZ() + 0.5, 0.0F, 0.0F);

        hp0 = user.getHealth();
        minHp = hp0;
        caught = false;
        mountTick = -1;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || user == null) {
            return true;
        }
        // Drive the fake user's physics + fall-damage path while it is NOT riding (its missing
        // client would normally do this). Once mounted, the bot's positionPassengers owns it.
        if (user.getVehicle() == null) {
            double px = user.getX();
            double py = user.getY();
            double pz = user.getZ();
            user.doTick(); // gravity → genuine fall
            user.doCheckFallDamage(user.getX() - px, user.getY() - py, user.getZ() - pz, user.onGround());
        }
        if (user.getVehicle() == bot && !caught) {
            caught = true;
            mountTick = ctx.elapsedTicks;
            LOGGER.info("[RESCUE] catch timing: mounted at t={} userY={} botY={} gap={} fallDist(user-tracked)={}",
                    mountTick, String.format("%.2f", user.getY()), String.format("%.2f", bot.getY()),
                    String.format("%.2f", user.getY() - bot.getY()), String.format("%.2f", user.fallDistance));
        }
        minHp = Math.min(minHp, user.getHealth());
        return ctx.elapsedTicks >= 180 || (caught && user.getVehicle() == null && ctx.elapsedTicks > mountTick + 30);
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        boolean unharmed = minHp >= hp0 - 0.01;
        boolean ok = caught && unharmed;
        String measured = String.format("caught:%b(t=%d),hp:%.1f->%.1f(min)", caught, mountTick, hp0, minHp);
        String expected = "user.getVehicle()==bot observed AND user hp unchanged (fall negated by catch)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
