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
    /** 9.2 diagnostics: which branch of the priority table fired, and what it picked. */
    private String lastRule = "none";
    @Nullable
    private net.minecraft.world.entity.LivingEntity lastChosen;

    public Mode mode() {
        return mode;
    }

    public String lastRule() {
        return lastRule;
    }

    @Nullable
    public net.minecraft.world.entity.LivingEntity lastChosen() {
        return lastChosen;
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
            // U1: no unnecessary preemptive attack. Peacetime movement now belongs to ch.16
            // (T5.4 BotIdle), so this branch must NOT set a goal of its own — it only stands down.
            //
            // It used to call follow(user) here, which made two layers write the planner goal in
            // the same tick: protection set it to the user's block, then the idle branch set it to
            // a wander target. Every setGoal with a different goal discards the search, so the A*
            // never completed and the bot stood still. Measured: bot_idle_wander botTravel 0.00,
            // maxStep 0.000 over 260 ticks, while bot_idle_follow passed — because there the two
            // layers happened to want the same destination and setGoal was a no-op.
            clearCombat(bot);
            mode = Mode.NONE;
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

        // --- rule 2 (6.3 승패 게이트) — VETO over the 9.3 mode choice ------------------------
        // 9.3 decides WHICH target and whether a bow is available; rule 2 decides whether entering
        // melee is survivable at all ("근접 진입 가부"). Until now allowMelee had no live consumer,
        // so the bot would close on anything 9.3 pointed it at, including a fight it loses. Goal 1
        // (the user's survival) fails if the bot dies protecting them.
        boolean allowMelee = CombatRules.allowMelee(chosen, CombatStats.of(bot),
                CombatRules.DEFAULT_SAFETY);
        boolean hasRanged = hasBowAndArrows(bot);
        if (useMelee && !allowMelee && hasRanged) {
            useMelee = false;
            LOGGER.info("[PROTECT] rule2: melee denied on {} -> ranged (bow available)",
                    chosen.entity.getType().toShortString());
        } else if (useMelee && !allowMelee) {
            // Rule 2 says this fight is lost, and there is no ranged option. Engage anyway.
            //
            // PROVISIONAL — final arbitration belongs to T5.6, whose spec covers
            // 「계층 간 우선순위 충돌 해소(반사>판단, 생존>교전, 유저보호 조율)」.
            //
            // Rule 2 asks "do I win"; user protection asks "does the USER die". Those are different
            // questions and rule 2 must not answer the second one. The bot has R0 totem and the
            // survival state machine to fall back on; the user has neither. The purpose of
            // intervening is not victory but pulling aggro — which is why aggroDrop is the judged
            // value — and a losing fight still buys the user time. Doing nothing is the worst
            // outcome available: measured, gating intervention on rule 2 produced
            // aggroDrop 97.4 -> 0.0 with mode ENGAGE_RANGED and no bow, i.e. the bot stood still
            // while the user was hit.
            LOGGER.info("[PROTECT] rule2 says lose vs {} but no ranged option -> engage anyway "
                            + "(user protection is not gated on winning)",
                    chosen.entity.getType().toShortString());
        }
        // --- rule 1 (6.3 카이팅 가능성) — records the D cell for the ranged controller ----------
        // canKite=false AND allowMelee=false is the cell design 6.3 does not define: rule 1 says
        // "정면 대응" (which presumes melee) while rule 2 forbids melee. Decision taken and recorded
        // in ch.6.3's appended block: keep melee denied, seek distance by available means, and if
        // that fails withdraw toward the user. Rule 2 is a survival gate and the bot dying breaks
        // goal 1, so where the two rules disagree the refusing one wins.
        if (!allowMelee && !chosen.canKite) {
            bot.rangedCombat().setDistanceCritical(true);
        } else {
            bot.rangedCombat().setDistanceCritical(false);
        }

        lastRule = rule;
        lastChosen = chosen.entity;
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
        // ch.14: 「교전 모드가 무기를 요청하면(근접→검, 원거리→활) 해당 카테고리 최고를 메인핸드로
        // 스왑」. The equipment manager had the swap but no requester, so choosing ranged mode left a
        // sword in hand and the bot shot nothing (measured in bot_protect_armed: mode ENGAGE_RANGED
        // with a bow in the inventory and threatHpDrop 0.0).
        bot.equipment().requestCategory(bot, !useMelee, target);
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
