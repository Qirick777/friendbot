package com.aicompanion.bot.equip;

import com.aicompanion.bot.AICompanionBot;
import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Collection;

/**
 * T5.2 장비 관리자 (design ch.14).
 *
 * <p>Spec quoted: 「전투 로직과 분리된 <b>상시 백그라운드</b>. 인벤토리 변화 시에만 재평가.」 —
 * so this runs every tick but does real work only when the inventory fingerprint changes.</p>
 *
 * <p>Scoring, per ch.14:</p>
 * <ul>
 *   <li>갑옷: {@code 방어도 + 방어 강도 + 인챈트 가치(보호/가시/내구) − 내구 위험}, best per slot.
 *       Material tier is deliberately NOT read by name — ch.14 says 「재질 티어는 방어도 attribute에
 *       반영되므로 attribute 직접 비교(이름 불문)」, which is the same measurement-not-names principle
 *       as 6.1.</li>
 *   <li>근접: {@code 공격력 + 공격 속도 + 인챈트(예리함/강타/휩쓸기)}, with 강타 weighted up when the
 *       target is undead.</li>
 *   <li>원거리: 활 인챈트(힘/무한/펀치) 최고 + 화살 재고 추적.</li>
 *   <li>방어용 블록 재고: 흔한 블록만, 고가치 제외.</li>
 * </ul>
 */
public class BotEquipment {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Durability below this fraction is a risk term in the armour score (ch.14 「− 내구 위험」). */
    private static final double DURABILITY_RISK_LINE = 0.25;
    private static final double DURABILITY_RISK_WEIGHT = 4.0;
    private static final double SMITE_WEIGHT_UNDEAD = 2.0;
    private static final double SMITE_WEIGHT_DEFAULT = 0.5;

    private int lastFingerprint = Integer.MIN_VALUE;
    private int blockStock;
    private int arrowStock;
    private String lastDecision = "none";

    /** Common blocks usable as structure material (shares 4.4's block-choice rule). */
    private static boolean isCheapBlock(ItemStack st) {
        return st.is(Items.COBBLESTONE) || st.is(Items.DIRT) || st.is(Items.STONE)
                || st.is(Items.NETHERRACK) || st.is(Items.ANDESITE) || st.is(Items.DIORITE)
                || st.is(Items.GRANITE) || st.is(Items.TUFF) || st.is(Items.DEEPSLATE)
                || st.is(Items.COBBLED_DEEPSLATE) || st.is(Items.GRAVEL) || st.is(Items.SAND);
    }

    public int blockStock() {
        return blockStock;
    }

    public int arrowStock() {
        return arrowStock;
    }

    public String lastDecision() {
        return lastDecision;
    }

    /** Force a re-evaluation on the next tick (used by tests and by explicit gear handouts). */
    public void invalidate() {
        lastFingerprint = Integer.MIN_VALUE;
    }

    /** Called every tick; does work only when the inventory changed (ch.14 「인벤토리 변화 시에만」). */
    public void tick(AICompanionBot bot) {
        // 15.1: an eat in flight owns the main hand. Vanilla's updatingUsingItem cancels the use as
        // soon as the held stack stops matching useItem, so swapping a weapon in mid-bite would
        // consume the animation and restore nothing.
        if (bot.living().isEating()) {
            return;
        }
        int fp = fingerprint(bot);
        if (fp == lastFingerprint) {
            return;
        }
        lastFingerprint = fp;
        reevaluate(bot, null);
    }

    /**
     * Re-evaluate and equip. {@code meleeTarget} lets the melee score weight 강타 for undead targets;
     * null means "no target context", which uses the default weight.
     */
    public void reevaluate(AICompanionBot bot, @Nullable LivingEntity meleeTarget) {
        Inventory inv = bot.getInventory();
        StringBuilder decision = new StringBuilder();

        // --- armour: best-scoring stack per slot ---
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack worn = bot.getItemBySlot(slot);
            double bestScore = worn.isEmpty() ? Double.NEGATIVE_INFINITY : armourScore(worn);
            int bestIdx = -1;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (st.isEmpty() || !(st.getItem() instanceof ArmorItem a) || a.getEquipmentSlot() != slot) {
                    continue;
                }
                double sc = armourScore(st);
                if (sc > bestScore) {
                    bestScore = sc;
                    bestIdx = i;
                }
            }
            if (bestIdx >= 0) {
                ItemStack chosen = inv.getItem(bestIdx).copy();
                inv.setItem(bestIdx, worn.isEmpty() ? ItemStack.EMPTY : worn.copy());
                bot.setItemSlot(slot, chosen);
                decision.append(slot.getName()).append('=')
                        .append(chosen.getItem().toString()).append(' ');
            }
        }

        // --- weapons: best melee and best ranged, melee swapped into the main hand ---
        int bestMelee = -1;
        double bestMeleeScore = Double.NEGATIVE_INFINITY;
        int bestRanged = -1;
        double bestRangedScore = Double.NEGATIVE_INFINITY;
        blockStock = 0;
        arrowStock = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) {
                continue;
            }
            if (st.is(Items.ARROW) || st.is(Items.SPECTRAL_ARROW) || st.is(Items.TIPPED_ARROW)) {
                arrowStock += st.getCount();
            }
            if (isCheapBlock(st)) {
                blockStock += st.getCount();
            }
            if (st.getItem() instanceof BowItem || st.getItem() instanceof CrossbowItem) {
                double sc = rangedScore(st);
                if (sc > bestRangedScore) {
                    bestRangedScore = sc;
                    bestRanged = i;
                }
            } else {
                double sc = meleeScore(st, meleeTarget);
                if (sc > bestMeleeScore) {
                    bestMeleeScore = sc;
                    bestMelee = i;
                }
            }
        }
        ItemStack held = bot.getMainHandItem();
        if (bestMelee >= 0 && meleeScore(held, meleeTarget) < bestMeleeScore) {
            ItemStack chosen = inv.getItem(bestMelee).copy();
            inv.setItem(bestMelee, held.isEmpty() ? ItemStack.EMPTY : held.copy());
            bot.setItemSlot(EquipmentSlot.MAINHAND, chosen);
            decision.append("mainhand=").append(chosen.getItem().toString()).append(' ');
        }
        decision.append("blocks=").append(blockStock).append(" arrows=").append(arrowStock);
        lastDecision = decision.toString();
        LOGGER.info("[EQUIP] {}", lastDecision);
    }

    /** Swap the main hand to the best item of the requested category (ch.14 「교전 모드가 요청하면」). */
    public boolean requestCategory(AICompanionBot bot, boolean ranged, @Nullable LivingEntity target) {
        Inventory inv = bot.getInventory();
        int best = -1;
        double bestScore = ranged ? rangedScore(bot.getMainHandItem())
                : meleeScore(bot.getMainHandItem(), target);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) {
                continue;
            }
            boolean isRangedItem = st.getItem() instanceof BowItem || st.getItem() instanceof CrossbowItem;
            if (isRangedItem != ranged) {
                continue;
            }
            double sc = ranged ? rangedScore(st) : meleeScore(st, target);
            if (sc > bestScore) {
                bestScore = sc;
                best = i;
            }
        }
        if (best < 0) {
            return false;
        }
        ItemStack held = bot.getMainHandItem();
        ItemStack chosen = inv.getItem(best).copy();
        inv.setItem(best, held.isEmpty() ? ItemStack.EMPTY : held.copy());
        bot.setItemSlot(EquipmentSlot.MAINHAND, chosen);
        LOGGER.info("[EQUIP] category swap ranged={} -> {}", ranged, chosen.getItem());
        return true;
    }

    // --- scores (ch.14) ---

    private static double armourScore(ItemStack st) {
        if (st.isEmpty() || !(st.getItem() instanceof ArmorItem a)) {
            return Double.NEGATIVE_INFINITY;
        }
        // Attribute-based, not material-name-based (ch.14 「attribute 직접 비교(이름 불문)」).
        double armour = attrFrom(st, a.getEquipmentSlot(), Attributes.ARMOR);
        double tough = attrFrom(st, a.getEquipmentSlot(), Attributes.ARMOR_TOUGHNESS);
        double ench = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.ALL_DAMAGE_PROTECTION, st)
                + EnchantmentHelper.getItemEnchantmentLevel(Enchantments.THORNS, st) * 0.5
                + EnchantmentHelper.getItemEnchantmentLevel(Enchantments.UNBREAKING, st) * 0.5;
        double risk = 0.0;
        if (st.isDamageableItem() && st.getMaxDamage() > 0) {
            double left = 1.0 - (double) st.getDamageValue() / st.getMaxDamage();
            if (left < DURABILITY_RISK_LINE) {
                risk = (DURABILITY_RISK_LINE - left) / DURABILITY_RISK_LINE * DURABILITY_RISK_WEIGHT;
            }
        }
        return armour + tough + ench - risk;
    }

    private static double meleeScore(ItemStack st, @Nullable LivingEntity target) {
        if (st.isEmpty()) {
            return 0.0;
        }
        double atk = attrFrom(st, EquipmentSlot.MAINHAND, Attributes.ATTACK_DAMAGE);
        double spd = attrFrom(st, EquipmentSlot.MAINHAND, Attributes.ATTACK_SPEED);
        boolean undead = target != null && target.getMobType() == MobType.UNDEAD;
        double ench = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SHARPNESS, st)
                + EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SMITE, st)
                        * (undead ? SMITE_WEIGHT_UNDEAD : SMITE_WEIGHT_DEFAULT)
                + EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SWEEPING_EDGE, st) * 0.5;
        return atk + spd + ench;
    }

    private static double rangedScore(ItemStack st) {
        if (st.isEmpty() || !(st.getItem() instanceof BowItem || st.getItem() instanceof CrossbowItem)) {
            return Double.NEGATIVE_INFINITY;
        }
        return 1.0
                + EnchantmentHelper.getItemEnchantmentLevel(Enchantments.POWER_ARROWS, st)
                + EnchantmentHelper.getItemEnchantmentLevel(Enchantments.INFINITY_ARROWS, st)
                + EnchantmentHelper.getItemEnchantmentLevel(Enchantments.PUNCH_ARROWS, st) * 0.5;
    }

    private static double attrFrom(ItemStack st, EquipmentSlot slot, Attribute attr) {
        Collection<AttributeModifier> mods = st.getAttributeModifiers(slot).get(attr);
        double v = 0.0;
        for (AttributeModifier m : mods) {
            v += m.getAmount();
        }
        return v;
    }

    private static int fingerprint(AICompanionBot bot) {
        Inventory inv = bot.getInventory();
        int h = 17;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            h = h * 31 + (st.isEmpty() ? 0 : st.getItem().hashCode() * 31 + st.getCount());
        }
        for (EquipmentSlot s : EquipmentSlot.values()) {
            ItemStack st = bot.getItemBySlot(s);
            h = h * 31 + (st.isEmpty() ? 0 : st.getItem().hashCode());
        }
        return h;
    }
}
