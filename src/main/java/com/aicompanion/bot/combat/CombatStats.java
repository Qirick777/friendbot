package com.aicompanion.bot.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * The bot's own combat snapshot for the tactical rules (T3.2). All read from attributes —
 * the mainhand weapon's damage/speed modifiers are already folded into ATTACK_DAMAGE /
 * ATTACK_SPEED once the item is equipped.
 */
public class CombatStats {

    /** Sprint ground speed in mob-movement-speed-attribute-comparable units (tunable, ch.18). */
    public static final double BOT_SPRINT_SPEED = 0.30;

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
