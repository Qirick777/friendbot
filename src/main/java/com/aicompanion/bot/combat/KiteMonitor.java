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

    /** Consecutive failing ticks before B may fire (design intent: a few ticks, not a window). */
    public static final int KITE_FAIL_TICKS = 10;

    /**
     * Opening rate below which "the bot is trying to open distance" counts as NOT WORKING, in b/t.
     *
     * <p>Derived, not tuned: rule 1 already declares a band inside which it treats a speed comparison
     * as indifferent — {@code 1 - KITE_ENTER_RATIO} = 3% of the bot's sprint. B reuses that same
     * indifference width as an absolute rate. In words: if the bot intends to open distance but the
     * gap grows slower than rule 1's own indifference band, the verdict rule 1 reached is not being
     * borne out on the ground. No new free constant is introduced.</p>
     *
     * <p>Note this is a POSITIVE threshold, which is the whole point of the redefinition. The old
     * predicate asked "is the gap closing" ({@code d(gap)/dt < -0.02}) and was therefore blind to the
     * state it existed to catch: a faster mob already at contact holds the gap CONSTANT (measured
     * 0.69 in bot_kite_execmon), so it never closed and B never fired while the bot lost health.</p>
     */
    public static final double MIN_OPENING_RATE =
            (1.0 - CombatRules.KITE_ENTER_RATIO) * CombatStats.BOT_SPRINT_SPEED;

    private final Map<UUID, int[]> failingTicks = new HashMap<>();
    private final Set<UUID> seen = new HashSet<>();
    private boolean intendedOpen;

    /**
     * Called once per tick from the bot's tick, before any branch takes ownership.
     *
     * <p>The predicate is {@code intent(open) AND d(gap)/dt < MIN_OPENING_RATE}, held for
     * {@code KITE_FAIL_TICKS}. The intent gate is what removes the false positive: the bot walking
     * IN to its rule-3 band also shrinks the gap, and the old predicate scored that as kiting failure
     * (measured: fired at tick 0 of bot_kite_execmon_lat's approach phase).</p>
     */
    public void tick(AICompanionBot bot) {
        seen.clear();
        for (TargetInfo t : bot.perception().targets) {
            UUID id = t.entity.getUUID();
            seen.add(id);
            int[] c = failingTicks.computeIfAbsent(id, k -> new int[1]);
            boolean intent = intendsToOpen(bot, t);
            double trend = bot.perception().speeds.gapTrend(t.entity);
            if (intent && trend < MIN_OPENING_RATE) {
                c[0]++;
            } else {
                c[0] = 0;
            }
        }
        failingTicks.keySet().retainAll(seen);
    }

    /**
     * Intent, read from what the bot ASKED for last tick rather than from what happened: the desired
     * movement direction (movement inputs rotated into world space) projected onto the away-from-
     * target axis. Positive means the bot is trying to increase the distance.
     */
    private boolean intendsToOpen(AICompanionBot bot, TargetInfo t) {
        double zza = bot.zza;
        double xxa = bot.xxa;
        if (zza * zza + xxa * xxa < 1.0E-6) {
            // Standing still is not an intent to open. A bot that is pinned and not even trying is
            // a different failure and must not be laundered into a kiting-execution verdict.
            return false;
        }
        double yaw = Math.toRadians(bot.getYRot());
        // Minecraft: +zza is forward along (-sin(yaw), cos(yaw)); +xxa is to the left.
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double lx = fz;
        double lz = -fx;
        double dx = fx * zza + lx * xxa;
        double dz = fz * zza + lz * xxa;
        double ax = bot.getX() - t.entity.getX();
        double az = bot.getZ() - t.entity.getZ();
        double alen = Math.hypot(ax, az);
        if (alen < 1.0E-6) {
            return false;
        }
        intendedOpen = (dx * ax + dz * az) / alen > 0.0;
        return intendedOpen;
    }

    /** Signal B: the bot has been trying and failing to open distance for KITE_FAIL_TICKS straight. */
    public boolean failing(LivingEntity target) {
        int[] c = target == null ? null : failingTicks.get(target.getUUID());
        return c != null && c[0] >= KITE_FAIL_TICKS;
    }

    /** Diagnostic: how many consecutive failing ticks this target currently has. */
    public int closingTicks(LivingEntity target) {
        int[] c = target == null ? null : failingTicks.get(target.getUUID());
        return c == null ? 0 : c[0];
    }

    /** Diagnostic: whether the last evaluated target saw an intent to open distance. */
    public boolean intendedOpen() {
        return intendedOpen;
    }

    public void reset() {
        failingTicks.clear();
        intendedOpen = false;
    }
}
