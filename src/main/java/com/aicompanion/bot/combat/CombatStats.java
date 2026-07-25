package com.aicompanion.bot.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * The bot's own combat snapshot for the tactical rules (T3.2). All read from attributes —
 * the mainhand weapon's damage/speed modifiers are already folded into ATTACK_DAMAGE /
 * ATTACK_SPEED once the item is equipped.
 */
public class CombatStats {

    /**
     * Bot sprint ground speed in BLOCKS PER TICK, measured at runtime (bot_warden_probe: 0.2806 b/t
     * = vanilla 5.61 m/s sprint). Rule 1 compares speeds in this one unit; see
     * {@link #MOB_ATTR_TO_BLOCKS_PER_TICK} for why the raw attribute cannot be compared directly.
     */
    public static final double BOT_SPRINT_SPEED = 0.2806;

    /*
     * NOTE: there is deliberately NO attribute→blocks/tick conversion constant any more.
     * Measured ratios (peak ÷ attribute) ranged 0.496 (zombie) … 1.688 (a speed-0.8 zombie), a 3.4×
     * spread that rises with the attribute, because effective speed is MOVEMENT_SPEED × an AI task
     * speed multiplier that differs per mob and per AI state. The reverse direction is impossible
     * too: a sprinting player's attribute (0.13) is BELOW a zombie's (0.23) while the measured
     * speeds are the other way round (0.2806 vs 0.1142) — player and mob attributes are different
     * units. Rule 1 therefore consumes an OBSERVED effective approach speed
     * ({@link com.aicompanion.bot.perception.SpeedObserver}); the attribute survives only as a
     * cold-start prior, and that prior is the conservative one (not kiteable).
     */

    public final double dps;
    public final double effectiveHp;
    public final double sprintSpeed;

    public CombatStats(double dps, double effectiveHp, double sprintSpeed) {
        this.dps = dps;
        this.effectiveHp = effectiveHp;
        this.sprintSpeed = sprintSpeed;
    }

    /**
     * Build from the bot: DPS = attack damage × attack speed; effective HP = health scaled by armour
     * damage reduction.
     *
     * <p>Effective HP used to be raw {@code getHealth()}, which ignored armour entirely. That was
     * harmless only because rule 2 had no live consumer — nothing read {@code allowMelee}. Wiring
     * rule 2 into the protection layer makes it load-bearing: an armoured bot would otherwise decline
     * fights it can win, and design goal 1 (the user's survival) depends on the bot engaging when it
     * can.</p>
     *
     * <p>Reduction uses the vanilla armour-points term (4% per point, capped at 80%). The full
     * vanilla formula also has a toughness/damage term that reduces the cap against heavy hits; that
     * refinement is left out deliberately — it needs the incoming damage value, which rule 2 does not
     * have at the point it decides, and omitting it is the conservative direction (it never
     * OVERstates survivability).</p>
     */
    public static CombatStats of(LivingEntity bot) {
        double atk = readAttr(bot, Attributes.ATTACK_DAMAGE, 1.0);
        double speed = readAttr(bot, Attributes.ATTACK_SPEED, 4.0);
        double dps = atk * speed;
        double armor = readAttr(bot, Attributes.ARMOR, 0.0);
        double reduction = Math.min(0.80, armor * 0.04);
        double effHp = bot.getHealth() / (1.0 - reduction);
        return new CombatStats(dps, effHp, BOT_SPRINT_SPEED);
    }

    private static double readAttr(LivingEntity e, net.minecraft.world.entity.ai.attributes.Attribute a, double def) {
        return e.getAttribute(a) != null ? e.getAttributeValue(a) : def;
    }

    @Override
    public String toString() {
        return String.format("CombatStats{dps=%.2f effHp=%.1f sprint=%.2f}", dps, effectiveHp, sprintSpeed);
    }
}
