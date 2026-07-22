package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * T3.3 melee check: the bot melee-fights a high-HP, stationary dummy with a sword. PASS iff the
 * dummy's health drops AND at least one hit lands ~1.5× the bot's base attack damage — i.e. a
 * critical hit was landed (judged by damage magnitude, per the design [검증]).
 */
public class BotMeleeTest implements BotTest {

    private Zombie dummy;
    private double botAtk;
    private double prevHp;
    private double maxHit;
    private double totalDrop;
    private int hits;

    @Override
    public String name() {
        return "bot_melee";
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
        // Night, so the undead dummy does not burn in daylight — sunlight fire (msgId=onFire)
        // was continuously re-triggering the target's hurt-invulnerability, absorbing the crits.
        ctx.level.setDayTime(18000L);
        for (int dx = -3; dx <= 8; dx++) {
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
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        bot.setInvulnerable(true); // survive the whole window regardless of the dummy

        // High-HP, stationary, knockback-immune punching bag with a SMALL hitbox (a golem is
        // large enough that the bot perches on it and never gets a clean fall for the crit).
        dummy = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + 2, o.getY(), o.getZ()));
        if (dummy != null) {
            dummy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(2000.0);
            dummy.setHealth(2000.0F);
            dummy.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0); // stay in reach
            dummy.getAttribute(Attributes.ARMOR).setBaseValue(0.0); // clean damage readings
            dummy.setNoAi(true); // stand still, don't fight back
        }

        prevHp = dummy != null ? dummy.getHealth() : 0;
        maxHit = 0;
        totalDrop = 0;
        hits = 0;

        bot.meleeCombat().setTarget(dummy);
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        if (dummy != null) {
            double hp = dummy.getHealth();
            double drop = prevHp - hp;
            if (drop > 0.01) {
                hits++;
                totalDrop += drop;
                if (drop > maxHit) {
                    maxHit = drop;
                }
            }
            prevHp = hp;
        }
        return ctx.elapsedTicks >= 380;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        // Base (non-crit, full-charge) melee damage = the bot's attack-damage attribute now
        // that the sword's modifier has been folded in (equipment updates during ticks).
        botAtk = (bot != null) ? bot.getAttributeValue(Attributes.ATTACK_DAMAGE) : 1.0;
        double critThresh = botAtk * 1.4; // clearly above a non-crit full-charge hit
        boolean damaged = totalDrop > 0.0;
        boolean critSeen = maxHit >= critThresh;

        boolean ok = damaged && critSeen;
        String measured = String.format("hits:%d,maxHit:%.2f,botAtk:%.2f,critThresh:%.2f,hpDrop:%.1f",
                hits, maxHit, botAtk, critThresh, totalDrop);
        String expected = "hpDrop>0 AND maxHit>=botAtk*1.4 (crit ~1.5x observed)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
