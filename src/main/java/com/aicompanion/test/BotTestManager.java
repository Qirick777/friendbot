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

    /** Single difficulty every harness runs at (see start()). */
    public static final net.minecraft.world.Difficulty STANDARD_DIFFICULTY =
            net.minecraft.world.Difficulty.NORMAL;

    public static final BotTestManager INSTANCE = new BotTestManager();
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Deque<String> queue = new ArrayDeque<>();

    private BotTest active;
    private BotTestContext ctx;
    private boolean setupDone;

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
        this.ctx = new BotTestContext(server, level, origin, source);
        this.setupDone = false;
        // Every harness runs at ONE difficulty. The bot is a ServerPlayer, so Player.hurt applies
        // difficulty scaling to any DamageScaling.ALWAYS / WHEN_CAUSED_BY_LIVING_NON_PLAYER source
        // (EASY: amount/2+1, NORMAL: amount, HARD: amount*1.5). Harnesses that judge by damage size
        // would otherwise measure different numbers depending on server.properties. NORMAL is the
        // undistorted scale and the one the design's documented values are quoted at (18장 소닉붐
        // 고정 10 = exactly the NORMAL reading; EASY gives 6, HARD 15).
        server.setDifficulty(STANDARD_DIFFICULTY, true);
        LOGGER.info("[BOTTEST] {} START origin={} timeout={}t difficulty={}",
                test.name(), origin, test.timeoutTicks(), STANDARD_DIFFICULTY);
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
                active.setup(ctx);
                setupDone = true;
                ctx.elapsedTicks = 0;
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

    private void finish(BotTestResult r) {
        String name = active.name();
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
