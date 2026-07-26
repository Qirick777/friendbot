package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.combat.CombatRules;
import com.aicompanion.bot.combat.CombatStats;
import com.aicompanion.bot.perception.Perception;
import com.aicompanion.bot.perception.TargetInfo;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * BETWEEN-RUN distribution of the rule-1 cold-start observation.
 *
 * <p>Why the within-run spread was the wrong input: rule 1's cold-start verdict is taken exactly
 * ONCE per engagement, at the tick the first window fills (measured: bot_kite_band_latch
 * ticksToVerdict 61, bot_kite_recover ticksToRecover 62). The noise that decides that single draw is
 * therefore the ENGAGEMENT-TO-ENGAGEMENT component, not the sliding spread inside one long chase.
 * Deeper sampling inside a run estimates the latter more precisely and leaves the former untouched.
 * This harness measures the former: N independent engagements, ONE observation each — the value the
 * verdict is actually taken from.</p>
 *
 * <p>Independence: each engagement gets a fresh warden (fresh UUID → fresh observer track), a random
 * spawn bearing, a random spawn distance OUTSIDE {@link Perception#PERCEPTION_RANGE} so observation
 * begins on a warden already in steady chase (not mid emerge/roar), and randomly scattered pillars
 * so the approach route is not the same clean straight line every time.</p>
 *
 * <p>Reported: the distribution (mean, sd, min, max), how many draws land on each side of the rule-1
 * decision line, and the per-engagement route curvature (path length ÷ net displacement) — the
 * candidate explanation for why one live-speed trial read 0.2236 while two others read 0.2658.</p>
 */
public class BotColdStartDistTest implements BotTest {


    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int ENGAGEMENTS = 30;
    private static final int PER_ENGAGEMENT = 400;
    private static final int SPAWN_MIN = 28;      // > PERCEPTION_RANGE(24): it walks in
    private static final int SPAWN_MAX = 40;
    private static final int PILLARS = 6;
    private static final int ARENA = 48;

    private record Draw(double observed, boolean kite, int spawnDist, double gapAtObsStart,
                        int ticksToVerdict, double pathRatio, int mobsPresent) {
    }

    private final List<Draw> draws = new ArrayList<>();
    /**
     * Seed. The fixed-seed instance is a REGRESSION set: re-running it draws the same 30 geometries,
     * which is what made it a clean before/after contamination test (sd 0.0228 -> 0.0205 across the
     * contract boundary) — but it is NOT new tail information. Re-running it will report 0/30 over
     * the decision line forever because it is the same 30 geometries every time. Estimating how
     * often a warden actually crosses the line needs fresh geometry, which is what the tail variants
     * supply.
     */
    protected long seed() {
        return 20260725L;
    }

    /** "regression" (fixed geometry, before/after comparison) or "tail" (fresh geometry). */
    protected String purpose() {
        return "regression";
    }

    private final Random rng = new Random(seed());

    private Warden warden;
    private int engagement = -1;
    private int engagementStart;
    private int obsStartTick = -1;
    private double gapAtObsStart = -1;
    private int spawnDist;
    private boolean recorded;
    private Vec3 pathFrom;
    private Vec3 lastPos;
    private double pathSum;

    @Override
    public int[] arenaBounds() {
        return new int[]{-52, 52, -52, 52};
    }

    @Override
    public String name() {
        return "bot_coldstart_dist";
    }

    /** O-5(2): 봇 자율 이동 활성 여부를 전제로 명시한다. */
    @Override
    public String scenarioSpec() {
        return "봇 최대체력 400 + 매 틱 만피 회복, 워든 setInvulnerable(true). 콜드스타트 첫 판정 거리를 재는 전제. 유저 없음(TestUser.spawn 미호출) → Perception.java:131 level.players()가 봇 외 플레이어를 찾지 못해 perception().user==null → BotIdle.java:87-89 즉시 반환. 16장 자율 이동 비활성이며 판정 라인의 idleCommandedTicks:0로 값 확인된다(O-5(2)).";
    }

    @Override
    public int timeoutTicks() {
        return ENGAGEMENTS * PER_ENGAGEMENT + 200;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);

        for (int dx = -ARENA; dx <= ARENA; dx++) {
            for (int dz = -ARENA; dz <= ARENA; dz++) {
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
        bot.setInvulnerable(false);   // Warden.canTargetEntity rejects invulnerable targets
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();
        draws.clear();
        engagement = -1;
        LOGGER.info("[COLDSTART] seed={} purpose={} engagements={}", seed(), purpose(), ENGAGEMENTS);
    }

    private void startEngagement(BotTestContext ctx, int t) {
        AICompanionBot bot = BotManager.current();
        if (warden != null) {
            warden.discard();
            warden = null;
        }
        BlockPos o = ctx.origin;
        // Fresh terrain: clear, then scatter pillars so the route differs engagement to engagement.
        for (int dx = -ARENA; dx <= ARENA; dx++) {
            for (int dz = -ARENA; dz <= ARENA; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (int i = 0; i < PILLARS; i++) {
            int px = o.getX() + rng.nextInt(2 * ARENA - 8) - (ARENA - 4);
            int pz = o.getZ() + rng.nextInt(2 * ARENA - 8) - (ARENA - 4);
            if (Math.abs(px - o.getX()) < 5 && Math.abs(pz - o.getZ()) < 5) {
                continue;   // never wall the bot in
            }
            ctx.level.setBlockAndUpdate(new BlockPos(px, o.getY(), pz), Blocks.STONE.defaultBlockState());
            ctx.level.setBlockAndUpdate(new BlockPos(px, o.getY() + 1, pz), Blocks.STONE.defaultBlockState());
        }

        spawnDist = SPAWN_MIN + rng.nextInt(SPAWN_MAX - SPAWN_MIN + 1);
        double bearing = rng.nextDouble() * Math.PI * 2.0;
        int sx = o.getX() + (int) Math.round(Math.cos(bearing) * spawnDist);
        int sz = o.getZ() + (int) Math.round(Math.sin(bearing) * spawnDist);
        warden = ctx.env.spawn(EntityType.WARDEN, new BlockPos(sx, o.getY(), sz));
        if (warden != null) {
            warden.setInvulnerable(true);
            warden.increaseAngerAt(bot);
            warden.setAttackTarget(bot);
        }
        obsStartTick = -1;
        gapAtObsStart = -1;
        recorded = false;
        pathFrom = null;
        lastPos = null;
        pathSum = 0;
        engagementStart = t;
        LOGGER.info("[COLDSTART] engagement {} spawnDist={} bearing={}",
                engagement, spawnDist, String.format("%.2f", Math.toDegrees(bearing)));
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

        int tt = t - 1;
        int idx = tt / PER_ENGAGEMENT;
        int local = tt % PER_ENGAGEMENT;
        if (idx >= ENGAGEMENTS) {
            return true;
        }
        if (local == 0) {
            engagement = idx;
            startEngagement(ctx, t);
            return false;
        }
        if (warden == null || !warden.isAlive()) {
            return false;
        }
        if (local % 40 == 0) {
            warden.increaseAngerAt(bot);
            warden.setAttackTarget(bot);
        }
        if (recorded) {
            return false;   // one draw per engagement; wait out the slot
        }

        double gap = Math.hypot(warden.getX() - bot.getX(), warden.getZ() - bot.getZ());
        boolean tracked = false;
        for (TargetInfo ti : bot.perception().targets) {
            if (ti.entity != warden) {
                continue;
            }
            tracked = true;
            if (obsStartTick < 0) {
                obsStartTick = t;
                gapAtObsStart = gap;
                pathFrom = warden.position();
                lastPos = warden.position();
            }
            if (lastPos != null) {
                pathSum += Math.hypot(warden.getX() - lastPos.x, warden.getZ() - lastPos.z);
            }
            lastPos = warden.position();
            // THE draw: the first tick rule 1 has a real observation is the tick the cold-start
            // verdict is taken. That single value is what decides the whole engagement.
            if (ti.speedObserved) {
                double net = pathFrom == null ? 0
                        : Math.hypot(warden.getX() - pathFrom.x, warden.getZ() - pathFrom.z);
                double ratio = net > 1.0E-6 ? pathSum / net : -1;
                boolean kite = CombatRules.canKite(ti, CombatStats.of(bot).sprintSpeed);
                // Mob census at the moment of the draw. The previous run had natural spawning on and
                // never swept between engagements, so "did crowding bias this?" was inferential.
                // Recorded here, it is a value.
                int mobsNow = ctx.level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                        new net.minecraft.world.phys.AABB(ctx.origin).inflate(64.0)).size();
                draws.add(new Draw(ti.observedSpeed, kite, spawnDist, gapAtObsStart,
                        t - obsStartTick, ratio, mobsNow));
                recorded = true;
                LOGGER.info("[COLDSTART] #{} observed={} ({}% of sprint) canKite={} spawnDist={} "
                                + "gapAtObsStart={} ticks={} pathRatio={} mobs={}",
                        draws.size(), String.format("%.4f", ti.observedSpeed),
                        String.format("%.1f", 100.0 * ti.observedSpeed / CombatStats.BOT_SPRINT_SPEED),
                        kite, spawnDist, String.format("%.1f", gapAtObsStart), t - obsStartTick,
                        String.format("%.3f", ratio), mobsNow);
            }
            break;
        }
        if (!tracked) {
            lastPos = null;   // out of perception range again: the path sum restarts with the track
        }
        return false;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        int n = draws.size();
        if (n < 3) {
            return BotTestResult.fail("draws:" + n, "at least 3 independent cold-start draws");
        }
        double sprint = CombatStats.BOT_SPRINT_SPEED;
        double enter = sprint * CombatRules.KITE_ENTER_RATIO;
        double mean = draws.stream().mapToDouble(Draw::observed).average().orElse(0);
        double var = draws.stream().mapToDouble(d -> (d.observed() - mean) * (d.observed() - mean))
                .sum() / (n - 1);
        double sd = Math.sqrt(var);
        double min = draws.stream().mapToDouble(Draw::observed).min().orElse(0);
        double max = draws.stream().mapToDouble(Draw::observed).max().orElse(0);
        long kiteTrue = draws.stream().filter(Draw::kite).count();
        long aboveEnter = draws.stream().filter(d -> d.observed() >= enter).count();

        // Does the spawn distance explain the spread, or does the route curvature?
        double meanDist = draws.stream().mapToDouble(Draw::spawnDist).average().orElse(0);
        double meanRatio = draws.stream().filter(d -> d.pathRatio() > 0)
                .mapToDouble(Draw::pathRatio).average().orElse(0);
        double covDist = 0;
        double varDist = 0;
        double covRatio = 0;
        double varRatio = 0;
        int nRatio = 0;
        for (Draw d : draws) {
            covDist += (d.spawnDist() - meanDist) * (d.observed() - mean);
            varDist += (d.spawnDist() - meanDist) * (d.spawnDist() - meanDist);
            if (d.pathRatio() > 0) {
                covRatio += (d.pathRatio() - meanRatio) * (d.observed() - mean);
                varRatio += (d.pathRatio() - meanRatio) * (d.pathRatio() - meanRatio);
                nRatio++;
            }
        }
        double meanMobs = draws.stream().mapToDouble(Draw::mobsPresent).average().orElse(0);
        double covMobs = 0;
        double varMobs = 0;
        double covOrder = 0;
        double varOrder = 0;
        double meanOrder = (draws.size() + 1) / 2.0;
        for (int i = 0; i < draws.size(); i++) {
            Draw d = draws.get(i);
            covMobs += (d.mobsPresent() - meanMobs) * (d.observed() - mean);
            varMobs += (d.mobsPresent() - meanMobs) * (d.mobsPresent() - meanMobs);
            covOrder += ((i + 1) - meanOrder) * (d.observed() - mean);
            varOrder += ((i + 1) - meanOrder) * ((i + 1) - meanOrder);
        }
        double rMobs = (varMobs > 1.0E-9 && var > 1.0E-9)
                ? covMobs / Math.sqrt(varMobs * var * (n - 1)) : 0;
        double rOrder = (varOrder > 1.0E-9 && var > 1.0E-9)
                ? covOrder / Math.sqrt(varOrder * var * (n - 1)) : 0;
        int maxMobs = draws.stream().mapToInt(Draw::mobsPresent).max().orElse(0);
        int minMobs = draws.stream().mapToInt(Draw::mobsPresent).min().orElse(0);
        double rDist = (varDist > 1.0E-9 && var > 1.0E-9)
                ? covDist / Math.sqrt(varDist * var * (n - 1)) : 0;
        double rRatio = (varRatio > 1.0E-9 && var > 1.0E-9 && nRatio > 2)
                ? covRatio / Math.sqrt(varRatio * var * (n - 1)) : 0;

        // Stability, judged on the RIGHT quantity: the distance from the mean to the decision line
        // must exceed the noise that actually decides a single draw (3σ between engagements).
        double distToLine = Math.abs(mean - enter);
        boolean stable = distToLine >= 3.0 * sd;

        StringBuilder per = new StringBuilder();
        for (Draw d : draws) {
            per.append(String.format("|%.4f/%b/d%d/r%.2f/m%d", d.observed(), d.kite(), d.spawnDist(),
                    d.pathRatio(), d.mobsPresent()));
        }
        LOGGER.info("[COLDSTART] SUMMARY n={} mean={} sd={} min={} max={} enter={} 3sd={} "
                        + "distToLine={} stable={} rDist={} rRatio={}",
                n, String.format("%.4f", mean), String.format("%.4f", sd), String.format("%.4f", min),
                String.format("%.4f", max), String.format("%.4f", enter),
                String.format("%.4f", 3 * sd), String.format("%.4f", distToLine), stable,
                String.format("%.2f", rDist), String.format("%.2f", rRatio));
        LOGGER.info("[COLDSTART] DRAWS{}", per);

        String measured = String.format(
                "n:%d,mean:%.4f(%.1f%%),sdBetweenRuns:%.4f,min:%.4f(%.1f%%),max:%.4f(%.1f%%),"
                        + "enterLine:%.4f,distMeanToLine:%.4f,3sd:%.4f,stable:%b,"
                        + "canKiteTrue:%d/%d,aboveEnterLine:%d,rSpawnDist:%.2f,rPathRatio:%.2f,"
                        + "mobs:%d~%d,rMobs:%.2f,rTrialOrder:%.2f,seed:%d,purpose:%s",
                n, mean, 100.0 * mean / sprint, sd, min, 100.0 * min / sprint, max,
                100.0 * max / sprint, enter, distToLine, 3 * sd, stable, kiteTrue, n, aboveEnter,
                rDist, rRatio, minMobs, maxMobs, rMobs, rOrder, seed(), purpose());
        String expected = "distribution of the ONE observation each engagement's cold-start verdict "
                + "is taken from; stable iff |mean - decision line| >= 3sd(between engagements)";
        return stable ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    /** Fresh geometry for tail estimation. Same procedure, different draws. */
    public static class Tail1 extends BotColdStartDistTest {
        @Override
        protected long seed() {
            return 11L;
        }

        @Override
        protected String purpose() {
            return "tail";
        }

        @Override
        public String name() {
            return "bot_coldstart_tail1";
        }
    }

    public static class Tail2 extends BotColdStartDistTest {
        @Override
        protected long seed() {
            return 22L;
        }

        @Override
        protected String purpose() {
            return "tail";
        }

        @Override
        public String name() {
            return "bot_coldstart_tail2";
        }
    }

    public static class Tail3 extends BotColdStartDistTest {
        @Override
        protected long seed() {
            return 33L;
        }

        @Override
        protected String purpose() {
            return "tail";
        }

        @Override
        public String name() {
            return "bot_coldstart_tail3";
        }
    }
}
