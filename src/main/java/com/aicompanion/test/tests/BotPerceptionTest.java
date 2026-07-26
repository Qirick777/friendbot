package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.perception.Perception;
import com.aicompanion.bot.perception.TargetInfo;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * T3.1 perception check: spawn a zombie and fire an arrow at the bot, then read the bot's
 * Perception. PASS iff the target list contains the zombie with attack-damage/health matching
 * the zombie's actual attributes (measured, not hardcoded) AND the arrow is caught in the
 * incoming-projectile list.
 */
public class BotPerceptionTest implements BotTest {

    private Zombie zombie;
    private double expAtk;
    private double expHp;
    private int maxIncomingSeen;

    @Override
    public String name() {
        return "bot_perception";
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return new int[]{-3, 6, -3, 3};
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;

        // Small clear platform.
        for (int dx = -3; dx <= 6; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 2; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);

        // Zombie ~3 blocks ahead; record its ACTUAL attributes as ground truth.
        zombie = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + 3, o.getY(), o.getZ()));
        if (zombie != null) {
            expAtk = zombie.getAttributeValue(Attributes.ATTACK_DAMAGE);
            expHp = zombie.getMaxHealth();
        }

        // Arrow ~4 blocks away moving toward the bot (incoming).
        double ax = o.getX() + 4.5, ay = o.getY() + 1.0, az = o.getZ() + 0.5;
        Arrow arrow = new Arrow(ctx.level, ax, ay, az);
        Vec3 toBot = new Vec3(bot.getX() - ax, (bot.getY() + 1.0) - ay, bot.getZ() - az).normalize().scale(0.4);
        arrow.setDeltaMovement(toBot);
        ctx.level.addFreshEntity(arrow);

        maxIncomingSeen = 0;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot != null) {
            int inc = bot.perception().incoming.size();
            if (inc > maxIncomingSeen) {
                maxIncomingSeen = inc;
            }
        }
        return ctx.elapsedTicks >= 20;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            return BotTestResult.fail("bot:null", "perceives zombie + arrow");
        }
        Perception p = bot.perception();

        TargetInfo z = null;
        for (TargetInfo t : p.targets) {
            if (t.entity == zombie) {
                z = t;
                break;
            }
        }

        boolean hasTarget = !p.targets.isEmpty();
        boolean zombieFound = z != null;
        boolean atkMatch = zombieFound && Math.abs(z.attackDamage - expAtk) < 0.01;
        boolean hpMatch = zombieFound && Math.abs(z.maxHealth - expHp) < 0.01 && expHp == 20.0;
        boolean arrowSeen = maxIncomingSeen >= 1;

        boolean ok = hasTarget && zombieFound && atkMatch && hpMatch && arrowSeen;
        String measured = String.format("targets:%d,zAtk:%.2fvs%.2f,zHp:%.1fvs%.1f,incoming:%d",
                p.targets.size(),
                zombieFound ? z.attackDamage : -1, expAtk,
                zombieFound ? z.maxHealth : -1, expHp,
                maxIncomingSeen);
        String expected = "targets>=1 AND zombie atk==attr AND hp==20 AND incoming>=1";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
