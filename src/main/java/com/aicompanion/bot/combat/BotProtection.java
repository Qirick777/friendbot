package com.aicompanion.bot.combat;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.perception.Perception;
import com.aicompanion.bot.perception.TargetInfo;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * User-protection protocol (T4.3 / design ch.9). The top-level coordinator: every tick it decides
 * WHICH enemy the bot should engage (or whether to intervene at all, heal the user, or flee),
 * driven by the user's health band × the bot's own critical state × heal-potion possession. It
 * then hands the chosen target to the melee/ranged combat controllers.
 *
 * <p>Runs below reflex (T4.2) and survival (T4.1) but above combat: it only sets targets/goals,
 * so the existing combat/movement branches consume its decision the same tick.</p>
 */
public class BotProtection {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final float USER_LOW = 0.40F;    // 유저 타겟 전환선 (design 18)
    private static final float USER_CRIT = 0.15F;   // 유저 치명선
    private static final double INVADE_RADIUS = 10.0; // 근접 몹 유저 중심 10블록 (9.4)
    private static final double MAX_LEASH = 16.0;    // 추적 중 유저와 이 이상 멀어지면 복귀 (9.3-2)
    private static final double DPS_TIE = 0.5;       // "고위험 여럿" 동률 판정 여유

    public enum Mode { NONE, FOLLOW, ENGAGE_MELEE, ENGAGE_RANGED, HEAL_USER, FLEE }

    private Mode mode = Mode.NONE;

    public Mode mode() {
        return mode;
    }

    /** Called from {@link AICompanionBot#tick()} below reflex/survival, above combat. */
    public void tick(AICompanionBot bot) {
        Perception p = bot.perception();
        ServerPlayer user = p.user;
        if (user == null) {
            // No user → protection is inert (does NOT touch combat targets), leaving manual/other
            // control (e.g. dev /bot attack, or a combat-only test) untouched.
            mode = Mode.NONE;
            return;
        }

        // --- 9.1 intervention filter + 9.4 invade radius + 9.3-3 ignore non-aggroed ranged ---
        List<TargetInfo> engage = engageable(p, user);
        if (engage.isEmpty()) {
            // U1: no unnecessary preemptive attack — just stay with the user.
            follow(bot, user);
            mode = Mode.NONE;
            LOGGER.info("[PROTECT] no engage target -> follow user (no preemptive attack)");
            return;
        }

        float userHp = user.getMaxHealth() > 0 ? p.userHealth / user.getMaxHealth() : 1.0F;
        boolean botCrit = bot.survival().isCritical(bot);
        boolean pot = hasThrowableHeal(bot);

        // --- 9.2 target-priority decision table (top-down, first match wins) ---
        if (userHp <= USER_CRIT) {
            if (pot) {
                throwHealAtUser(bot, user);      // T-P4: 유저 즉각 회복 (발밑 투척)
                follow(bot, user);
                mode = Mode.HEAL_USER;
                LOGGER.info("[PROTECT] user<=15% + potion -> HEAL_USER (throw), recheck->flee");
                return;
            }
            flee(bot, user);                      // T-P5: 즉각 도주 (T4.5에서 완성)
            mode = Mode.FLEE;
            LOGGER.info("[PROTECT] user<=15% -> FLEE (delegated to T4.5)");
            return;
        }

        TargetInfo chosen;
        String rule;
        if (userHp <= USER_LOW) {
            if (botCrit) {
                chosen = maxDpsPreferTargetingUser(engage); // T-P3
                rule = "P3(user<=40,botCrit:maxDPS,tie=targetsUser)";
            } else {
                chosen = nearestTargetingUserElseMaxDps(engage, bot); // T-P2
                rule = "P2(user<=40:targetsUser-first)";
            }
        } else {
            chosen = maxDps(engage); // T-P1
            rule = "P1(user>40:maxDPS)";
        }

        // --- 9.3 engage mode: bow → guard + ranged (melee threat first); no bow → chase melee ---
        boolean useMelee;
        if (hasBowAndArrows(bot)) {
            TargetInfo meleeThreat = nearestMeleeThreat(engage, user);
            if (meleeThreat != null) {
                chosen = meleeThreat;           // 9.3: 접근 근접 위협 우선
                useMelee = true;
            } else {
                useMelee = false;               // 9.3: 유저 곁 활 처치
            }
        } else {
            useMelee = true;
            if (bot.position().distanceTo(user.position()) > MAX_LEASH) {
                // 9.3-2: chasing pulled us too far → abandon and return to the user.
                clearCombat(bot);
                follow(bot, user);
                mode = Mode.FOLLOW;
                LOGGER.info("[PROTECT] leash>{} -> return to user (abandon chase)", (int) MAX_LEASH);
                return;
            }
        }

        assignTarget(bot, chosen.entity, useMelee);
        mode = useMelee ? Mode.ENGAGE_MELEE : Mode.ENGAGE_RANGED;
        LOGGER.info("[PROTECT] {} -> target={} dps={} targetsUser={} mode={} userHp={}",
                rule, chosen.entity.getType().toString(), fmt(chosen.attackDamage),
                chosen.targetingUser, mode, fmt(userHp * 100) + "%");
    }

    // --- engageable set (9.1 / 9.4 / 9.3-3) ---

    private List<TargetInfo> engageable(Perception p, ServerPlayer user) {
        LivingEntity userLastHurt = user.getLastHurtMob();
        List<TargetInfo> out = new ArrayList<>();
        for (TargetInfo t : p.targets) {
            if (t.entity == null || !t.entity.isAlive()) {
                continue;
            }
            boolean aggro = t.targetingUser;                                   // 9.1 유저 어그로
            boolean userInitiated = userLastHurt != null && t.entity == userLastHurt; // 9.1 유저 개시
            boolean nearUser = distToUser(t, user) <= INVADE_RADIUS;           // 9.4 근접 침범

            if (t.isRanged && !aggro && !userInitiated) {
                continue; // 9.3-3: 유저 어그로 안 끌린 원거리 몹 무시
            }
            if (!t.isRanged && !nearUser && !aggro && !userInitiated) {
                continue; // 9.4: 침범하지 않은 근접 몹은 대상 아님 (U1 선제공격 금지)
            }
            out.add(t);
        }
        return out;
    }

    // --- target selectors (9.2) ---

    private static TargetInfo maxDps(List<TargetInfo> es) {
        TargetInfo best = es.get(0);
        for (TargetInfo t : es) {
            if (t.attackDamage > best.attackDamage) {
                best = t;
            }
        }
        return best;
    }

    private static TargetInfo maxDpsPreferTargetingUser(List<TargetInfo> es) {
        TargetInfo top = maxDps(es);
        TargetInfo tie = null;
        for (TargetInfo t : es) {
            if (t.attackDamage >= top.attackDamage - DPS_TIE && t.targetingUser) {
                if (tie == null || t.attackDamage > tie.attackDamage) {
                    tie = t;
                }
            }
        }
        return tie != null ? tie : top;
    }

    private static TargetInfo nearestTargetingUserElseMaxDps(List<TargetInfo> es, AICompanionBot bot) {
        TargetInfo best = null;
        for (TargetInfo t : es) {
            if (t.targetingUser && (best == null || t.distance < best.distance)) {
                best = t;
            }
        }
        return best != null ? best : maxDps(es);
    }

    @Nullable
    private static TargetInfo nearestMeleeThreat(List<TargetInfo> es, ServerPlayer user) {
        TargetInfo best = null;
        for (TargetInfo t : es) {
            if (!t.isRanged) {
                double d = distToUser(t, user);
                if (best == null || d < distToUser(best, user)) {
                    best = t;
                }
            }
        }
        return best;
    }

    // --- actions ---

    private void assignTarget(AICompanionBot bot, LivingEntity target, boolean useMelee) {
        if (useMelee) {
            bot.rangedCombat().stop();
            if (bot.meleeCombat().target() != target) {
                bot.meleeCombat().setTarget(target);
            }
        } else {
            bot.meleeCombat().stop();
            if (bot.rangedCombat().target() != target) {
                bot.rangedCombat().setTarget(target);
            }
        }
    }

    private void clearCombat(AICompanionBot bot) {
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();
    }

    /** No target → stay near the user (W2). Clears combat and paths toward the user. */
    private void follow(AICompanionBot bot, @Nullable ServerPlayer user) {
        clearCombat(bot);
        if (user != null) {
            bot.planner().setGoal(user.blockPosition());
        }
    }

    /** T-P5 flee stub — completed in T4.5 (도주 모드). For now: back away from the nearest enemy. */
    private void flee(AICompanionBot bot, ServerPlayer user) {
        clearCombat(bot);
        // Minimal fallback so "flee" is not a no-op: path to a point away from the nearest enemy.
        if (!bot.perception().targets.isEmpty()) {
            LivingEntity e = bot.perception().targets.get(0).entity;
            double dx = bot.getX() - e.getX();
            double dz = bot.getZ() - e.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1.0E-6) {
                int gx = (int) (bot.getX() + dx / len * 8.0);
                int gz = (int) (bot.getZ() + dz / len * 8.0);
                bot.planner().setGoal(new net.minecraft.core.BlockPos(gx, (int) bot.getY(), gz));
            }
        }
    }

    private void throwHealAtUser(AICompanionBot bot, ServerPlayer user) {
        int slot = findSplash(bot);
        if (slot < 0) {
            return;
        }
        ItemStack stack = bot.getInventory().getItem(slot);
        ServerLevel level = (ServerLevel) bot.level();
        ThrownPotion potion = new ThrownPotion(level, bot);
        potion.setItem(stack.copy());
        // Aim at the user: yaw toward the user, steep downward so it breaks at their feet.
        double dx = user.getX() - bot.getX();
        double dz = user.getZ() - bot.getZ();
        float yaw = net.minecraft.util.Mth.wrapDegrees(
                (float) (net.minecraft.util.Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F);
        potion.shootFromRotation(bot, 45.0F, yaw, 0.0F, 0.7F, 1.0F);
        level.addFreshEntity(potion);
        stack.shrink(1);
        LOGGER.info("[PROTECT] heal potion thrown toward user");
    }

    // --- inventory / geometry helpers ---

    private static boolean hasThrowableHeal(AICompanionBot bot) {
        return findSplash(bot) >= 0;
    }

    private static int findSplash(AICompanionBot bot) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() == Items.SPLASH_POTION
                    || inv.getItem(i).getItem() == Items.LINGERING_POTION) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasBowAndArrows(AICompanionBot bot) {
        Inventory inv = bot.getInventory();
        boolean bow = false;
        boolean arrows = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof BowItem) {
                bow = true;
            } else if (s.getItem() == Items.ARROW) {
                arrows = true;
            }
        }
        return bow && arrows;
    }

    private static double distToUser(TargetInfo t, ServerPlayer user) {
        return t.entity.position().distanceTo(user.position());
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }
}
