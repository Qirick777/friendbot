package com.aicompanion.bot.combat;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.perception.TargetInfo;
import com.mojang.logging.LogUtils;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * Reflex layer (T4.2 / design ch.7). Runs BEFORE survival/combat every tick ("판단보다 먼저, 매 틱
 * 최우선"). Two reflexes:
 *
 * <ul>
 *   <li><b>R0 — totem pre-equip</b>: if the estimated incoming damage would be lethal, move a totem
 *       into the off-hand (vanilla auto-procs it on a killing blow). Off-hand priority totem &gt; shield.</li>
 *   <li><b>R1 — shield / evade</b>: on an incoming projectile (or melee motion), raise a shield if held
 *       (and the enemy is not an axe wielder), otherwise sidestep perpendicular to the projectile.</li>
 * </ul>
 */
public class BotReflex {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final float TOTEM_MARGIN = 2.0F;     // 여유 (design 7 R0)
    private static final double RANGED_EVADE_DIST = 22.0; // orbit while a ranged enemy is this close
    private static final double ORBIT_MIN = 4.0;         // radial correction band (stay off the shooter)
    private static final double ORBIT_MAX = 12.0;

    private boolean placedTotem;          // we put a totem in the off-hand (for proc detection)
    private final int orbitSign = 1;      // consistent circle-strafe direction → no zero-velocity flips
    private boolean chargeEscaping;       // T4.6: currently fleeing a charged-up special attack

    // --- R0: totem pre-equip (never blocks the other layers) ---

    public void tickR0(AICompanionBot bot) {
        float expDmg = bot.perception().expectedDamage;
        boolean lethalRisk = expDmg >= bot.getHealth() + TOTEM_MARGIN;
        ItemStack off = bot.getOffhandItem();
        boolean offIsTotem = off.getItem() == Items.TOTEM_OF_UNDYING;

        if (lethalRisk && !offIsTotem) {
            int slot = findInMain(bot, Items.TOTEM_OF_UNDYING);
            if (slot >= 0) {
                ItemStack before = off.copy();
                bot.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
                bot.getInventory().getItem(slot).shrink(1);
                if (!before.isEmpty()) {
                    bot.getInventory().add(before); // off-hand priority totem > shield: shield back to bag
                    LOGGER.info("[REFLEX] shield displaced by totem (offhand priority)");
                }
                placedTotem = true;
                LOGGER.info("[REFLEX] R0 offhand: {} -> {} (expDmg={} hp={})",
                        before.isEmpty() ? "empty" : before.getItem(),
                        Items.TOTEM_OF_UNDYING, fmt(expDmg), fmt(bot.getHealth()));
            }
        }

        // Proc detection: a totem we placed is gone → vanilla auto-fired it → transition to escape.
        if (placedTotem && bot.getOffhandItem().getItem() != Items.TOTEM_OF_UNDYING) {
            placedTotem = false;
            LOGGER.info("[REFLEX] totem PROC detected -> escape (hp={})", fmt(bot.getHealth()));
            // Health drops to ~1 on proc → survival's danger line auto-triggers the escape next.
        }
        if (bot.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING) {
            placedTotem = true;
        }
    }

    /**
     * R-charge (T4.6 / design 6.5 "소닉 차징 감지 시 즉시 밴드 밖 이탈(반사)"). Reads the charge signal
     * from the target's LAYER-2 PROFILE — the reflex never asks which mob this is — and, while a
     * charge is up, sprints straight out past the ability's range.
     *
     * @return true if it owns movement this tick.
     */
    public boolean tickChargeEscape(AICompanionBot bot) {
        TargetInfo charging = null;
        for (TargetInfo t : bot.perception().targets) {
            if (t.entity != null && t.entity.isAlive() && t.layer2Profile.isCharging(t.entity)) {
                charging = t;
                break;
            }
        }
        if (charging == null) {
            chargeEscaping = false;
            return false;
        }
        // Escape past the ability's own range (rule-3 band upper bound covers it with margin).
        double escapeTo = CombatRules.bandMax(charging);
        double dist = charging.distance;
        if (!chargeEscaping) {
            chargeEscaping = true;
            LOGGER.info("[REFLEX] CHARGE detected (layer-2 signal) dist={} -> escape beyond {}",
                    fmt(dist), fmt(escapeTo));
        }
        float away = Mth.wrapDegrees(
                yawTo(bot.getX(), bot.getZ(), charging.entity.getX(), charging.entity.getZ()) + 180.0F);
        bot.setYRot(away);
        bot.setYBodyRot(away);
        bot.setYHeadRot(away);
        bot.zza = 1.0F;
        bot.xxa = 0.0F;
        bot.setSprinting(true);
        bot.setJumping(false);
        return true;
    }

    // --- R1: shield / sidestep evade. Returns true if it owns movement this tick. ---

    public boolean tickR1(AICompanionBot bot) {
        Projectile incoming = firstIncoming(bot);
        Entity rangedEnemy = nearestRangedEnemy(bot);
        if (incoming == null && rangedEnemy == null) {
            return false; // no ranged threat → let the lower layers run
        }

        boolean shieldInOff = bot.getOffhandItem().getItem() == Items.SHIELD;
        boolean axeEnemy = nearestEnemyHasAxe(bot);

        // Rule 4 (6.3 방패·넉백 유효성) — COMBINED, not substituted. useShield(t) answers "is a shield
        // effective against this target at all" (layer-2 armour piercing); the existing conditions
        // answer "can it be raised right now" (a shield is held, a projectile is actually inbound,
        // and the enemy has no axe — vanilla axes disable shields). Replacing one with the other
        // would drop whichever knowledge the other holds; the axe term in particular is not in
        // rule 4. Moving the axe knowledge into Layer2Profile is registered as separate debt.
        boolean ruleShield = ruleAllowsShield(bot, incoming);
        if (incoming != null && shieldInOff && !axeEnemy && ruleShield) {
            // R1 shield: face the threat and raise the shield (off-hand use).
            Vec3 src = incoming.position();
            float faceYaw = yawTo(bot.getX(), bot.getZ(), src.x, src.z);
            bot.setYRot(faceYaw);
            bot.setYBodyRot(faceYaw);
            bot.setYHeadRot(faceYaw);
            stopInputs(bot);
            if (!bot.isUsingItem()) {
                bot.startUsingItem(InteractionHand.OFF_HAND);
            }
            LOGGER.info("[REFLEX] R1 shield up isBlocking={} usingItem={}",
                    bot.isBlocking(), bot.isUsingItem());
            return true;
        }

        if (axeEnemy) {
            LOGGER.info("[REFLEX] axe enemy -> evade-biased (shield disable risk)");
        }

        // R1 evade (no shield / projectile): circle-strafe around the threat. Continuous tangential
        // motion never stops or reverses, so a non-leading shooter's arrows keep landing behind us
        // (design "무빙으로 우아하게 피한다"). Pivot on the ranged enemy if known, else on the arrow line.
        double px;
        double pz;
        if (rangedEnemy != null) {
            px = rangedEnemy.getX();
            pz = rangedEnemy.getZ();
        } else {
            Vec3 v = incoming.getDeltaMovement();
            px = bot.getX() + v.x; // a point along the arrow's flight → orbit its bearing
            pz = bot.getZ() + v.z;
        }
        double dx = px - bot.getX();
        double dz = pz - bot.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0E-6) {
            return false;
        }
        double rx = dx / dist;
        double rz = dz / dist;                 // unit vector toward the threat (radial)
        double tx = -rz * orbitSign;
        double tz = rx * orbitSign;            // tangent (perpendicular) → the orbit direction
        // Mild radial correction to hold the orbit band (stay off the shooter, stay on the ground).
        double radial = dist < ORBIT_MIN ? -1.0 : (dist > ORBIT_MAX ? 1.0 : 0.0);
        double mx = tx + rx * radial * 0.5;
        double mz = tz + rz * radial * 0.5;
        float moveYaw = yawTo(bot.getX(), bot.getZ(), bot.getX() + mx, bot.getZ() + mz);
        bot.setYRot(moveYaw);
        bot.setYBodyRot(moveYaw);
        bot.setYHeadRot(moveYaw);
        bot.zza = 1.0F;
        bot.xxa = 0.0F;
        bot.setSprinting(true); // more lateral speed = wider miss margin
        bot.setJumping(false);
        LOGGER.info("[REFLEX] R1 circle-strafe dist={} tangent=({},{})", fmt(dist), fmt(tx), fmt(tz));
        return true;
    }

    // --- helpers ---

    @Nullable
    private static Projectile firstIncoming(AICompanionBot bot) {
        return bot.perception().incoming.isEmpty() ? null : bot.perception().incoming.get(0);
    }

    /**
     * Projectile-shooting enemies only. Deliberately NOT {@code TargetInfo.isRanged}: since T4.6
     * that flag also covers mobs whose layer-2 range is a hitscan/AoE ability (a warden's sonic
     * boom), and orbiting such a mob is useless — it cannot be dodged, only outranged, which is the
     * job of the tactical band and the charge-escape reflex. Circle-strafing is for dodging actual
     * projectiles, so the test is "does it shoot things".
     */
    /**
     * Rule 4's half of the shield decision: is a shield effective against the thing shooting at us?
     * Resolved against the projectile's owner when it is a tracked target, else against the nearest
     * target — an unknown shooter defaults to "shield useful" per design 6.6 (관통 여부 모름 → 방패
     * 유효 가정하되, 뚫리는 걸 관측하면 즉시 오프).
     */
    private static boolean ruleAllowsShield(AICompanionBot bot, @Nullable Projectile incoming) {
        Entity owner = incoming == null ? null : incoming.getOwner();
        TargetInfo nearest = null;
        for (TargetInfo t : bot.perception().targets) {
            if (owner != null && t.entity == owner) {
                return CombatRules.useShield(t);
            }
            if (nearest == null || t.distance < nearest.distance) {
                nearest = t;
            }
        }
        return nearest == null || CombatRules.useShield(nearest);
    }

    @Nullable
    private static Entity nearestRangedEnemy(AICompanionBot bot) {
        for (TargetInfo t : bot.perception().targets) {
            if (t.entity != null && t.entity.isAlive() && t.distance <= RANGED_EVADE_DIST
                    && t.entity instanceof net.minecraft.world.entity.monster.RangedAttackMob) {
                return t.entity;
            }
        }
        return null;
    }

    private static boolean nearestEnemyHasAxe(AICompanionBot bot) {
        for (TargetInfo t : bot.perception().targets) {
            if (t.entity != null && t.entity.isAlive()) {
                return t.entity.getMainHandItem().getItem() instanceof AxeItem;
            }
        }
        return false;
    }

    private static int findInMain(AICompanionBot bot, net.minecraft.world.item.Item item) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < 36; i++) { // hotbar + main only (never armor/offhand)
            if (inv.getItem(i).getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    private static float yawTo(double x, double z, double tx, double tz) {
        return Mth.wrapDegrees((float) (Mth.atan2(tz - z, tx - x) * (180.0D / Math.PI)) - 90.0F);
    }

    private static void stopInputs(AICompanionBot bot) {
        bot.zza = 0.0F;
        bot.xxa = 0.0F;
        bot.setSprinting(false);
        bot.setJumping(false);
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
