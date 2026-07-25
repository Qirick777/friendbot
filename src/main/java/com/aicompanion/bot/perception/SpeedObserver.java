package com.aicompanion.bot.perception;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Observed effective approach speed per target, in blocks/tick (design 6.6 "관측 기반").
 *
 * <p>The measured quantity is <b>displacement ÷ elapsed ticks over the window, stationary ticks
 * included</b> — not a peak step and not a moving-ticks-only mean. A mob that sprints for 4 ticks
 * and stands for 1 closes ground at 80% of its top speed, and closing ground is exactly what rule 1
 * asks about. (The earlier 0.157 attribute conversion came from a moving-only mean taken while a
 * warden was still locked in its emerge/roar animation; both mistakes are avoided here.)</p>
 *
 * <p>Also holds the sticky kite verdict so rule 1 can apply hysteresis without the rule engine
 * itself becoming stateful.</p>
 */
public class SpeedObserver {

    /**
     * Sliding window length, chosen by CONVERGENCE, not by the verdict it produces
     * (bot_window_variance). Criterion, declared before looking at outcomes: the shortest window at
     * which measurement noise can no longer flip the rule-1 comparison, i.e. mean ± 3σ stays on one
     * side of the bot's sprint speed, for every mob tested (duty cycles 1.00 / 0.80 / 0.58).
     * Warden — the only subject anywhere near the threshold — is unstable at 20 and 40
     * (±0.054, ±0.027) and stable from 60 (±0.004); zombie and brute are stable from 20.
     */
    public static final int WINDOW_TICKS = 60;
    /** Full window required before the estimate is trusted; below it the cold-start prior applies. */
    public static final int MIN_SPAN_TICKS = 60;
    /** Drop tracking for entities not seen for this long. */
    private static final int STALE_TICKS = 100;
    /** Below this per-tick bot displacement the bot counts as pinned (observation invalid). */
    public static final double BOT_MOVING_MIN = 0.02;
    /** Within this gap the target is glued to the bot and has no reason to travel. */
    public static final double GLUED_GAP = 3.0;

    private record Sample(int tick, Vec3 pos) {
    }

    private static final class Track {
        final Deque<Sample> samples = new ArrayDeque<>();
        int lastSeenTick;
        boolean kiteVerdict;      // sticky (hysteresis); cold start = false (conservative)
        boolean settled;          // a real verdict has been established from a valid observation
        boolean everObserved;
        double lastValidSpeed = -1;   // last capability estimate taken under a VALID observation
        // --- execution monitor (signal B): gap trend while the bot is actually kiting ---
        double lastGap = -1;
        double gapTrend;          // + = opening the distance, − = being closed down
    }

    private final Map<UUID, Track> tracks = new HashMap<>();

    /**
     * Record this tick's position for an entity.
     *
     * <p>OBSERVATION VALIDITY: the capability estimate is absolute target displacement, which only
     * reveals what the target CAN do when the situation lets it move. If the bot is pinned (its own
     * displacement ≈ 0) or the target is already glued to the bot, the target has no reason to
     * travel and the sample says nothing about its speed. Such ticks are NOT folded into the
     * estimate — an invalid observation must never manufacture the convenient answer
     * ({@code canKite=true}). The last valid estimate is used instead.</p>
     */
    public void observe(LivingEntity entity, int tick, double botDisplacementPerTick, double gapToBot) {
        Track tr = tracks.computeIfAbsent(entity.getUUID(), k -> new Track());
        // Signal B — execution monitor: how the gap is trending, regardless of validity.
        if (tr.lastGap >= 0) {
            tr.gapTrend = gapToBot - tr.lastGap;
        }
        tr.lastGap = gapToBot;

        boolean botPinned = botDisplacementPerTick < BOT_MOVING_MIN;
        boolean glued = gapToBot < GLUED_GAP;
        if (botPinned && glued) {
            tr.lastSeenTick = tick;
            tr.samples.clear();   // stale: resume measuring only once the situation is informative
            return;
        }
        tr.lastSeenTick = tick;
        tr.samples.addLast(new Sample(tick, entity.position()));
        while (!tr.samples.isEmpty() && tick - tr.samples.peekFirst().tick() > WINDOW_TICKS) {
            tr.samples.removeFirst();
        }
        if (span(tr) >= MIN_SPAN_TICKS) {
            tr.everObserved = true;
            tr.lastValidSpeed = rawSpeed(tr);
        }
    }

    /** True once the window holds enough span for the measurement to be meaningful. */
    public boolean hasObservation(LivingEntity entity) {
        Track tr = tracks.get(entity.getUUID());
        // everObserved keeps the estimate alive across invalid stretches (we hold the last valid
        // value); without it a pinned bot would fall back to the cold start and re-latch.
        return tr != null && tr.everObserved;
    }

    /**
     * Effective approach speed in blocks/tick = horizontal displacement between the window ends
     * divided by the elapsed ticks (stationary ticks included). Returns 0 when not yet observable.
     */
    public double effectiveSpeed(LivingEntity entity) {
        Track tr = tracks.get(entity.getUUID());
        if (tr == null) {
            return 0.0;
        }
        if (span(tr) >= MIN_SPAN_TICKS) {
            return rawSpeed(tr);
        }
        // Window not (re)filled — fall back to the last estimate taken under a valid observation.
        return tr.lastValidSpeed >= 0 ? tr.lastValidSpeed : 0.0;
    }

    private static double rawSpeed(Track tr) {
        if (tr.samples.size() < 2) {
            return 0.0;
        }
        int sp = span(tr);
        if (sp <= 0) {
            return 0.0;
        }
        Vec3 a = tr.samples.peekFirst().pos();
        Vec3 b = tr.samples.peekLast().pos();
        return Math.hypot(b.x - a.x, b.z - a.z) / sp;
    }

    /** Signal B — per-tick gap change: positive means the bot is successfully opening distance. */
    public double gapTrend(LivingEntity entity) {
        Track tr = tracks.get(entity.getUUID());
        return tr == null ? 0.0 : tr.gapTrend;
    }

    public boolean isSettled(LivingEntity entity) {
        Track tr = tracks.get(entity.getUUID());
        return tr != null && tr.settled;
    }

    public boolean kiteVerdict(LivingEntity entity) {
        Track tr = tracks.get(entity.getUUID());
        return tr != null && tr.kiteVerdict;
    }

    public void setKiteVerdict(LivingEntity entity, boolean verdict) {
        Track tr = tracks.computeIfAbsent(entity.getUUID(), k -> new Track());
        tr.kiteVerdict = verdict;
        if (tr.everObserved) {
            tr.settled = true;
        }
    }

    /** Forget entities we have not seen for a while (keeps the map bounded). */
    public void prune(int tick) {
        Iterator<Map.Entry<UUID, Track>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            if (tick - it.next().getValue().lastSeenTick > STALE_TICKS) {
                it.remove();
            }
        }
    }

    private static int span(Track tr) {
        if (tr.samples.size() < 2) {
            return 0;
        }
        return tr.samples.peekLast().tick() - tr.samples.peekFirst().tick();
    }
}
