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

    /**
     * Mob {@code MOVEMENT_SPEED} attribute → blocks/tick conversion. The attribute is NOT in
     * blocks/tick: bot_warden_probe measured a warden (attribute 0.30) travelling 0.0471 b/t, i.e.
     * a factor of 0.157. Without this, rule 1 compared an attribute against a b/t figure and got
     * "warden is as fast as a sprinting player", contradicting design 6.5.
     */
    public static final double MOB_ATTR_TO_BLOCKS_PER_TICK = 0.157;

    /** Convert a mob's movement-speed attribute into blocks/tick (rule-1 comparable). */
    public static double mobSpeedBlocksPerTick(double movementSpeedAttribute) {
        return movementSpeedAttribute * MOB_ATTR_TO_BLOCKS_PER_TICK;
    }

    public final double dps;
    public final double effectiveHp;
    public final double sprintSpeed;

    public CombatStats(double dps, double effectiveHp, double sprintSpeed) {
        this.dps = dps;
        this.effectiveHp = effectiveHp;
        this.sprintSpeed = sprintSpeed;
    }

    /** Build from the bot: DPS = attack damage × attack speed; effective HP = current health. */
    public static CombatStats of(LivingEntity bot) {
        double atk = readAttr(bot, Attributes.ATTACK_DAMAGE, 1.0);
        double speed = readAttr(bot, Attributes.ATTACK_SPEED, 4.0);
        double dps = atk * speed;
        double effHp = bot.getHealth();
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
