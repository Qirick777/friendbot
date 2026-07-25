package com.aicompanion.bot.combat;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.perception.TargetInfo;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Signal B — the kiting EXECUTION monitor (design 6.6 신호 분리: A = slow capability estimate,
 * B = few-tick "is the kiting actually working" check).
 *
 * <p>WHY THIS CLASS EXISTS: B used to be computed inside {@link BotRangedCombat#tick}, which runs
 * only in the ranged branch of {@code AICompanionBot.tick} — below reflex, creeper defence, rescue,
 * survival AND melee. Measured consequence (bot_kite_execmon): with a fast mob glued to the bot the
 * protection layer hands the target to melee, the ranged branch never runs, and B is never evaluated
 * at all — {@code execMonitorFired:false} while the bot lost 31.7 HP. A safety net that only exists
 * in one branch is not a safety net. B is therefore evaluated HERE, once per tick, above every
 * branch — the same position the design gives the reflex layer in ch.7 ("판단보다 먼저, 매 틱 최우선").</p>
 *
 * <p>KNOWN LIMITATION, reported not silently patched: the input is {@code d(gap)/dt}, which is
 * negative both when the target closes on the bot and when the BOT closes on the target. Measured in
 * bot_kite_execmon_lat: the monitor latched at {@code ticksToExec:0} during the approach phase,
 * before the pursuer had moved at all, because the bot itself was walking in to its rule-3 band.
 * Restricting B to ticks where the bot is actually trying to open distance is a change to B's
 * definition, not to its wiring, so it is proposed rather than made here.</p>
 */
public class KiteMonitor {

    /** Consecutive closing ticks before B may fire (design intent: a few ticks, not a window). */
    public static final int KITE_FAIL_TICKS = 10;
    /** Per-tick gap change below which the gap counts as closing. */
    public static final double KITE_FAIL_TREND = -0.02;

    private final Map<UUID, int[]> closingTicks = new HashMap<>();
    private final Set<UUID> seen = new HashSet<>();

    /** Called once per tick from the bot's tick, before any branch takes ownership. */
    public void tick(AICompanionBot bot) {
        seen.clear();
        for (TargetInfo t : bot.perception().targets) {
            UUID id = t.entity.getUUID();
            seen.add(id);
            double trend = bot.perception().speeds.gapTrend(t.entity);
            int[] c = closingTicks.computeIfAbsent(id, k -> new int[1]);
            if (trend < KITE_FAIL_TREND) {
                c[0]++;
            } else {
                c[0] = 0;
            }
        }
        closingTicks.keySet().retainAll(seen);
    }

    /** Signal B: the gap to this target has been shrinking for KITE_FAIL_TICKS straight. */
    public boolean failing(LivingEntity target) {
        int[] c = target == null ? null : closingTicks.get(target.getUUID());
        return c != null && c[0] >= KITE_FAIL_TICKS;
    }

    /** Diagnostic: how many consecutive closing ticks this target currently has. */
    public int closingTicks(LivingEntity target) {
        int[] c = target == null ? null : closingTicks.get(target.getUUID());
        return c == null ? 0 : c[0];
    }

    public void reset() {
        closingTicks.clear();
    }
}
