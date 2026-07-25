package com.aicompanion.bot.combat;

import com.aicompanion.bot.perception.TargetInfo;

/**
 * Measurement-based tactical rule engine (T3.2 / design 6.3, layer 1). Turns measured target
 * stats into a {@link TacticalDecision} via four name-agnostic rules. Judgment only — nothing
 * here acts; T3.3+ consume the output.
 */
public final class CombatRules {

    /** Safety factor for the win/loss gate (tunable, ch.18). */
    public static final double DEFAULT_SAFETY = 1.0;
    /** Extra spacing added to the safe distance / kiting band (tunable, ch.18). */
    public static final double DEFAULT_MARGIN = 2.0;
    /**
     * Kiting-band margins, COMMON to every mob (T4.6 / ch.18). The band is
     * [safeDistance+MIN, safeDistance+MAX] around the rule-3 distance. With the warden's layer-2
     * horizontal range of 15 these give exactly the documented 16~20 band, and the same two
     * constants apply to every other mob — no per-mob margin, so the band still comes from one rule.
     */
    public static final double BAND_MARGIN_MIN = 1.0;
    public static final double BAND_MARGIN_MAX = 5.0;
    /** Knockback resistance at/above which a target is treated as knockback-immune. */
    public static final double KB_IMMUNE = 1.0;

    private CombatRules() {
    }

    /** Rule 1 hysteresis: become kiteable only well below the bot's sprint … */
    public static final double KITE_ENTER_RATIO = 0.85;
    /** … and stop being kiteable only once clearly at/above it, so the verdict cannot chatter. */
    public static final double KITE_EXIT_RATIO = 0.95;

    /**
     * Rule 1 — kiting is possible when the target is slower than the bot's sprint. The comparison is
     * measured-vs-measured, both in blocks/tick: the target's OBSERVED effective approach speed
     * (displacement ÷ elapsed ticks, stationary ticks included) against the bot's measured sprint.
     */
    public static boolean canKite(TargetInfo t, double botSprintSpeed) {
        return t.canKite;
    }

    /**
     * Pure rule-1 verdict with hysteresis. Cold start (no usable observation yet) is the
     * CONSERVATIVE answer, {@code false} = 정면 대응: mistaking a fast mob for a slow one makes the
     * bot turn its back and get run down, which costs far more than the opposite mistake.
     *
     * @param observedSpeed  target's observed effective approach speed, blocks/tick
     * @param hasObservation whether the observation window is filled enough to trust
     * @param previous       the previous verdict for this target (sticky, for hysteresis)
     */
    public static boolean evaluateKite(double observedSpeed, boolean hasObservation,
                                       boolean previous, double botSprintSpeed) {
        if (!hasObservation) {
            return false; // cold-start prior: assume it can keep up
        }
        if (observedSpeed < botSprintSpeed * KITE_ENTER_RATIO) {
            return true;
        }
        if (observedSpeed > botSprintSpeed * KITE_EXIT_RATIO) {
            return false;
        }
        return previous; // inside the dead band → hold
    }

    /**
     * Rule 2 — win/loss gate. Melee is allowed only if the bot kills the target faster than
     * the target kills the bot (× safety). Target DPS reflects armor piercing (layer 2).
     */
    public static boolean allowMelee(TargetInfo t, CombatStats me, double safetyFactor) {
        double botDps = Math.max(me.dps, 1.0E-6);
        double targetDps = Math.max(t.dpsEstimate(), 1.0E-6);
        double timeToKill = t.maxHealth / botDps;
        double timeToDie = me.effectiveHp / targetDps;
        return timeToKill < timeToDie * safetyFactor;
    }

    /** Rule 3 — safe distance / kiting band = max(melee reach, ranged range) + margin. */
    public static double safeDistance(TargetInfo t, double margin) {
        return Math.max(t.reach, t.rangedRange) + margin;
    }

    /** Rule 3 — band lower bound (common margin, T4.6). */
    public static double bandMin(TargetInfo t) {
        return safeDistance(t, BAND_MARGIN_MIN);
    }

    /** Rule 3 — band upper bound (common margin, T4.6). */
    public static double bandMax(TargetInfo t) {
        return safeDistance(t, BAND_MARGIN_MAX);
    }

    /** Rule 4a — shield is useful unless the target pierces armor. */
    public static boolean useShield(TargetInfo t) {
        return !t.armorPiercing;
    }

    /** Rule 4b — a knockback-immune target cannot be pushed; keep distance instead. */
    public static boolean keepDistance(TargetInfo t) {
        return t.knockbackResist >= KB_IMMUNE;
    }

    /** Combine all four rules into the tactical decision for one target. */
    public static TacticalDecision evaluate(TargetInfo t, CombatStats me,
                                            double safetyFactor, double margin) {
        boolean canKite = canKite(t, me.sprintSpeed);
        boolean allowMelee = allowMelee(t, me, safetyFactor);
        double band = safeDistance(t, margin);
        boolean shieldOn = useShield(t);
        boolean keepDist = keepDistance(t);
        return new TacticalDecision(canKite, allowMelee, band, shieldOn, keepDist,
                bandMin(t), bandMax(t));
    }

    public static TacticalDecision evaluate(TargetInfo t, CombatStats me) {
        return evaluate(t, me, DEFAULT_SAFETY, DEFAULT_MARGIN);
    }
}
