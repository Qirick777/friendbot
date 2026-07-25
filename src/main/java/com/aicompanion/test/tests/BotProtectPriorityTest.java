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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * T4.3 [검증] (b): with the user healthy (>40% → "최고위험 우선"), a HIGH-dps and a LOW-dps enemy are
 * both aggroed on the user. The bot must hit the high-dps one first. Judged by the strong invariant
 * "the low-dps enemy takes ZERO damage until the high-dps enemy is dead" — this proves it is the
 * priority rule at work, not a chance ordering. Also reports the first-drop ticks and their gap.
 */
public class BotProtectPriorityTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private Zombie high;   // attackDamage 12 → 고위험
    private Zombie low;    // attackDamage 2  → 저위험
    private double highHp0;
    private double lowHp0;
    private int highFirstDropTick = -1;
    private int lowFirstDropTick = -1;
    private int highDeadTick = -1;
    private double lowDropWhileHighAlive;
    private double prevHighHp;
    private double prevLowHp;

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
        return "bot_protect_priority";
    }

    @Override
    public int timeoutTicks() {
        return 420;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);

        for (int dx = -6; dx <= 10; dx++) {
            for (int dz = -6; dz <= 8; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 2; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        ServerPlayer user = TestUser.spawn(ctx.server, ctx.level, o);
        user.setInvulnerable(true);
        user.setHealth(user.getMaxHealth()); // >40% → P1 (highest-dps first)

        bot.survival().reset();
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() - 1.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.setInvulnerable(true);
        bot.setHealth(bot.getMaxHealth());
        bot.getInventory().clearContent();
        bot.getInventory().add(new ItemStack(Items.IRON_SWORD));
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        // Both aggroed on the user (within 10), stationary knockback-immune punching bags.
        high = spawnDummy(ctx, new BlockPos(o.getX() + 3, o.getY(), o.getZ()), 12.0, 30.0);
        low = spawnDummy(ctx, new BlockPos(o.getX() + 3, o.getY(), o.getZ() + 4), 2.0, 200.0);

        highHp0 = high != null ? high.getHealth() : 0;
        lowHp0 = low != null ? low.getHealth() : 0;
        prevHighHp = highHp0;
        prevLowHp = lowHp0;
    }

    private Zombie spawnDummy(BotTestContext ctx, BlockPos pos, double atk, double hp) {
        Zombie z = ctx.env.spawn(EntityType.ZOMBIE, pos);
        if (z != null) {
            z.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(atk);
            z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(hp);
            z.setHealth((float) hp);
            z.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
            z.getAttribute(Attributes.ARMOR).setBaseValue(0.0);
            z.setNoAi(true);
        }
        return z;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        ServerPlayer user = TestUser.current();
        int t = ctx.elapsedTicks;
        if (high != null) {
            if (user != null) {
                high.setTarget(user); // keep it aggroed on the user
            }
            double hp = high.getHealth();
            if (hp < prevHighHp - 0.01 && highFirstDropTick < 0) {
                highFirstDropTick = t;
            }
            if (!high.isAlive() && highDeadTick < 0) {
                highDeadTick = t;
            }
            prevHighHp = hp;
        }
        if (low != null) {
            if (user != null) {
                low.setTarget(user);
            }
            double hp = low.getHealth();
            double drop = prevLowHp - hp;
            if (drop > 0.01) {
                if (lowFirstDropTick < 0) {
                    lowFirstDropTick = t;
                }
                if (highDeadTick < 0) { // high still alive → low should be untouched
                    lowDropWhileHighAlive += drop;
                }
            }
            prevLowHp = hp;
        }
        // End once the high-dps target is dead and we've observed a beat after, or on timeout.
        return (highDeadTick >= 0 && t >= highDeadTick + 20) || t >= 400;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        boolean highEngaged = highFirstDropTick >= 0;
        boolean highKilled = highDeadTick >= 0;
        boolean lowUntouchedUntilHighDead = lowDropWhileHighAlive < 0.01;
        boolean ok = highEngaged && highKilled && lowUntouchedUntilHighDead;

        int gap = (highFirstDropTick >= 0 && lowFirstDropTick >= 0)
                ? lowFirstDropTick - highFirstDropTick : -1;
        LOGGER.info("[PROTECT] priority judge: highFirstDrop={} highDead={} lowFirstDrop={} "
                        + "lowDropWhileHighAlive={} gap={}",
                highFirstDropTick, highDeadTick, lowFirstDropTick, lowDropWhileHighAlive, gap);
        String measured = String.format(
                "highFirstDrop:%d,highDead:%d,lowFirstDrop:%d,lowDropWhileHighAlive:%.1f,gap:%d",
                highFirstDropTick, highDeadTick, lowFirstDropTick, lowDropWhileHighAlive, gap);
        String expected = "high engaged+killed AND low ZERO damage until high dead (priority, not chance)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
