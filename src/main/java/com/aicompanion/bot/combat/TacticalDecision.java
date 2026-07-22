package com.aicompanion.bot.combat;

/**
 * Output of the tactical rule engine for one target (T3.2 / design 6.3).
 * This is a JUDGMENT only — no execution here (that is T3.3+).
 */
public class TacticalDecision {

    public enum Mode { MELEE, RANGED }

    public final boolean canKite;      // rule 1
    public final boolean allowMelee;   // rule 2 (win/loss gate)
    public final Mode mode;            // MELEE if allowMelee else RANGED (ranged forced)
    public final double bandDistance;  // rule 3 (kiting band)
    public final boolean shieldOn;     // rule 4 (off if target pierces armor)
    public final boolean keepDistance; // rule 4 (target is knockback-immune)

    public TacticalDecision(boolean canKite, boolean allowMelee, double bandDistance,
                            boolean shieldOn, boolean keepDistance) {
        this.canKite = canKite;
        this.allowMelee = allowMelee;
        this.mode = allowMelee ? Mode.MELEE : Mode.RANGED;
        this.bandDistance = bandDistance;
        this.shieldOn = shieldOn;
        this.keepDistance = keepDistance;
    }

    @Override
    public String toString() {
        return String.format("{canKite=%b allowMelee=%b mode=%s band=%.1f shield=%b keepDist=%b}",
                canKite, allowMelee, mode, bandDistance, shieldOn, keepDistance);
    }
}
