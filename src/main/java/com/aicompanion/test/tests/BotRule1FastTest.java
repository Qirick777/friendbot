package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.combat.CombatRules;
import com.aicompanion.bot.combat.CombatStats;
import com.aicompanion.bot.perception.TargetInfo;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Rule-1 FALSE-BRANCH gate (audit item 1f). Design 6.3 rule 1 has two branches; until now only
 * "적 속도 &lt; 봇 스프린트 → 카이팅 성립" was ever exercised, so the rule could degenerate into a
 * constant-true function without any harness noticing.
 *
 * <p>This harness does not trust any conversion constant. It MEASURES the target's real top speed
 * in blocks/tick while it chases the bot, measures the bot's own sprint the same way, and then
 * requires the rule's verdict to agree with the measurement:
 * {@code canKite == (measuredTargetPeak < botSprint)}. A target that genuinely outruns the bot must
 * come back {@code canKite=false} (→ 정면 대응).</p>
 */
public class BotRule1FastTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int BOT_PHASE = 100;
    private static final int START_DIST = 50;
    private static final int WARMUP = 40;
    private static final int WINDOW = 100;
    /** Fast enough that its measured speed clearly beats the bot's sprint (zombie ratio ≈0.5). */
    private static final double FAST_ATTR = 0.8;

    private Zombie fast;
    private Vec3 lastBotPos;
    private double botDist;
    private int botTicks;
    private Vec3 lastMobPos;
    private double peakStep;
    private Vec3 windowStartPos;
    private int windowStartTick = -1;
    private Vec3 windowEndPos;
    private int windowEndTick = -1;
    private double effective;
    private double botObservedSpeed;
    private Boolean canKite;
    private double attrSeen;

    @Override
    public String name() {
        return "bot_rule1_fast";
    }

    @Override
    public int timeoutTicks() {
        return BOT_PHASE + WARMUP + WINDOW + 80;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);

        for (int dx = -8; dx <= START_DIST + 40; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 4; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        bot.survival().reset();
        bot.getInventory().clearContent();
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(400.0);
        bot.setHealth(400.0F);
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(false);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();
        bot.mover().moveTo(o.getX() + 60.0, o.getZ() + 0.5); // phase 0: measure the bot's sprint
        lastBotPos = bot.position();
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            return true;
        }
        int t = ctx.elapsedTicks;
        bot.setHealth(bot.getMaxHealth());

        if (t < BOT_PHASE) {
            bot.setSprinting(true);
            Vec3 p = bot.position();
            if (t > 20) {
                botDist += Math.hypot(p.x - lastBotPos.x, p.z - lastBotPos.z);
                botTicks++;
            }
            lastBotPos = p;
            return false;
        }

        if (t == BOT_PHASE) {
            bot.mover().stop();
            bot.setSprinting(false);
            bot.setDeltaMovement(Vec3.ZERO);
            bot.moveTo(ctx.origin.getX() + 0.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5, 90.0F, 0.0F);
            fast = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(ctx.origin.getX() + START_DIST,
                    ctx.origin.getY(), ctx.origin.getZ()));
            if (fast != null) {
                fast.setInvulnerable(true);
                fast.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(FAST_ATTR);
                attrSeen = fast.getAttributeValue(Attributes.MOVEMENT_SPEED);
                lastMobPos = fast.position();
            }
            return false;
        }

        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(ctx.origin.getX() + 0.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5, 90.0F, 0.0F);
        if (fast == null || !fast.isAlive()) {
            return false;
        }
        fast.setTarget(bot);

        Vec3 p = fast.position();
        double step = Math.hypot(p.x - lastMobPos.x, p.z - lastMobPos.z);
        lastMobPos = p;
        int local = t - BOT_PHASE;
        // Reference measurement, same definition the bot's observer uses: horizontal DISPLACEMENT
        // divided by ELAPSED ticks (stationary ticks included). Measuring stops once the mob is on
        // top of the bot, since after that it has nowhere left to close.
        double gap = Math.hypot(p.x - bot.getX(), p.z - bot.getZ());
        if (local >= WARMUP && local < WARMUP + WINDOW) {
            peakStep = Math.max(peakStep, step);
            if (windowStartTick < 0) {
                windowStartTick = local;
                windowStartPos = p;
            }
            if (gap > 4.0) {
                windowEndTick = local;
                windowEndPos = p;
            }
        }
        if (windowStartPos != null && windowEndPos != null && windowEndTick > windowStartTick) {
            effective = Math.hypot(windowEndPos.x - windowStartPos.x, windowEndPos.z - windowStartPos.z)
                    / (windowEndTick - windowStartTick);
        }

        // Read the verdict WHILE the target is still closing. Once it is on top of the bot its
        // approach rate is legitimately ~0 (nothing left to close), so a sample taken then would
        // say "slow" about a mob that just ran the bot down.
        if (gap > 4.0) {
            for (TargetInfo ti : bot.perception().targets) {
                if (ti.entity == fast) {
                    canKite = CombatRules.canKite(ti, CombatStats.of(bot).sprintSpeed);
                    botObservedSpeed = ti.observedSpeed;
                    break;
                }
            }
        }
        return local >= WARMUP + WINDOW;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        double botSprint = botDist / Math.max(1, botTicks);
        boolean actuallyFaster = effective > botSprint;
        boolean ruleSaysKite = canKite != null && canKite;
        // The rule must agree with the measurement: a target that outruns the bot cannot be kited.
        boolean ok = canKite != null && (ruleSaysKite == !actuallyFaster);

        LOGGER.info("[RULE1] attr={} measuredEffective={} b/t (displacement/ticks) peak={} b/t "
                        + "botSprint={} b/t actuallyFaster={} canKite={} observedByBot={}",
                f(attrSeen), f(effective), f(peakStep), f(botSprint), actuallyFaster, canKite,
                f(botObservedSpeed));
        String measured = String.format(
                "attr:%.2f,measuredEffective:%.4f,peak:%.4f,botSprint:%.4f,actuallyFaster:%b,"
                        + "canKite:%s,botObserved:%.4f",
                attrSeen, effective, peakStep, botSprint, actuallyFaster, String.valueOf(canKite),
                botObservedSpeed);
        String expected = "canKite == (measured target effective approach speed < measured bot "
                + "sprint) — a target that outruns the bot must give canKite=false";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    private static String f(double v) {
        return String.format("%.4f", v);
    }
}
