package com.aicompanion.bot.combat;

import net.minecraft.world.entity.LivingEntity;

/**
 * Layer-2 data for one mob kind (design 6.5 "특수 케이스 (층 2)"): the facts the bot CANNOT measure
 * — armor/shield-piercing, exact ability range, and how to read a charge-up signal.
 *
 * <p>This is <b>data, not logic</b> (T4.6 분할근거: "전용 로직이 아니라 데이터 보완"). The rule engine
 * ({@link CombatRules}) never asks what mob it is facing; it only consumes the measured stats plus
 * whatever profile was supplied here. Any entity given the same profile therefore produces the same
 * tactics, and a warden given a plain profile produces plain tactics.</p>
 */
public final class Layer2Profile {

    /** How to read a "winding up a big attack" signal off the entity, server-side. */
    @FunctionalInterface
    public interface ChargeDetector {
        boolean isCharging(LivingEntity entity);
    }

    /** 6.6 미지 몹 안전 기본값: assume the shield works, no known ranged ability, no charge signal. */
    public static final Layer2Profile DEFAULT =
            new Layer2Profile(false, 0.0, 0.0, 0.0, e -> false);

    /** True when this mob's damage bypasses armour/shield/blocks (규칙4 input). */
    public final boolean armorPiercing;
    /** Horizontal reach of the mob's ranged/special ability, in blocks (규칙3 input). */
    public final double rangedRangeXZ;
    /** Vertical reach of that ability, in blocks. */
    public final double rangedRangeY;
    /** Fixed damage of that ability (0 = unknown/none). */
    public final double fixedDamage;
    /** Server-readable charge detection (7장 반사 계층 input). */
    public final ChargeDetector chargeDetector;

    public Layer2Profile(boolean armorPiercing, double rangedRangeXZ, double rangedRangeY,
                         double fixedDamage, ChargeDetector chargeDetector) {
        this.armorPiercing = armorPiercing;
        this.rangedRangeXZ = rangedRangeXZ;
        this.rangedRangeY = rangedRangeY;
        this.fixedDamage = fixedDamage;
        this.chargeDetector = chargeDetector;
    }

    public boolean isCharging(LivingEntity entity) {
        return chargeDetector.isCharging(entity);
    }

    @Override
    public String toString() {
        return String.format("Layer2{pierce=%b rangeXZ=%.1f rangeY=%.1f dmg=%.1f}",
                armorPiercing, rangedRangeXZ, rangedRangeY, fixedDamage);
    }
}
