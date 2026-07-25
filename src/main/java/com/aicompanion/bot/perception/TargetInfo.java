package com.aicompanion.bot.perception;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.RangedAttackMob;

/**
 * Measured stats for one nearby mob (design 6.2 / A.2). Everything is read from attributes
 * and entity state — never from the mob's name/type (measurement-based, not hardcoded).
 */
public class TargetInfo {

    public final LivingEntity entity;
    public final double distance;

    public final double maxHealth;
    public final double health;
    public final double attackDamage;
    public final double moveSpeed;
    public final double knockbackResist;

    public final boolean isRanged;
    public final boolean armorPiercing; // layer-2 data (T4.6): supplied by Layer2Registry, not by name
    public final double hitboxWidth;
    public final double hitboxHeight;
    public final double reach;
    public final double rangedRange;    // unknown (0) until observed (design 6.6) / layer-2 supplied
    public final boolean targetingUser;
    /** Layer-2 data attached to this target (T4.6); {@code DEFAULT} for unknown mobs. */
    public final com.aicompanion.bot.combat.Layer2Profile layer2Profile;

    public TargetInfo(LivingEntity entity, double distance, LivingEntity user) {
        this.entity = entity;
        this.distance = distance;

        this.maxHealth = entity.getMaxHealth();
        this.health = entity.getHealth();
        this.attackDamage = readAttr(entity, Attributes.ATTACK_DAMAGE);
        this.moveSpeed = readAttr(entity, Attributes.MOVEMENT_SPEED);
        this.knockbackResist = readAttr(entity, Attributes.KNOCKBACK_RESISTANCE);

        // Layer-2 data (T4.6 / design 6.5): the unmeasurable facts come from the data table, keyed
        // by profile — never from the mob's name. An unknown mob gets the 6.6 safe default.
        com.aicompanion.bot.combat.Layer2Profile layer2 =
                com.aicompanion.bot.combat.Layer2Registry.profileOf(entity);
        this.layer2Profile = layer2;

        this.isRanged = entity instanceof RangedAttackMob || layer2.rangedRangeXZ > 0.0;
        this.armorPiercing = layer2.armorPiercing;
        this.hitboxWidth = entity.getBbWidth();
        this.hitboxHeight = entity.getBbHeight();
        // Melee reach ≈ half the attacker's width plus a standard arm span; refined later.
        this.reach = this.hitboxWidth * 0.5 + 2.0;
        // 0 until observed (6.6); layer-2 supplies it for mobs whose range cannot be measured.
        this.rangedRange = layer2.rangedRangeXZ;

        this.targetingUser = (user != null)
                && (entity instanceof Mob mob)
                && (mob.getTarget() == user);
    }

    /** Read an attribute value, or 0 if the entity does not have that attribute. */
    private static double readAttr(LivingEntity e, Attribute attr) {
        return e.getAttribute(attr) != null ? e.getAttributeValue(attr) : 0.0;
    }

    /** Effective DPS estimate from attack damage (attack speed refined later). */
    public double dpsEstimate() {
        return attackDamage; // ~1 swing/sec baseline; layer-2/observation refines
    }

    @Override
    public String toString() {
        return String.format(
                "%s{hp=%.1f/%.1f atk=%.2f spd=%.3f kbRes=%.2f ranged=%b box=%.2fx%.2f reach=%.1f targetsUser=%b d=%.1f}",
                entity.getType().toString(), health, maxHealth, attackDamage, moveSpeed,
                knockbackResist, isRanged, hitboxWidth, hitboxHeight, reach, targetingUser, distance);
    }
}
