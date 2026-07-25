package com.aicompanion.bot.combat;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.Warden;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The layer-2 data table (design 6.5). Lookup order for a mob's {@link Layer2Profile}:
 *
 * <ol>
 *   <li>a per-entity override (test injection, or 6.6 "맞아보며 학습" observation later),</li>
 *   <li>the registered table entry for its {@link EntityType},</li>
 *   <li>{@link Layer2Profile#DEFAULT} — the 6.6 unknown-mob safe default.</li>
 * </ol>
 *
 * <p>Because the override layer is keyed by entity UUID, ANY entity can be given the warden profile
 * and a warden can be given a plain profile — which is how T4.6 proves the tactics come from data,
 * not from the mob's identity.</p>
 */
public final class Layer2Registry {

    /**
     * Warden (design 6.5 / 18장 워든 데이터). Sonic boom pierces armour, shields and blocks; its
     * window is 15 horizontal / 20 vertical; fixed 10 damage. The charge signal is the SERVER-side
     * Brain memory {@code SONIC_BOOM_SOUND_DELAY} that {@code SonicBoom.start()} sets for 34 ticks
     * (= the documented 1.7s charge). Confirmed at runtime by bot_warden_probe; the client-only
     * {@code sonicBoomAnimationState}/particles are deliberately NOT used.
     */
    public static final Layer2Profile WARDEN = new Layer2Profile(
            true, 15.0, 20.0, 10.0,
            e -> e instanceof Warden w
                    && w.getBrain().hasMemoryValue(MemoryModuleType.SONIC_BOOM_SOUND_DELAY));

    private static final Map<EntityType<?>, Layer2Profile> TABLE = new HashMap<>();
    private static final Map<UUID, Layer2Profile> OVERRIDES = new HashMap<>();

    static {
        TABLE.put(EntityType.WARDEN, WARDEN);
    }

    private Layer2Registry() {
    }

    /** Resolve the profile for an entity (override → table → unknown-mob default). */
    public static Layer2Profile profileOf(LivingEntity entity) {
        Layer2Profile override = OVERRIDES.get(entity.getUUID());
        if (override != null) {
            return override;
        }
        return TABLE.getOrDefault(entity.getType(), Layer2Profile.DEFAULT);
    }

    /** Attach a profile to one specific entity, regardless of its type (test/observation input). */
    public static void override(LivingEntity entity, Layer2Profile profile) {
        OVERRIDES.put(entity.getUUID(), profile);
    }

    public static void clearOverride(LivingEntity entity) {
        OVERRIDES.remove(entity.getUUID());
    }

    public static void clearAllOverrides() {
        OVERRIDES.clear();
    }
}
