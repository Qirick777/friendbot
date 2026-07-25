package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
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
 * (item 4, companion to {@link BotKiteExecMonitorTest}) LATENCY of signal B measured where B is
 * capable of firing at all.
 *
 * <p>{@code bot_kite_execmon} shows B's blind spot: a target already AT contact cannot close the gap
 * any further, so {@code d(gap)/dt ≈ 0} and B never triggers. That harness therefore reports "never
 * fired", not a latency. This one supplies the condition B was actually defined for — a gap that is
 * genuinely and continuously shrinking — and measures how many ticks B needs.</p>
 *
 * <p>The pursuer is SPEED-INJECTED (position written each tick, {@code CLOSE_RATE} b/t straight at
 * the bot) so the closing rate is a known constant instead of an emergent one: B's input,
 * {@code d(gap)/dt}, is then controlled, and the measured number is B's latency and nothing else.</p>
 *
 * <p>Ceiling (spec intent): B's own definition spends {@code KITE_FAIL_TICKS}=10 consecutive closing
 * ticks before it may fire, so the ceiling is 10 + 10 allowance = 20 ticks. The design registers B
 * as the "수 틱" signal; slower than that and the A/B role split does not hold.</p>
 */
public class BotKiteExecLatencyTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int START_GAP = 16;
    private static final double CLOSE_RATE = 0.35;   // > the bot's sprint 0.2806 → the gap must shrink
    private static final int SETTLE = 60;            // engage + let the band settle before measuring
    private static final int RUN_TICKS = 200;
    private static final int EXEC_MAX_TICKS = 20;    // 10 (B's definition) + 10 (allowance)

    private Zombie pursuer;
    private double pursuerX;
    private int closingStartTick = -1;
    private int execTick = -1;
    private boolean fired;
    private double gapAtClosingStart = -1;
    private double gapAtExec = -1;
    private double minGap = Double.MAX_VALUE;
    private double sumTrend;
    private int trendTicks;

    @Override
    public String name() {
        return "bot_kite_execmon_lat";
    }

    @Override
    public int timeoutTicks() {
        return RUN_TICKS + 60;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);

        for (int dx = -40; dx <= START_GAP + 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 3; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        bot.survival().reset();
        bot.getInventory().clearContent();
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0);
        bot.setHealth(200.0F);
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(true);   // this harness measures ticks, not damage
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        pursuerX = o.getX() + START_GAP;
        pursuer = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + START_GAP, o.getY(), o.getZ()));
        if (pursuer != null) {
            pursuer.setInvulnerable(true);
            pursuer.setNoAi(true);      // motion is INJECTED at a known rate
            pursuer.setPersistenceRequired();
        }
        fired = false;
        execTick = -1;
        closingStartTick = -1;
        minGap = Double.MAX_VALUE;
        sumTrend = 0;
        trendTicks = 0;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || pursuer == null) {
            return true;
        }
        int t = ctx.elapsedTicks;
        bot.rangedCombat().setTarget(pursuer);   // engage: this is where signal B is evaluated

        double gap = Math.hypot(pursuer.getX() - bot.getX(), pursuer.getZ() - bot.getZ());
        minGap = Math.min(minGap, gap);

        if (t >= SETTLE) {
            // Close on the bot at a fixed rate. Its own retreat is whatever the controller does; the
            // net d(gap)/dt is therefore negative and roughly constant — exactly B's input.
            double dir = Math.signum(bot.getX() - pursuerX);
            pursuerX += dir * CLOSE_RATE;
            pursuer.setDeltaMovement(Vec3.ZERO);
            pursuer.moveTo(pursuerX, bot.getY(), bot.getZ(), 0.0F, 0.0F);
            if (closingStartTick < 0) {
                closingStartTick = t;
                gapAtClosingStart = gap;
                // Zero the monitor at the start of the measured stretch: during the settle phase the
                // BOT walks in to its rule-3 band, which is also a shrinking gap, and that latched B
                // at ticksToExec:0 on the previous run. The latency being measured is B's response
                // to the PURSUER closing, so the counter starts here.
                bot.kiteMonitor().reset();
                LOGGER.info("[EXECLAT] closing starts t={} gap={}", t, String.format("%.2f", gap));
            }
            double trend = bot.perception().speeds.gapTrend(pursuer);
            sumTrend += trend;
            trendTicks++;
            if (!fired && bot.kiteMonitor().failing(pursuer)) {
                fired = true;
                execTick = t;
                gapAtExec = gap;
                LOGGER.info("[EXECLAT] signal B fired t={} (+{} ticks after closing began) gap={}->{}",
                        t, t - closingStartTick, String.format("%.2f", gapAtClosingStart),
                        String.format("%.2f", gap));
            }
        }
        return fired || t >= RUN_TICKS;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        int ticksToExec = fired ? execTick - closingStartTick : -1;
        double meanTrend = trendTicks > 0 ? sumTrend / trendTicks : 0;
        // Premise: the gap really was closing. Without it a non-firing B proves nothing.
        boolean closing = gapAtClosingStart > 0 && minGap < gapAtClosingStart - 1.0;
        boolean ok = closing && fired && ticksToExec <= EXEC_MAX_TICKS;

        LOGGER.info("[EXECLAT] RESULT fired={} ticksToExec={} (ceiling {}) meanTrend={} gap {}->{}",
                fired, ticksToExec, EXEC_MAX_TICKS, String.format("%.4f", meanTrend),
                String.format("%.2f", gapAtClosingStart), String.format("%.2f", minGap));
        String measured = String.format(
                "gapClosing:%b,execMonitorFired:%b,ticksToExec:%d,ceiling:%d,meanGapTrend:%.4f,"
                        + "trendLine:%.2f,gapAtClosingStart:%.2f,gapAtExec:%.2f,minGap:%.2f,injected:%.2f",
                closing, fired, ticksToExec, EXEC_MAX_TICKS, meanTrend, -0.02,
                gapAtClosingStart, gapAtExec, minGap, CLOSE_RATE);
        String expected = "with the gap genuinely and continuously closing, signal B fires within "
                + EXEC_MAX_TICKS + " ticks (10 = its own consecutive-tick definition + 10 allowance)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
