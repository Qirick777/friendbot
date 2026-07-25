package com.aicompanion.test;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Drives one {@link BotTest} at a time on the server tick loop.
 *
 * <p>Flow per tick (END phase): if idle, pull the next queued test and start it;
 * otherwise run setup-once, then observe, then judge on ready or timeout — printing
 * exactly one {@code [BOTTEST] <name> PASS/FAIL measured=<..> expected=<..>} line.</p>
 */
public final class BotTestManager {

    /**
     * Two-tier statistical standard (transition decision). Judging every probabilistic harness at
     * the final gate would need ~100 trials each — over a thousand runs across the suite — so
     * development uses a cheaper screening tier that still catches real functional shortfalls, and
     * the strict gate is applied once, at T5.6.
     */
    public static final int SCREENING_TRIALS = 30;
    /** Screening passes at (spec threshold − this). Only clear functional defects fail here. */
    public static final double SCREENING_SLACK = 0.15;
    /** Final gate (T5.6): full trials, no slack. */
    public static final int FINAL_TRIALS = 100;
    /** While true the suite judges at the screening tier. */
    public static final boolean SCREENING_MODE = true;

    /** Single difficulty every harness runs at (see start()). */
    public static final net.minecraft.world.Difficulty STANDARD_DIFFICULTY =
            net.minecraft.world.Difficulty.NORMAL;

    public static final BotTestManager INSTANCE = new BotTestManager();
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Deque<String> queue = new ArrayDeque<>();

    private BotTest active;
    private BotTestContext ctx;
    private boolean setupDone;

    // --- statistical repetition (audit item 4): trials run in-process, one aggregate verdict ---
    private String testName;
    private int trialIndex;
    private int trialsPassed;
    private final java.util.List<String> trialLines = new java.util.ArrayList<>();
    /** Trial isolation contract: trial 1's post-setup state is the baseline every later trial must match. */
    private TrialCanary.Snapshot baseline;
    private String canaryDiff = "";

    /**
     * World time is standardised the same way difficulty is. Left alone it advances every tick, so a
     * harness that does not set it would trip the canary every trial for a reason that is not a leak;
     * pinned here, any drift the canary reports IS a leak.
     */
    public static final long STANDARD_DAYTIME = 18000L;

    private BotTestManager() {
    }

    public boolean isRunning() {
        return active != null;
    }

    @Nullable
    public String activeName() {
        return active == null ? null : active.name();
    }

    /** Queue a test to run when the manager next becomes idle (used by headless auto-run). */
    public synchronized void enqueue(String name) {
        queue.add(name);
    }

    /**
     * Start a test immediately. Returns false if unknown or one is already running.
     * Setup runs on the following tick (on the server thread) so it is never mid-tick.
     */
    public synchronized boolean start(String name, MinecraftServer server, @Nullable CommandSourceStack source) {
        if (active != null) {
            LOGGER.warn("[BOTTEST] cannot start '{}' — '{}' already running", name, active.name());
            return false;
        }
        BotTest test = BotTestRegistry.create(name);
        if (test == null) {
            LOGGER.warn("[BOTTEST] unknown test '{}'", name);
            return false;
        }
        ServerLevel level = source != null ? source.getLevel() : server.overworld();
        BlockPos origin = source != null ? BlockPos.containing(source.getPosition()) : level.getSharedSpawnPos();
        this.active = test;
        this.baseline = null;
        this.canaryDiff = "";
        this.testName = name;
        this.trialIndex = 0;
        this.trialsPassed = 0;
        this.trialLines.clear();
        this.ctx = new BotTestContext(server, level, origin, source);
        this.setupDone = false;
        // Every harness runs at ONE difficulty. The bot is a ServerPlayer, so Player.hurt applies
        // difficulty scaling to any DamageScaling.ALWAYS / WHEN_CAUSED_BY_LIVING_NON_PLAYER source
        // (EASY: amount/2+1, NORMAL: amount, HARD: amount*1.5). Harnesses that judge by damage size
        // would otherwise measure different numbers depending on server.properties. NORMAL is the
        // undistorted scale and the one the design's documented values are quoted at (18장 소닉붐
        // 고정 10 = exactly the NORMAL reading; EASY gives 6, HARD 15).
        server.setDifficulty(STANDARD_DIFFICULTY, true);
        // Standardise the WORLD, not just the difficulty. The canary's first run showed the arena
        // was never static: 5 -> 38 mobs accumulated from natural spawning during a single harness,
        // and random block ticks (grass, leaves, fluids) drifted terrain between trials. Both make
        // trials non-independent, and both are environment, not behaviour under test.
        for (net.minecraft.server.level.ServerLevel lv : server.getAllLevels()) {
            lv.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING)
                    .set(false, server);
            lv.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT)
                    .set(false, server);
            lv.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE)
                    .set(false, server);
            lv.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK)
                    .set(false, server);
            lv.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING)
                    .set(0, server);
        }
        LOGGER.info("[BOTTEST] {} START origin={} timeout={}t difficulty={} repeats={} threshold={}",
                test.name(), origin, test.timeoutTicks(), STANDARD_DIFFICULTY,
                test.repeats(), test.successThreshold());
        return true;
    }

    /** Called every server tick (END phase). */
    public synchronized void serverTick(MinecraftServer server) {
        if (active == null) {
            String next = queue.poll();
            if (next != null) {
                start(next, server, null);
            }
            return;
        }
        try {
            if (!setupDone) {
                ctx.level.setDayTime(STANDARD_DAYTIME);
                if (baseline == null) {
                    // Trial 1 must start from the same swept arena as trials 2..n, or the baseline
                    // records world-gen leftovers that no later trial can reproduce.
                    ctx.env.clearEntities(ctx.origin, TrialCanary.ENTITY_RADIUS);
                }
                active.setup(ctx);
                setupDone = true;
                ctx.elapsedTicks = 0;
                // Isolation contract. Trial 1 defines the baseline; every later trial must start
                // from the same state, checked with values instead of assumed.
                // Building an arena over natural terrain drops item entities on the first trial and
                // over flat stone on none of the others. That debris is a by-product of construction,
                // not harness state, so it is swept before the snapshot on every trial alike.
                TrialCanary.sweepConstructionDebris(ctx.level, ctx.origin);
                TrialCanary.Snapshot now = TrialCanary.capture(
                        ctx.level, ctx.origin, com.aicompanion.bot.BotManager.current());
                if (baseline == null) {
                    baseline = now;
                    canaryDiff = "";
                    LOGGER.info("[BOTTEST] canary baseline mobs={} items={} proj={} blocks={} bot=[{}] user=[{}]",
                            now.mobs(), now.items(), now.projectiles(), now.nonAirBlocks(),
                            now.botState(), now.userState());
                } else {
                    canaryDiff = TrialCanary.diff(baseline, now);
                    if (!canaryDiff.isEmpty()) {
                        LOGGER.warn("[BOTTEST] canary MISMATCH before trial {}: {}",
                                trialIndex + 1, canaryDiff);
                        finish(new BotTestResult(false, "canary:MISMATCH(" + canaryDiff + ")",
                                "trial starts from the same state as trial 1 (isolation contract)"));
                        return;
                    }
                }
                return; // observe starting next tick
            }
            ctx.elapsedTicks++;
            if (active.tick(ctx)) {
                finish(active.judge(ctx));
                return;
            }
            if (ctx.elapsedTicks >= active.timeoutTicks()) {
                finish(new BotTestResult(false,
                        "timeout@" + ctx.elapsedTicks + "t",
                        "condition within " + active.timeoutTicks() + "t"));
            }
        } catch (Throwable t) {
            LOGGER.error("[BOTTEST] {} crashed", active.name(), t);
            finish(new BotTestResult(false, "exception:" + t.getClass().getSimpleName(), "no exception"));
        }
    }

    /** Return the bot to a clean state so one trial cannot contaminate the next. */
    /**
     * The second fake player leaked exactly like the bot did: TestUser.spawn returns the live user
     * untouched, so bot_escape_ride carried it 7.59 blocks in trial 1 and trials 2-3 then ran with a
     * stale user out of trigger range (userMoved:0.00, twice, identically).
     */
    private void resetUser() {
        net.minecraft.server.level.ServerPlayer user = TestUser.current();
        if (user == null || !user.isAlive()) {
            return;
        }
        user.stopRiding();
        for (net.minecraft.world.entity.Entity p : new java.util.ArrayList<>(user.getPassengers())) {
            p.stopRiding();
        }
        user.getInventory().clearContent();
        user.setInvulnerable(false);
        user.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        user.fallDistance = 0.0F;
        user.getFoodData().setFoodLevel(20);
        user.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                .setBaseValue(20.0);
        user.setHealth(20.0F);
        user.moveTo(ctx.origin.getX() + 0.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5, 0.0F, 0.0F);
    }

    private void resetBot() {
        com.aicompanion.bot.AICompanionBot bot = com.aicompanion.bot.BotManager.current();
        if (bot == null) {
            return;
        }
        bot.planner().stop();
        bot.mover().stop();
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();
        bot.kiteMonitor().reset();
        bot.survival().reset();
        bot.getInventory().clearContent();
        bot.stopRiding();
        bot.getPassengers().forEach(net.minecraft.world.entity.Entity::stopRiding);
        bot.setInvulnerable(false);
        bot.setSprinting(false);
        bot.setShiftKeyDown(false);
        bot.zza = 0.0F;
        bot.xxa = 0.0F;
        bot.setJumping(false);
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.fallDistance = 0.0F;
        bot.getFoodData().setFoodLevel(20);
        bot.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                .setBaseValue(20.0);
        bot.setHealth(bot.getMaxHealth());
        bot.moveTo(ctx.origin.getX() + 0.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5, 0.0F, 0.0F);
    }

    /** 95% Wilson score interval lower bound for k successes out of n. */
    private static double wilsonLowerBound(int k, int n) {
        if (n <= 0) {
            return 0.0;
        }
        double z = 1.96;
        double p = (double) k / n;
        double z2 = z * z;
        double denom = 1.0 + z2 / n;
        double centre = p + z2 / (2 * n);
        double margin = z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n));
        return Math.max(0.0, (centre - margin) / denom);
    }

    private void finish(BotTestResult r) {
        String name = active.name();
        int repeats = Math.max(1, active.repeats());

        if (repeats > 1) {
            trialIndex++;
            if (r.pass()) {
                trialsPassed++;
            }
            trialLines.add(String.format("t%d:%s(%s)", trialIndex, r.pass() ? "P" : "F", r.measured()));
            LOGGER.info("[BOTTEST] {} trial {}/{} {} measured={}",
                    name, trialIndex, repeats, r.pass() ? "PASS" : "FAIL", r.measured());
            if (trialIndex < repeats) {
                // Next trial in the same server: trials must be independent. Wiping leftover
                // entities is not enough — the BOT persists across trials, so its controller state
                // (path, movement target, combat target, mount, inventory, health) leaks into the
                // next trial. bot_path_reach exposed this: trial 1 passed and trials 2-3 failed at
                // the identical stuck coordinate.
                ctx.env.clearEntities(ctx.origin, TrialCanary.ENTITY_RADIUS);
                resetBot();
                resetUser();
                BotTest next = BotTestRegistry.create(testName);
                if (next != null) {
                    active = next;
                    setupDone = false;
                    ctx.elapsedTicks = 0;
                    return;
                }
            }
            double rate = (double) trialsPassed / trialIndex;
            // Judge on the 95% Wilson score lower bound, not the raw rate: landing exactly on the
            // threshold with a handful of trials is sampling luck, not evidence.
            double lb = wilsonLowerBound(trialsPassed, trialIndex);
            // A threshold of 1.00 is a DETERMINISTIC requirement ("no trial may fail"), not a rate
            // estimate — and no finite-sample Wilson bound ever reaches 1.0, so applying the interval
            // there would fail every such harness forever. Interval judging applies to the
            // probabilistic harnesses (threshold < 1).
            boolean deterministic = active.successThreshold() >= 1.0 - 1.0E-9;
            double effective = deterministic ? active.successThreshold()
                    : active.successThreshold() - (SCREENING_MODE ? SCREENING_SLACK : 0.0);
            boolean ok = deterministic ? trialsPassed == trialIndex
                    : lb >= effective - 1.0E-9;
            String measured = String.format(
                    "trials:%d,passed:%d,successRate:%.2f,wilson95Lower:%.3f,canary:%s|%s",
                    trialIndex, trialsPassed, rate, lb,
                    trialIndex > 1 ? "OK" : "n/a", String.join("|", trialLines));
            String expected = deterministic
                    ? String.format("all %d trials pass (deterministic requirement) — %s",
                            repeats, r.expected())
                    : String.format(
                            "95%% Wilson lower bound >= %.2f (%s tier: spec %.2f) over %d trials — %s",
                            effective, SCREENING_MODE ? "screening" : "final",
                            active.successThreshold(), repeats, r.expected());
            r = new BotTestResult(ok, measured, expected);
        }

        String verdict = r.pass() ? "PASS" : "FAIL";
        // Authoritative judgment line (R.2 format).
        LOGGER.info("[BOTTEST] {} {} measured={} expected={}", name, verdict, r.measured(), r.expected());
        if (ctx.source != null) {
            final String msg = "[BOTTEST] " + name + " " + verdict + " measured=" + r.measured();
            ctx.source.sendSuccess(() -> Component.literal(msg), false);
        }
        active = null;
        ctx = null;
        setupDone = false;
    }
}
