package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.combat.CombatStats;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * T4.6 AUDIT PROBE — multi-mob speed re-measurement (audit item 1b/1c/1d).
 *
 * <p>The single warden observation behind the 0.157 attribute→blocks/tick factor is re-taken across
 * several mobs, each chasing the (stationary) bot down a clear flat lane. For every mob it reports
 * the mean step over the window, the PEAK single-tick step (top speed, immune to pathing stalls),
 * the fraction of ticks it actually moved, and — for the warden — the pose, so an EMERGING/ROARING
 * animation lock cannot masquerade as "slow".</p>
 */
public class BotSpeedProbeTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int BOT_PHASE = 100;   // ticks spent measuring the bot's sprint
    private static final int PER_MOB = 180;     // ticks per mob
    private static final int WARMUP = 40;       // ticks skipped for spawn/pathfind spin-up
    private static final int WINDOW = 100;      // measured ticks
    private static final int START_DIST = 30;

    private record Subject(String label, EntityType<? extends Mob> type) {
    }

    private static final List<Subject> SUBJECTS = List.of(
            new Subject("zombie", EntityType.ZOMBIE),
            new Subject("spider", EntityType.SPIDER),
            new Subject("iron_golem", EntityType.IRON_GOLEM),
            new Subject("skeleton", EntityType.SKELETON),
            new Subject("warden", EntityType.WARDEN));

    private static final class Result {
        String label;
        double attr;
        double meanAll;
        double peakStep;
        double movingFrac;
        String poses = "";
    }

    private final List<Result> results = new ArrayList<>();
    private Mob current;
    private Result currentResult;
    private Vec3 lastMobPos;
    private double botDist;
    private int botTicks;
    private Vec3 lastBotPos;
    private double sumStep;
    private int stepTicks;
    private int movingTicks;
    private final java.util.Set<String> poseSet = new java.util.LinkedHashSet<>();

    @Override
    public String name() {
        return "bot_speed_probe";
    }

    @Override
    public int timeoutTicks() {
        return BOT_PHASE + PER_MOB * SUBJECTS.size() + 60;
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
        bot.setInvulnerable(false); // Warden.canTargetEntity rejects invulnerable targets
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();
        bot.mover().moveTo(o.getX() + 60.0, o.getZ() + 0.5); // phase 0: sprint straight
        lastBotPos = bot.position();
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            return true;
        }
        int t = ctx.elapsedTicks;
        bot.setHealth(bot.getMaxHealth()); // never die to the chasers

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

        int idx = (t - BOT_PHASE) / PER_MOB;
        int local = (t - BOT_PHASE) % PER_MOB;
        if (idx >= SUBJECTS.size()) {
            return true;
        }

        if (local == 0) {
            // finish the previous subject, start the next
            finishCurrent();
            if (current != null) {
                current.discard();
            }
            bot.mover().stop();
            bot.setSprinting(false);
            bot.setDeltaMovement(Vec3.ZERO);
            bot.moveTo(ctx.origin.getX() + 0.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5, 90.0F, 0.0F);

            Subject s = SUBJECTS.get(idx);
            current = ctx.env.spawn(s.type(), new BlockPos(ctx.origin.getX() + START_DIST,
                    ctx.origin.getY(), ctx.origin.getZ()));
            currentResult = new Result();
            currentResult.label = s.label();
            if (current != null) {
                current.setInvulnerable(true);
                currentResult.attr = current.getAttributeValue(Attributes.MOVEMENT_SPEED);
                lastMobPos = current.position();
            }
            sumStep = 0;
            stepTicks = 0;
            movingTicks = 0;
            poseSet.clear();
            return false;
        }

        // Keep the bot pinned so the chase distance is the mob's doing only.
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(ctx.origin.getX() + 0.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5, 90.0F, 0.0F);

        if (current == null || !current.isAlive()) {
            return false;
        }
        // Force aggression every tick (fake players are not naturally targeted — T4.3 finding).
        if (current instanceof Warden w) {
            w.increaseAngerAt(bot);
            w.setAttackTarget(bot);
        } else {
            current.setTarget(bot);
        }
        poseSet.add(current.getPose().name());

        Vec3 p = current.position();
        double step = Math.hypot(p.x - lastMobPos.x, p.z - lastMobPos.z);
        lastMobPos = p;
        if (local >= WARMUP && local < WARMUP + WINDOW) {
            sumStep += step;
            stepTicks++;
            if (step > 1.0E-4) {
                movingTicks++;
            }
            currentResult.peakStep = Math.max(currentResult.peakStep, step);
        }
        return false;
    }

    private void finishCurrent() {
        if (currentResult == null || stepTicks == 0) {
            return;
        }
        currentResult.meanAll = sumStep / stepTicks;
        currentResult.movingFrac = (double) movingTicks / stepTicks;
        currentResult.poses = String.join("|", poseSet);
        results.add(currentResult);
        LOGGER.info("[SPEEDPROBE] {} attr={} meanAll={} b/t peak={} b/t movingFrac={} ratio(mean/attr)={} "
                        + "ratio(peak/attr)={} poses={}",
                currentResult.label, f(currentResult.attr), f(currentResult.meanAll), f(currentResult.peakStep),
                f(currentResult.movingFrac), f(currentResult.meanAll / currentResult.attr),
                f(currentResult.peakStep / currentResult.attr), currentResult.poses);
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        finishCurrent();
        double botSprint = botDist / Math.max(1, botTicks);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("botSprint:%.4f", botSprint));
        double minRatio = Double.MAX_VALUE;
        double maxRatio = -1;
        for (Result r : results) {
            double ratio = r.peakStep / r.attr;
            minRatio = Math.min(minRatio, ratio);
            maxRatio = Math.max(maxRatio, ratio);
            sb.append(String.format("|%s attr:%.2f mean:%.4f peak:%.4f mv:%.2f pk/attr:%.3f fasterThanBot:%b",
                    r.label, r.attr, r.meanAll, r.peakStep, r.movingFrac, ratio, r.peakStep > botSprint));
        }
        double spread = maxRatio > 0 ? maxRatio / minRatio : -1;
        sb.append(String.format("|ratioSpread:%.2fx|currentFactor:%.3f",
                spread, CombatStats.MOB_ATTR_TO_BLOCKS_PER_TICK));
        LOGGER.info("[SPEEDPROBE] SUMMARY {}", sb);

        boolean ok = results.size() == SUBJECTS.size() && botTicks > 10;
        String expected = "per-mob peak b/t measured for all subjects (diagnostic probe)";
        return ok ? BotTestResult.pass(sb.toString(), expected) : BotTestResult.fail(sb.toString(), expected);
    }

    private static String f(double v) {
        return String.format("%.4f", v);
    }
}
