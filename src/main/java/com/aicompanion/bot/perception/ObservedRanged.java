package com.aicompanion.bot.perception;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Design 6.2, 측정값 table: 「원거리 여부 | <b>투사체 발사 관측</b> | 붙기 전에 맞나」.
 *
 * <p>The spec names the SOURCE of this fact, and it is an observation, not a class. The
 * implementation had been reading {@code instanceof RangedAttackMob} — an interface, invisible to
 * the bot, that says what a mob <em>could</em> do rather than what it has been seen doing. 6.2(c)
 * fixes the shape this should take: 「보수적으로 가정하되, 관측되면 갱신한다」.</p>
 *
 * <p>So this registry holds the observed half. A projectile in the world whose owner is a mob is
 * the observation: that mob's TYPE is now known to shoot. Keying by type, not by entity, is what
 * makes the knowledge useful — the second skeleton benefits from what the first one taught us,
 * exactly like 6.6's 「사거리 모름 → 관측된 첫 공격 거리로 안전거리 갱신」. Cleared with the level so a
 * new world starts ignorant.</p>
 */
public final class ObservedRanged {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Set<EntityType<?>> SEEN_FIRING = Collections.synchronizedSet(new HashSet<>());

    private ObservedRanged() {
    }

    /** Record the observation carried by a projectile that is in flight near the bot. */
    public static void observe(Projectile projectile) {
        Entity owner = projectile.getOwner();
        if (owner == null) {
            return;
        }
        EntityType<?> type = owner.getType();
        if (SEEN_FIRING.add(type)) {
            LOGGER.info("[PERCEPTION] 6.2 원거리 관측: {} fired {} — now known to shoot",
                    EntityType.getKey(type), EntityType.getKey(projectile.getType()));
        }
    }

    /** Has any entity of this type ever been observed firing a projectile? */
    public static boolean hasFired(EntityType<?> type) {
        return SEEN_FIRING.contains(type);
    }

    public static boolean hasFired(Entity entity) {
        return entity != null && hasFired(entity.getType());
    }

    public static int knownTypes() {
        return SEEN_FIRING.size();
    }

    /** Test/lifecycle hook: forget everything (a fresh world has observed nothing). */
    public static void clear() {
        SEEN_FIRING.clear();
    }
}
