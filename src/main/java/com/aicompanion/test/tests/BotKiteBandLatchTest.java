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
 * (3b) PERMANENT-LATCH gate for the hysteresis band. The cold start is deliberately
 * {@code canKite=false}; the danger of that choice is a target whose measured speed lands INSIDE the
 * ±3% band (0.97~1.03 × bot sprint) — inside the band the rule returns {@code previous}, so a
 * cold-start false could be held forever and the bot would never kite a target it can in fact outrun.
 *
 * <p>The {@code settled} flag is what prevents that: while no real verdict has been established yet,
 * an in-band reading is decided by the band midpoint instead of by {@code previous}. This harness
 * locks that behaviour with a value.</p>
 *
 * <p>SPEED-INJECTED DUMMY: the subject is not AI-driven — its position is written every tick along a
 * straight line at exactly {@code INJECT_RATIO × BOT_SPRINT_SPEED}, so the observer's input is a
 * genuine displacement at a known, in-band rate (not an attribute the observer never reads). The
 * line is tangential (nearest approach ≈6 blocks, farthest ≈17), so the whole run stays outside
 * {@code GLUED_GAP} and inside {@code PERCEPTION_RANGE} — every tick is a VALID observation, which is
 * exactly the condition under which a latch would be inexcusable.</p>
 */
public class BotKiteBandLatchTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 99% of the bot's sprint — inside the 97~103% band, below the midpoint (1.00). */
    private static final double INJECT_RATIO = 0.99;
    private static final double INJECT = CombatStats.BOT_SPRINT_SPEED * INJECT_RATIO;
    private static final int LANE_X = 6;      // constant lateral offset → gap never gets glued
    private static final int LANE_Z0 = -16;   // straight run -16 → +16 along Z
    private static final int RUN_TICKS = 140;

    private Zombie dummy;
    private double laneZ;
    private boolean coldStartFalse;      // the verdict really did start at the conservative false
    private boolean sawColdStart;
    private boolean latchBroken;         // ... and did NOT stay there
    private int ticksToVerdict = -1;
    private double observedAtVerdict;
    private double maxObserved;
    private double minGap = Double.MAX_VALUE;
    private double maxGap;

    @Override
    public String name() {
        return "bot_kite_band_latch";
    }

    @Override
    public int timeoutTicks() {
        return RUN_TICKS + 40;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);

        for (int dx = -6; dx <= 14; dx++) {
            for (int dz = -22; dz <= 22; dz++) {
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
        bot.setInvulnerable(true);        // nothing here is about damage
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        laneZ = LANE_Z0;
        dummy = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + LANE_X, o.getY(), o.getZ() + LANE_Z0));
        if (dummy != null) {
            dummy.setInvulnerable(true);
            dummy.setNoAi(true);          // motion is INJECTED, not attribute-driven
            dummy.setPersistenceRequired();
        }
        coldStartFalse = false;
        sawColdStart = false;
        latchBroken = false;
        ticksToVerdict = -1;
        maxObserved = 0;
        minGap = Double.MAX_VALUE;
        maxGap = 0;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || dummy == null) {
            return true;
        }
        // Bot pinned; only the dummy moves, and it moves at a rate we chose.
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(ctx.origin.getX() + 0.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5, -90.0F, 0.0F);

        laneZ += INJECT;
        dummy.setDeltaMovement(Vec3.ZERO);
        dummy.moveTo(ctx.origin.getX() + LANE_X + 0.5, ctx.origin.getY(), ctx.origin.getZ() + laneZ + 0.5,
                0.0F, 0.0F);

        int t = ctx.elapsedTicks;
        double gap = Math.hypot(dummy.getX() - bot.getX(), dummy.getZ() - bot.getZ());
        minGap = Math.min(minGap, gap);
        maxGap = Math.max(maxGap, gap);

        for (TargetInfo ti : bot.perception().targets) {
            if (ti.entity != dummy) {
                continue;
            }
            boolean kite = CombatRules.canKite(ti, CombatStats.of(bot).sprintSpeed);
            maxObserved = Math.max(maxObserved, ti.observedSpeed);
            if (!sawColdStart) {
                sawColdStart = true;
                coldStartFalse = !kite;   // must be the conservative false before any observation
            }
            if (!latchBroken && kite) {
                latchBroken = true;
                ticksToVerdict = t;
                observedAtVerdict = ti.observedSpeed;
                LOGGER.info("[BANDLATCH] verdict left the cold-start false at t={} observed={} "
                                + "({}% of sprint) gap={}",
                        t, String.format("%.4f", ti.observedSpeed),
                        String.format("%.1f", 100.0 * ti.observedSpeed / CombatStats.BOT_SPRINT_SPEED),
                        String.format("%.2f", gap));
            }
            break;
        }
        return latchBroken || t >= RUN_TICKS;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        double sprint = CombatStats.BOT_SPRINT_SPEED;
        double bandLo = sprint * CombatRules.KITE_ENTER_RATIO;
        double bandHi = sprint * CombatRules.KITE_EXIT_RATIO;
        double judged = latchBroken ? observedAtVerdict : maxObserved;
        boolean inBand = judged >= bandLo && judged <= bandHi;
        boolean ok = sawColdStart && coldStartFalse && inBand && latchBroken;

        LOGGER.info("[BANDLATCH] RESULT coldStartFalse={} latchBroken={} ticks={} observed={} inBand={}",
                coldStartFalse, latchBroken, ticksToVerdict, String.format("%.4f", judged), inBand);
        String measured = String.format(
                "injected:%.4f(%.1f%%),observedAtVerdict:%.4f(%.1f%%),inBand:%b,band:%.4f~%.4f,"
                        + "coldStartFalse:%b,latchBroken:%b,ticksToVerdict:%d,gap:%.2f~%.2f",
                INJECT, 100.0 * INJECT_RATIO, judged, 100.0 * judged / sprint, inBand, bandLo, bandHi,
                coldStartFalse, latchBroken, ticksToVerdict, minGap, maxGap);
        String expected = "a target measured INSIDE the 97~103% band starts at the conservative "
                + "canKite=false and does NOT stay there — the unsettled midpoint rule resolves it";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
