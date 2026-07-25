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
 * (item 4) LATENCY of signal B, the execution monitor — measured with signal A's invalid-observation
 * gate DELIBERATELY BYPASSED.
 *
 * <p>Why this harness has to exist: in {@link BotKiteFlipTest} the reported {@code hpLostDuringLag}
 * was 0.0, but that number was produced by A's gate refusing to enter the bad state at all
 * ({@code startedKiteable:false}). B — the thing that is supposed to be the safety net WHEN the bad
 * state happens — was therefore never exercised. A safety net that has never caught anything is
 * unverified, not "good".</p>
 *
 * <p>Bypass mechanism (no code is disabled — the gate is fed a genuinely valid observation):
 * A's gate blocks only when the bot is pinned AND the target is glued. So during phase 1 the bot is
 * kept gently MOVING (oscillation, per-tick displacement ≈0.06 &gt; {@code BOT_MOVING_MIN}) with the
 * fast target riding along beside it. Every tick is a valid observation, and what it validly measures
 * is a target whose net displacement over the window is ≈0 — so rule 1 concludes
 * {@code canKite=true} about a target that in fact outruns the bot. That is exactly the bad state A
 * cannot avoid, and it is where B has to earn its keep.</p>
 *
 * <p>Threshold derivation (spec intent, not measurement): the design registers B as the
 * "수 틱 반응" signal, and B's own definition already spends {@code KITE_FAIL_TICKS}=10 consecutive
 * closing ticks before it may fire. The ceiling is therefore 10 (definition) + 10 (detection
 * allowance) = 20 ticks. Anything slower means B is not a few-tick signal and the A/B role split
 * does not hold.</p>
 */
public class BotKiteExecMonitorTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double FAST_ATTR = 2.0;   // must genuinely outrun the bot's retreat
    private static final int BYPASS_TICKS = 120;   // > MIN_SPAN_TICKS(60): a full VALID window
    private static final int FLEE_TICKS = 240;
    private static final int EXEC_MAX_TICKS = 20;  // 10 (B's own definition) + 10 (allowance)
    private static final double OSC_STEP = 0.06;   // > BOT_MOVING_MIN(0.02): the bot is not pinned

    private Zombie chaser;
    private boolean startedKiteable;     // the bad state was actually entered (bypass worked)
    private boolean execMonitorFired;
    private boolean aFlipped;
    private int fleeStartTick = -1;
    private int execTick = -1;
    private int aFlipTick = -1;
    private float hpAtFleeStart = -1;
    private float hpAtExec = -1;
    private float hpEnd = -1;
    private double gapAtFlee = -1;
    private double gapAtExec = -1;
    private double minGapDuringFlee = Double.MAX_VALUE;
    private double observedAtFlee;
    private int intentOpenTicks;      // did the bot ever ASK to open distance?
    private int maxFailingTicks;      // how close B came to its threshold
    private int failingTicksTotal;    // failing ticks in TOTAL (not necessarily consecutive)

    @Override
    public String name() {
        return "bot_kite_execmon";
    }

    @Override
    public int timeoutTicks() {
        return BYPASS_TICKS + FLEE_TICKS + 60;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);

        for (int dx = -90; dx <= 20; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
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
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0);
        bot.setHealth(200.0F);
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(false);   // the lag cost must be paid in real health
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        chaser = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + 1, o.getY(), o.getZ()));
        if (chaser != null) {
            chaser.setInvulnerable(true);
            chaser.setPersistenceRequired();
            chaser.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(FAST_ATTR);
            chaser.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(4.0);
        }
        startedKiteable = false;
        execMonitorFired = false;
        aFlipped = false;
        fleeStartTick = -1;
        execTick = -1;
        aFlipTick = -1;
        minGapDuringFlee = Double.MAX_VALUE;
        intentOpenTicks = 0;
        maxFailingTicks = 0;
        failingTicksTotal = 0;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || chaser == null) {
            return true;
        }
        chaser.setTarget(bot);
        int t = ctx.elapsedTicks;
        double gap = Math.hypot(chaser.getX() - bot.getX(), chaser.getZ() - bot.getZ());

        Boolean kite = null;
        double observed = 0;
        for (TargetInfo ti : bot.perception().targets) {
            if (ti.entity == chaser) {
                kite = CombatRules.canKite(ti, CombatStats.of(bot).sprintSpeed);
                observed = ti.observedSpeed;
                break;
            }
        }

        if (t < BYPASS_TICKS) {
            // Phase 1: the bot MOVES (so the gate opens) but goes nowhere (so the target beside it
            // measures as slow). Valid observation, wrong conclusion — by construction.
            double z = ctx.origin.getZ() + 0.5 + ((t / 8) % 2 == 0 ? 1 : -1) * (t % 8) * OSC_STEP;
            bot.setDeltaMovement(Vec3.ZERO);
            bot.moveTo(ctx.origin.getX() + 0.5, ctx.origin.getY(), z, -90.0F, 0.0F);
            bot.setHealth(bot.getMaxHealth());   // the bypass phase is not the measured cost
            return false;
        }

        if (t == BYPASS_TICKS) {
            fleeStartTick = t;
            startedKiteable = kite != null && kite;
            hpAtFleeStart = bot.getHealth();
            gapAtFlee = gap;
            observedAtFlee = observed;
            LOGGER.info("[EXECMON] bypass ends t={} canKite={} (bad state entered={}) observed={} gap={}",
                    t, kite, startedKiteable, String.format("%.4f", observed), String.format("%.2f", gap));
        }

        // Phase 2: the bot acts on the (wrong) kiteable verdict and disengages. Engaging the target
        // hands movement to the ranged controller, which is also where the execution monitor lives.
        bot.rangedCombat().setTarget(chaser);
        minGapDuringFlee = Math.min(minGapDuringFlee, gap);
        // Without these, "B stayed silent" cannot be told apart from "the bot never tried to kite".
        if (bot.kiteMonitor().intendedOpen()) {
            intentOpenTicks++;
        }
        maxFailingTicks = Math.max(maxFailingTicks, bot.kiteMonitor().closingTicks(chaser));
        if (bot.kiteMonitor().intendedOpen()
                && bot.perception().speeds.gapTrend(chaser)
                        < com.aicompanion.bot.combat.KiteMonitor.MIN_OPENING_RATE) {
            failingTicksTotal++;
        }

        // Query the monitor itself, not the ranged controller's mirror: the whole point is that the
        // ranged branch may never run in this scenario.
        if (!execMonitorFired && bot.kiteMonitor().failing(chaser)) {
            execMonitorFired = true;
            execTick = t;
            hpAtExec = bot.getHealth();
            gapAtExec = gap;
            LOGGER.info("[EXECMON] signal B fired at t={} (+{} ticks) gap={}->{} hp={}->{}",
                    t, t - fleeStartTick, String.format("%.2f", gapAtFlee), String.format("%.2f", gap),
                    hpAtFleeStart, hpAtExec);
        }
        if (!aFlipped && kite != null && !kite) {
            aFlipped = true;
            aFlipTick = t;
            LOGGER.info("[EXECMON] signal A flipped at t={} (+{} ticks)", t, t - fleeStartTick);
        }
        hpEnd = bot.getHealth();
        return (execMonitorFired && aFlipped) || t >= BYPASS_TICKS + FLEE_TICKS;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        int ticksToExec = execMonitorFired ? execTick - fleeStartTick : -1;
        int ticksToA = aFlipped ? aFlipTick - fleeStartTick : -1;
        double hpLostUntilExec = (hpAtFleeStart >= 0 && hpAtExec >= 0) ? hpAtFleeStart - hpAtExec : -1;
        double hpLostTotal = hpAtFleeStart >= 0 ? hpAtFleeStart - hpEnd : -1;
        double gapClosed = (gapAtFlee >= 0 && minGapDuringFlee < Double.MAX_VALUE)
                ? gapAtFlee - minGapDuringFlee : -1;

        // Premise: the bad state must really have been entered, and the kiting must really be
        // failing — otherwise there is nothing for B to catch and the latency is meaningless.
        boolean premise = startedKiteable;
        boolean ok = premise && execMonitorFired && ticksToExec <= EXEC_MAX_TICKS;

        LOGGER.info("[EXECMON] RESULT startedKiteable={} fired={} ticksToExec={} (ceiling {}) "
                        + "ticksToA={} hpLostUntilExec={}",
                startedKiteable, execMonitorFired, ticksToExec, EXEC_MAX_TICKS, ticksToA, hpLostUntilExec);
        String measured = String.format(
                "startedKiteable:%b,execMonitorFired:%b,ticksToExec:%d,ceiling:%d,ticksToAFlip:%d,"
                        + "hpLostUntilExec:%.1f,hpLostTotal:%.1f,gapAtFlee:%.2f,gapAtExec:%.2f,"
                        + "gapClosed:%.2f,observedAtFlee:%.4f,intentOpenTicks:%d,maxFailingTicks:%d,"
                        + "failingTicksTotal:%d,"
                        + "enterLine:%.4f",
                startedKiteable, execMonitorFired, ticksToExec, EXEC_MAX_TICKS, ticksToA,
                hpLostUntilExec, hpLostTotal, gapAtFlee, gapAtExec, gapClosed, observedAtFlee,
                intentOpenTicks, maxFailingTicks, failingTicksTotal,
                CombatStats.BOT_SPRINT_SPEED * CombatRules.KITE_ENTER_RATIO);
        String expected = "with A's gate bypassed the bot really enters canKite=true on a target it "
                + "cannot outrun; signal B must catch it within " + EXEC_MAX_TICKS
                + " ticks (10 = B's own consecutive-tick definition + 10 allowance)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
