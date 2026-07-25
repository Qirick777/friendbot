package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
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
 * WINDOW-LENGTH selection probe. The observation window must NOT be picked because it makes a
 * particular mob pass — that would be tuning to the answer. This probe measures a property of the
 * measurement itself: for each candidate window it computes many sliding estimates and reports
 * their spread. The window to adopt is the SHORTEST one at which the estimate has converged
 * (coefficient of variation stops falling meaningfully), independent of what verdict follows.
 *
 * <p>Run across mobs with different stop-and-go duty cycles (measured earlier: zombie 1.00,
 * warden 0.80, piglin brute 0.58) — a window that only converges for the smooth walker is not
 * converged.</p>
 */
public class BotWindowVarianceTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int[] WINDOWS = {20, 40, 60, 100, 150, 200};
    private static final int STRIDE = 5;        // sliding-window step for the estimate series
    private static final int PER_MOB = 900;
    private static final int WARMUP = 60;       // spawn + emerge/roar settle
    private static final int START_DIST = 100;
    private static final double STOP_GAP = 6.0; // stop sampling once it is on top of the bot

    private record Subject(String label, EntityType<? extends Mob> type) {
    }

    private static final List<Subject> SUBJECTS = List.of(
            new Subject("zombie", EntityType.ZOMBIE),
            new Subject("warden", EntityType.WARDEN),
            new Subject("piglin_brute", EntityType.PIGLIN_BRUTE));

    private final List<String> report = new ArrayList<>();
    private Mob current;
    private String currentLabel = "";
    private final List<Vec3> samples = new ArrayList<>();
    private int mobsAtPhaseEnd;
    private int maxMobsDuringPhase;

    @Override
    public int[] arenaBounds() {
        return new int[]{-14, 114, -12, 12};
    }

    @Override
    public String name() {
        return "bot_window_variance";
    }

    @Override
    public int timeoutTicks() {
        return PER_MOB * SUBJECTS.size() + 80;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);

        for (int dx = -10; dx <= START_DIST + 10; dx++) {
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
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            return true;
        }
        int t = ctx.elapsedTicks;
        bot.setHealth(bot.getMaxHealth());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(ctx.origin.getX() + 0.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5, 90.0F, 0.0F);

        // BotTestManager delivers the first tick as elapsedTicks=1, so phase boundaries are
        // computed off (t-1); with a raw t%PER_MOB the very first subject was never spawned.
        int tt = t - 1;
        int idx = tt / PER_MOB;
        int local = tt % PER_MOB;
        if (idx >= SUBJECTS.size()) {
            return true;
        }

        if (local == 0) {
            analyse();
            if (current != null) {
                current.discard();
            }
            Subject s = SUBJECTS.get(idx);
            currentLabel = s.label();
            samples.clear();
            maxMobsDuringPhase = 0;
            mobsAtPhaseEnd = 0;
            current = ctx.env.spawn(s.type(), new BlockPos(ctx.origin.getX() + START_DIST,
                    ctx.origin.getY(), ctx.origin.getZ()));
            if (current != null) {
                current.setInvulnerable(true);
                current.setPersistenceRequired();
            }
            LOGGER.info("[WINDOW] phase start mob={} spawned={}", currentLabel, current != null);
            return false;
        }
        if (current == null || !current.isAlive()) {
            return false;
        }
        if (current instanceof Warden w) {
            w.increaseAngerAt(bot);
            w.setAttackTarget(bot);
        } else {
            current.setTarget(bot);
        }
        double gap = Math.hypot(current.getX() - bot.getX(), current.getZ() - bot.getZ());
        if (local >= WARMUP && gap > STOP_GAP) {
            samples.add(current.position());
        }
        // Mob census during the probe. This is a long single run, and "repeats==1 cannot leak" is
        // only true of the between-trial reset — natural spawning accumulates INSIDE a run too, and
        // that is a candidate explanation for the sd non-monotonicity this probe exists to settle.
        if (local % 20 == 0) {
            mobsAtPhaseEnd = ctx.level.getEntitiesOfClass(Mob.class,
                    new net.minecraft.world.phys.AABB(ctx.origin).inflate(64.0)).size();
            maxMobsDuringPhase = Math.max(maxMobsDuringPhase, mobsAtPhaseEnd);
        }
        return false;
    }

    /** For each candidate window: sliding estimates → mean, stddev, coefficient of variation. */
    private void analyse() {
        if (currentLabel.isEmpty()) {
            return;
        }
        if (samples.size() < 40) {
            LOGGER.info("[WINDOW] {} INSUFFICIENT samples={} (mob never produced a usable approach)",
                    currentLabel, samples.size());
            report.add(currentLabel + " insufficient n=" + samples.size());
            return;
        }
        StringBuilder line = new StringBuilder(currentLabel + " n=" + samples.size()
                + " mobsMax=" + maxMobsDuringPhase + " mobsEnd=" + mobsAtPhaseEnd);
        for (int w : WINDOWS) {
            if (samples.size() <= w) {
                line.append(String.format("|W%d:insufficient", w));
                continue;
            }
            List<Double> est = new ArrayList<>();
            for (int i = 0; i + w < samples.size(); i += STRIDE) {
                Vec3 a = samples.get(i);
                Vec3 b = samples.get(i + w);
                est.add(Math.hypot(b.x - a.x, b.z - a.z) / w);
            }
            if (est.size() < 3) {
                line.append(String.format("|W%d:tooFewEstimates", w));
                continue;
            }
            double mean = est.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double var = est.stream().mapToDouble(e -> (e - mean) * (e - mean)).average().orElse(0);
            double sd = Math.sqrt(var);
            double cv = mean > 1.0E-9 ? sd / mean : -1;
            // Overlapping estimates are NOT independent: neighbouring windows share most of their
            // samples, so their spread understates the real one and need not fall monotonically with
            // w. sdIndep uses disjoint windows only — that is the honest number, and its estimate
            // count (nIndep) says how much to trust it.
            List<Double> ind = new ArrayList<>();
            for (int i = 0; i + w < samples.size(); i += w) {
                Vec3 a = samples.get(i);
                Vec3 b = samples.get(i + w);
                ind.add(Math.hypot(b.x - a.x, b.z - a.z) / w);
            }
            String indep = "n/a";
            if (ind.size() >= 3) {
                double m2 = ind.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double v2 = ind.stream().mapToDouble(e -> (e - m2) * (e - m2)).sum() / (ind.size() - 1);
                indep = String.format("%.4f", Math.sqrt(v2));
            }
            line.append(String.format("|W%d: mean=%.4f sd=%.4f cv=%.3f n=%d sdIndep=%s nIndep=%d",
                    w, mean, sd, cv, est.size(), indep, ind.size()));
        }
        LOGGER.info("[WINDOW] {}", line);
        report.add(line.toString());
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        analyse();
        String measured = String.join(" || ", report);
        LOGGER.info("[WINDOW] SUMMARY {}", measured);
        boolean ok = report.size() == SUBJECTS.size();
        return ok ? BotTestResult.pass(measured, "per-window estimate spread for each mob (diagnostic)")
                : BotTestResult.fail(measured, "per-window estimate spread for each mob (diagnostic)");
    }
}
