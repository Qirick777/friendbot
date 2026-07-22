package com.aicompanion.bot.combat;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.perception.TargetInfo;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * Survival priority state machine (T4.1 / design ch.8). Runs BEFORE the combat/movement layers and,
 * when a health-driven survival mode is active, overrides them (design 8.1 "위가 이긴다").
 *
 * <p>Priority (health = the bot's own fraction of max):</p>
 * <ol start="0">
 *   <li><b>Survival escape</b> — hp &lt; danger: pearl if held and survivable (self-damage precheck),
 *       else backpedal-and-heal.</li>
 *   <li><b>Heal-hold</b> — latched: once escaping/healing, keep recovering until hp ≥ return.</li>
 *   <li><b>Pearl return</b> — hp ≥ return and the last escape used a pearl.</li>
 *   <li><b>Combat heal</b> — hp &lt; guard with a heal item: splash = throw at feet now (top),
 *       apple/drink = make distance then use.</li>
 * </ol>
 */
public class BotSurvival {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Health thresholds (fraction of max) — design 8 / 18 파라미터 총람.
    public static final float DANGER_LINE = 0.30F;   // 이탈 위험선
    public static final float RETURN_LINE = 0.80F;   // 복귀선
    public static final float GUARD_LINE = 0.55F;    // 전투 중 회복 경계선
    public static final float CRITICAL_LINE = 0.40F; // 봇 위급선 (8.2, T4.3에서 소비)

    private static final float PEARL_SELF_DAMAGE = 5.0F; // 엔더펄 착지 낙하 5데미지 (design 8.1)
    private static final float PEARL_MARGIN = 3.0F;      // 여유
    private static final double SAFE_HEAL_DIST = 6.0;    // 황금사과/포션은 거리 확보 후 사용
    private static final double TELEPORT_MIN = 4.0;      // 이 이상 순간 이동 = 텔레포트 성립

    public enum Mode { NONE, PEARL_ESCAPE, RETREAT_HEAL, PEARL_RETURN, COMBAT_HEAL }

    private Mode mode = Mode.NONE;
    private boolean healing;       // priority-1 latch: recovering until RETURN_LINE
    private boolean lastWasPearl;  // the active recovery began with a pearl escape

    // Pearl-escape tracking (for teleport confirmation + fallback).
    @Nullable
    private ThrownEnderpearl thrownPearl;
    @Nullable
    private Vec3 preThrowPos;
    @Nullable
    private Vec3 lastPearlPos;
    private boolean pearlTeleportConfirmed;
    private String pearlMechanism = "none"; // "vanilla" | "fallback" | "none"

    public Mode mode() {
        return mode;
    }

    public boolean isCritical(AICompanionBot bot) {
        return bot.getHealth() / bot.getMaxHealth() <= CRITICAL_LINE;
    }

    public boolean pearlTeleportConfirmed() {
        return pearlTeleportConfirmed;
    }

    public String pearlMechanism() {
        return pearlMechanism;
    }

    public void reset() {
        mode = Mode.NONE;
        healing = false;
        lastWasPearl = false;
        thrownPearl = null;
        preThrowPos = null;
        lastPearlPos = null;
        pearlTeleportConfirmed = false;
        pearlMechanism = "none";
    }

    /**
     * Decide + act. Called from {@link AICompanionBot#tick()} before the physics tick.
     *
     * @return true if a survival mode is active (caller must suppress combat/movement/look).
     */
    public boolean tick(AICompanionBot bot) {
        trackPearl(bot);                 // teleport confirm / fallback — independent of the chosen mode
        this.mode = decide(bot);
        act(bot, mode);
        return mode != Mode.NONE;
    }

    // --- decision (priority machine with the heal-hold latch) ---

    private Mode decide(AICompanionBot bot) {
        float hpFrac = bot.getHealth() / bot.getMaxHealth();
        boolean canPearl = countItem(bot, Items.ENDER_PEARL) > 0
                && bot.getHealth() > PEARL_SELF_DAMAGE + PEARL_MARGIN; // 자해 5데미지 사전검사
        boolean hasHeal = findHealItem(bot) != null;

        if (healing) {
            // Priority 1/2: keep recovering; on reaching the return line, optionally pearl-return.
            if (hpFrac >= RETURN_LINE) {
                healing = false;
                if (lastWasPearl) {
                    lastWasPearl = false;
                    return Mode.PEARL_RETURN;
                }
                return Mode.NONE;
            }
            return Mode.RETREAT_HEAL; // recover on the ground (after a pearl escape too)
        }

        // Priority 0: survival escape.
        if (hpFrac < DANGER_LINE) {
            healing = true;
            if (canPearl) {
                lastWasPearl = true;
                return Mode.PEARL_ESCAPE;
            }
            lastWasPearl = false;
            return Mode.RETREAT_HEAL;
        }

        // Priority 3: in-combat heal (no latch).
        if (hpFrac < GUARD_LINE && hasHeal) {
            return Mode.COMBAT_HEAL;
        }

        return Mode.NONE;
    }

    // --- action ---

    private void act(AICompanionBot bot, Mode m) {
        switch (m) {
            case PEARL_ESCAPE -> actPearlEscape(bot);
            case RETREAT_HEAL -> actRetreatHeal(bot, true);
            case COMBAT_HEAL -> actCombatHeal(bot);
            case PEARL_RETURN -> actPearlReturn(bot);
            case NONE -> { /* combat/movement layers run instead */ }
        }
    }

    /** Throw a pearl away from the nearest enemy (once); teleport confirmed in {@link #trackPearl}. */
    private void actPearlEscape(AICompanionBot bot) {
        if (thrownPearl != null) {
            // Already thrown — hold still while the pearl flies (teleport handled in trackPearl).
            stopInputs(bot);
            return;
        }
        Entity enemy = nearestEnemy(bot);
        float awayYaw = enemy != null ? awayYawFrom(bot, enemy) : bot.getYRot();
        bot.setYRot(awayYaw);
        bot.setYBodyRot(awayYaw);
        bot.setYHeadRot(awayYaw);
        bot.setXRot(-30.0F); // arc upward so the pearl carries the bot a useful distance away

        preThrowPos = bot.position();
        pearlTeleportConfirmed = false;

        ServerLevel level = (ServerLevel) bot.level();
        ThrownEnderpearl pearl = new ThrownEnderpearl(level, bot);
        pearl.shootFromRotation(bot, bot.getXRot(), bot.getYRot(), 0.0F, 1.5F, 1.0F);
        level.addFreshEntity(pearl);
        consumeOne(bot, Items.ENDER_PEARL);
        thrownPearl = pearl;
        lastPearlPos = pearl.position();

        LOGGER.info("[SURVIVAL] PEARL_ESCAPE thrown from ({},{},{}) awayYaw={}",
                fmt(preThrowPos.x), fmt(preThrowPos.y), fmt(preThrowPos.z), (int) awayYaw);
        stopInputs(bot);
    }

    /** Watch the in-flight pearl: confirm the vanilla teleport, or teleport the bot ourselves. */
    private void trackPearl(AICompanionBot bot) {
        if (thrownPearl == null) {
            return;
        }
        if (preThrowPos != null && bot.position().distanceTo(preThrowPos) > TELEPORT_MIN) {
            // The vanilla ThrownEnderpearl.onHit teleported the bot (connection gate passed).
            pearlTeleportConfirmed = true;
            pearlMechanism = "vanilla";
            LOGGER.info("[SURVIVAL] PEARL teleport CONFIRMED mechanism=vanilla to ({},{},{}) moved={}",
                    fmt(bot.getX()), fmt(bot.getY()), fmt(bot.getZ()),
                    fmt(bot.position().distanceTo(preThrowPos)));
            thrownPearl = null;
            return;
        }
        if (thrownPearl.isAlive() && !thrownPearl.isRemoved()) {
            lastPearlPos = thrownPearl.position(); // remember the last known landing spot
            return;
        }
        // Pearl is gone but the bot did NOT move → the connection gate blocked the vanilla teleport.
        // Fallback: teleport the bot to the pearl's landing spot ourselves so escape still works.
        if (lastPearlPos != null && preThrowPos != null) {
            bot.teleportTo(lastPearlPos.x, lastPearlPos.y, lastPearlPos.z);
            bot.resetFallDistance();
            pearlTeleportConfirmed = true;
            pearlMechanism = "fallback";
            LOGGER.info("[SURVIVAL] PEARL teleport CONFIRMED mechanism=fallback to ({},{},{}) moved={}",
                    fmt(lastPearlPos.x), fmt(lastPearlPos.y), fmt(lastPearlPos.z),
                    fmt(bot.position().distanceTo(preThrowPos)));
        }
        thrownPearl = null;
    }

    /** Backpedal away from the enemy (distance ↑) and, once far enough, consume a heal item. */
    private void actRetreatHeal(AICompanionBot bot, boolean allowMove) {
        Entity enemy = nearestEnemy(bot);
        double dist = enemy != null ? bot.position().distanceTo(enemy.position()) : Double.MAX_VALUE;

        if (enemy != null) {
            float faceYaw = faceYawTo(bot, enemy);
            bot.setYRot(faceYaw);
            bot.setYBodyRot(faceYaw);
            bot.setYHeadRot(faceYaw);
            bot.setXRot(0.0F);
        }

        HealItem heal = findHealItem(bot);
        boolean farEnough = dist >= SAFE_HEAL_DIST;

        // Backpedal until safe distance (design "거리 벌린 뒤 사용"); keep facing the threat.
        bot.setSprinting(false);
        bot.setJumping(false);
        bot.xxa = 0.0F;
        bot.zza = (allowMove && enemy != null && !farEnough) ? -1.0F : 0.0F;

        if (heal == null) {
            return;
        }
        if (heal.kind == HealKind.SPLASH) {
            throwSplashAtFeet(bot, heal.slot); // instant, no distance needed (design 우선순위 3 최우선)
            return;
        }
        // Apple / drinkable potion: only once distance is made.
        if (farEnough) {
            ensureInMainHand(bot, heal.slot);
            if (!bot.isUsingItem()) {
                bot.startUsingItem(InteractionHand.MAIN_HAND);
            }
        }
    }

    /** In-combat heal (priority 3): splash immediately; apple/drink behaves like a short retreat-heal. */
    private void actCombatHeal(AICompanionBot bot) {
        HealItem heal = findHealItem(bot);
        if (heal != null && heal.kind == HealKind.SPLASH) {
            throwSplashAtFeet(bot, heal.slot);
            return;
        }
        actRetreatHeal(bot, true);
    }

    /** Pearl return (priority 2): throw a pearl back toward the last engagement anchor if we can. */
    private void actPearlReturn(AICompanionBot bot) {
        Entity enemy = nearestEnemy(bot);
        if (enemy == null || countItem(bot, Items.ENDER_PEARL) <= 0) {
            LOGGER.info("[SURVIVAL] PEARL_RETURN skipped (no anchor/pearl)");
            reset();
            return;
        }
        float towardYaw = faceYawTo(bot, enemy);
        bot.setYRot(towardYaw);
        bot.setYBodyRot(towardYaw);
        bot.setYHeadRot(towardYaw);
        bot.setXRot(-30.0F);
        ServerLevel level = (ServerLevel) bot.level();
        ThrownEnderpearl pearl = new ThrownEnderpearl(level, bot);
        pearl.shootFromRotation(bot, bot.getXRot(), bot.getYRot(), 0.0F, 1.5F, 1.0F);
        level.addFreshEntity(pearl);
        consumeOne(bot, Items.ENDER_PEARL);
        preThrowPos = bot.position();
        thrownPearl = pearl;
        lastPearlPos = pearl.position();
        pearlTeleportConfirmed = false;
        LOGGER.info("[SURVIVAL] PEARL_RETURN thrown toward enemy");
    }

    // --- heal-item plumbing ---

    private enum HealKind { SPLASH, EAT, DRINK }

    private record HealItem(int slot, HealKind kind) {
    }

    /** Find a heal item, splash > apple > drinkable potion (design 우선순위 3). */
    @Nullable
    private HealItem findHealItem(AICompanionBot bot) {
        Inventory inv = bot.getInventory();
        int splash = -1, apple = -1, drink = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            Item it = inv.getItem(i).getItem();
            if (it == Items.SPLASH_POTION && splash < 0) {
                splash = i;
            } else if ((it == Items.GOLDEN_APPLE || it == Items.ENCHANTED_GOLDEN_APPLE) && apple < 0) {
                apple = i;
            } else if (it == Items.POTION && drink < 0) {
                drink = i;
            }
        }
        if (splash >= 0) {
            return new HealItem(splash, HealKind.SPLASH);
        }
        if (apple >= 0) {
            return new HealItem(apple, HealKind.EAT);
        }
        if (drink >= 0) {
            return new HealItem(drink, HealKind.DRINK);
        }
        return null;
    }

    private void throwSplashAtFeet(AICompanionBot bot, int slot) {
        ItemStack stack = bot.getInventory().getItem(slot);
        if (stack.isEmpty()) {
            return;
        }
        ServerLevel level = (ServerLevel) bot.level();
        ThrownPotion potion = new ThrownPotion(level, bot);
        potion.setItem(stack.copy());
        potion.shootFromRotation(bot, 90.0F, bot.getYRot(), 0.0F, 0.5F, 1.0F); // steep down → breaks at feet
        level.addFreshEntity(potion);
        stack.shrink(1);
        LOGGER.info("[SURVIVAL] splash heal thrown at feet");
    }

    private void ensureInMainHand(AICompanionBot bot, int slot) {
        Inventory inv = bot.getInventory();
        ItemStack want = inv.getItem(slot);
        if (want.isEmpty()) {
            return;
        }
        if (bot.getItemInHand(InteractionHand.MAIN_HAND) == want) {
            return;
        }
        int sel = inv.selected;
        ItemStack held = inv.getItem(sel);
        inv.setItem(sel, want);
        inv.setItem(slot, held);
    }

    // --- geometry / inventory helpers ---

    @Nullable
    private Entity nearestEnemy(AICompanionBot bot) {
        for (TargetInfo t : bot.perception().targets) {
            if (t.entity != null && t.entity.isAlive()) {
                return t.entity;
            }
        }
        return null;
    }

    private static float faceYawTo(AICompanionBot bot, Entity target) {
        double dx = target.getX() - bot.getX();
        double dz = target.getZ() - bot.getZ();
        return Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F);
    }

    private static float awayYawFrom(AICompanionBot bot, Entity enemy) {
        return Mth.wrapDegrees(faceYawTo(bot, enemy) + 180.0F);
    }

    private static int countItem(AICompanionBot bot, Item item) {
        Inventory inv = bot.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() == item) {
                n += inv.getItem(i).getCount();
            }
        }
        return n;
    }

    private static void consumeOne(AICompanionBot bot, Item item) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() == item) {
                inv.getItem(i).shrink(1);
                return;
            }
        }
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
