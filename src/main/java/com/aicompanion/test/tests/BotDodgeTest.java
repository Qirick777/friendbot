package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * T4.2 reflex R1 check (sidestep evade): a real skeleton fires arrows at the bot, which holds NO
 * shield → the reflex must sidestep. PASS iff arrows were actually fired AND the bot took ZERO
 * damage (health unchanged) AND the bot actually moved laterally (evasion happened, not "the reflex
 * was called"). NOTE (design [검증] scope): this measures the correlation "evasion happened + no hit";
 * it does NOT prove causation (that a stationary bot WOULD have been hit) — that A/B is out of scope.
 */
public class BotDodgeTest implements BotTest {

    private Skeleton skeleton;
    private Vec3 startPos;
    private float startHp;
    private float minHp;
    private double maxLateral;
    private final Set<Integer> arrowIds = new HashSet<>();
    // Diagnostics (audit item 4): lateral distance alone has no diagnostic power — PASS and FAIL
    // runs overlap on it. These separate "evade never fired" from "evade fired but the arrow hit".
    private int hitEvents;          // distinct health-drop events = arrows that landed
    private int evadeTicks;         // ticks the R1 evade actually drove movement
    private float prevHp;

    /**
     * 50, not the screening tier's 30. At 24/30 the point estimate landed exactly on the spec
     * threshold (0.80) and only the Wilson bound missed (0.627 vs 0.65) — that is sample shortage,
     * not evidence of a defect. The screening n is a floor, not a cap: if p-hat holds at 0.80, n=50
     * puts the bound at ~0.670 and the question is settled either way.
     */
    @Override
    public int repeats() {
        return 50;
    }

    @Override
    public double successThreshold() {
        return 0.80;
    }

    @Override
    public String name() {
        return "bot_dodge";
    }

    @Override
    public int timeoutTicks() {
        return 260;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);

        // Wide platform: the bot circle-strafes around the skeleton (radius ~7 at +7X), so the
        // orbit spans roughly x∈[0,15], z∈[-8,8]. Give margin on all sides.
        for (int dx = -6; dx <= 18; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 3; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        bot.survival().reset();
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90.0F, 0.0F);
        bot.setInvulnerable(false);                 // must genuinely NOT be hit
        bot.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ItemStack.EMPTY); // no shield → sidestep
        bot.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(20.0);
        bot.setHealth(20.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        // Real skeleton with a bow ~7 blocks east, targeting the bot → fires arrows at it.
        skeleton = ctx.env.spawn(EntityType.SKELETON, new BlockPos(o.getX() + 7, o.getY(), o.getZ()));
        if (skeleton != null) {
            skeleton.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
            skeleton.setPersistenceRequired();
            skeleton.setTarget(bot);
        }

        startPos = bot.position();
        startHp = bot.getHealth();
        minHp = startHp;
        prevHp = startHp;
        maxLateral = 0;
        hitEvents = 0;
        evadeTicks = 0;
        arrowIds.clear();
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            return true;
        }
        if (skeleton != null && skeleton.isAlive()) {
            skeleton.setTarget(bot); // keep it shooting at the bot
        }
        float hp = bot.getHealth();
        if (hp < prevHp - 0.01) {
            hitEvents++;
        }
        prevHp = hp;
        if (hp < minHp) {
            minHp = hp;
        }
        // The evade owns movement when it runs, and it is the only layer that sprints here.
        if (bot.isSprinting()) {
            evadeTicks++;
        }
        double lateral = Math.abs(bot.getZ() - startPos.z);
        if (lateral > maxLateral) {
            maxLateral = lateral;
        }
        // Count distinct arrows that have been fired into the arena.
        AABB box = new AABB(ctx.origin).inflate(24.0);
        for (AbstractArrow a : ctx.level.getEntitiesOfClass(AbstractArrow.class, box)) {
            arrowIds.add(a.getId());
        }
        return ctx.elapsedTicks >= 240;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        int arrows = arrowIds.size();
        boolean fired = arrows >= 3;
        boolean unharmed = minHp >= startHp - 0.01;   // ZERO damage taken
        boolean sidestepped = maxLateral > 1.0;        // evasion actually moved the bot

        boolean ok = fired && unharmed && sidestepped;
        String measured = String.format(
                "arrows:%d,hits:%d,evadeTicks:%d,hp:%.1f->%.1f(min),lateral:%.2f",
                arrows, hitEvents, evadeTicks, startHp, minHp, maxLateral);
        String expected = "arrows>=3 AND hp unchanged (no hit) AND lateral>1.0 (sidestep occurred)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
