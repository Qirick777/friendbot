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

    /** Sliding window length. Long enough to average out stop-and-go, short enough to react. */
    public static final int WINDOW_TICKS = 40;
    /** Minimum observed span before the measurement is trusted (cold start below this). */
    public static final int MIN_SPAN_TICKS = 20;
    /** Drop tracking for entities not seen for this long. */
    private static final int STALE_TICKS = 100;

    private record Sample(int tick, Vec3 pos) {
    }

    private static final class Track {
        final Deque<Sample> samples = new ArrayDeque<>();
        int lastSeenTick;
        boolean kiteVerdict;      // sticky (hysteresis); cold start = false (conservative)
        boolean everObserved;
    }

    private final Map<UUID, Track> tracks = new HashMap<>();

    /** Record this tick's position for an entity. */
    public void observe(LivingEntity entity, int tick) {
        Track tr = tracks.computeIfAbsent(entity.getUUID(), k -> new Track());
        tr.lastSeenTick = tick;
        tr.samples.addLast(new Sample(tick, entity.position()));
        while (!tr.samples.isEmpty() && tick - tr.samples.peekFirst().tick() > WINDOW_TICKS) {
            tr.samples.removeFirst();
        }
        if (span(tr) >= MIN_SPAN_TICKS) {
            tr.everObserved = true;
        }
    }

    /** True once the window holds enough span for the measurement to be meaningful. */
    public boolean hasObservation(LivingEntity entity) {
        Track tr = tracks.get(entity.getUUID());
        return tr != null && tr.everObserved && span(tr) >= MIN_SPAN_TICKS;
    }

    /**
     * Effective approach speed in blocks/tick = horizontal displacement between the window ends
     * divided by the elapsed ticks (stationary ticks included). Returns 0 when not yet observable.
     */
    public double effectiveSpeed(LivingEntity entity) {
        Track tr = tracks.get(entity.getUUID());
        if (tr == null || tr.samples.size() < 2) {
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

    public boolean kiteVerdict(LivingEntity entity) {
        Track tr = tracks.get(entity.getUUID());
        return tr != null && tr.kiteVerdict;
    }

    public void setKiteVerdict(LivingEntity entity, boolean verdict) {
        tracks.computeIfAbsent(entity.getUUID(), k -> new Track()).kiteVerdict = verdict;
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
