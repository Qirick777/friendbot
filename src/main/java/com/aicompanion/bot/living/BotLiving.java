package com.aicompanion.bot.living;

import com.aicompanion.bot.AICompanionBot;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * T5.3 생활 기능 — design ch.15, verbatim:
 *
 * <pre>
 * 15.1 배고픔 (백그라운드 자동)
 *   배고픔 수치가 임계 이하 → 인벤토리 최적 식량 섭취
 *     식량 선택: 포만감·포화도 높은 것 우선, 위험 식량(썩은 고기·복어·독감자) 회피
 *     전투 중 억제: 먹는 중 이동 느려짐 → 위급 회복(황금사과) 아니면 안전할 때
 *   포만도 6 이하에서 스프린트 불가(바닐라 값 사용).
 *
 * 15.2 수면 (유저 동조)
 *   유저가 침대에 누움 감지 → 주변 침대 탐색
 *     빈 침대 있음 → 봇도 그 침대에 누움(startSleeping)
 *     침대 없음    → 침대 옆 바닥에 눕는 포즈 연출(렌더 커스터마이즈)
 *   유저 기상 → 봇도 기상
 * </pre>
 *
 * <p>Background layer: it never owns the tick and never drives movement. It sits above the combat
 * branches only so that the sprint clamp is applied to whatever the controllers below asked for.</p>
 */
public class BotLiving {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 「배고픔 수치가 임계 이하」. Vanilla refuses ordinary food at a full bar, so the threshold has to
     * be below 20; 17 is the first level at which a full meal is not wasted (nutrition ≥ 3 is
     * typical). Not derived from any measurement — from the spec's intent that the bot tops up in
     * the background rather than waiting to starve.
     */
    public static final int HUNGER_THRESHOLD = 17;
    /** 「포만도 6 이하에서 스프린트 불가(바닐라 값 사용)」. */
    public static final int SPRINT_MIN_FOOD = 6;
    /** 「주변 침대 탐색」 radius around the sleeping user. */
    public static final int BED_SEARCH = 8;
    /** 「침대 옆 바닥」 — how close the lie-down pose is placed to the user. */
    private static final double LIE_BESIDE_DIST = 2.0;

    /**
     * 「위험 식량(썩은 고기·복어·독감자) 회피」. The named three are the floor; anything whose food
     * properties carry a harmful effect is refused by the same rule, so a modded or overlooked bad
     * food does not slip through a hard-coded list.
     */
    private static final Set<Item> NAMED_DANGEROUS = Set.of(
            Items.ROTTEN_FLESH, Items.PUFFERFISH, Items.POISONOUS_POTATO);

    private boolean eating;
    private int eatTicks;
    @Nullable
    private Item lastEaten;
    private int lastFoodBefore = -1;
    private boolean sleeping;
    private boolean lyingBeside;
    @Nullable
    private BlockPos occupiedBed;

    /** True while an eat is in progress — the equipment manager must not swap the food out. */
    public boolean isEating() {
        return eating;
    }

    @Nullable
    public Item lastEaten() {
        return lastEaten;
    }

    /** 15.2: the bot is in a bed. */
    public boolean isSleepingInBed() {
        return sleeping;
    }

    /** 15.2 fallback: no bed was free, so the bot lies on the floor beside the user. */
    public boolean isLyingBeside() {
        return lyingBeside;
    }

    @Nullable
    public BlockPos occupiedBed() {
        return occupiedBed;
    }

    public void reset() {
        eating = false;
        eatTicks = 0;
        lastEaten = null;
        lastFoodBefore = -1;
        sleeping = false;
        lyingBeside = false;
        occupiedBed = null;
    }

    /**
     * Background tick. Never returns ownership of the tick. The sprint clamp is NOT here — it has
     * to run after the controllers have set their movement inputs, so it lives in
     * {@link #applySprintClamp} which the bot calls at the end of its branch chain.
     */
    public void tick(AICompanionBot bot) {
        tickSleep(bot);
        tickHunger(bot);
    }

    // ---------------------------------------------------------------- 15.1 배고픔

    private void tickHunger(AICompanionBot bot) {
        if (sleeping || lyingBeside) {
            return;
        }
        // An eat already in flight: vanilla's updatingUsingItem drives it to completion. Watch for
        // the food bar actually moving so "먹었다" is a value change, not a call.
        if (eating) {
            eatTicks++;
            if (!bot.isUsingItem()) {
                eating = false;
                int after = bot.getFoodData().getFoodLevel();
                LOGGER.info("[LIVING] eat finished item={} food={}->{} ticks={}",
                        lastEaten, lastFoodBefore, after, eatTicks);
            }
            return;
        }

        int food = bot.getFoodData().getFoodLevel();
        if (food > HUNGER_THRESHOLD) {
            return;
        }
        // 「전투 중 억제 … 위급 회복(황금사과) 아니면 안전할 때」. Emergency healing is BotSurvival's
        // job (it owns the golden apple); this layer simply stands down while fighting.
        if (inCombat(bot)) {
            return;
        }

        int slot = bestFoodSlot(bot);
        if (slot < 0) {
            return;
        }
        ItemStack food_ = bot.getInventory().getItem(slot);
        lastEaten = food_.getItem();
        lastFoodBefore = food;
        // Put it in the main hand: vanilla's updatingUsingItem cancels the use the moment the held
        // stack stops matching useItem, so the food has to be the held item for the whole animation.
        bot.getInventory().selected = slot < 9 ? slot : bot.getInventory().selected;
        if (slot >= 9) {
            ItemStack held = bot.getInventory().getItem(bot.getInventory().selected);
            bot.getInventory().setItem(slot, held);
            bot.getInventory().setItem(bot.getInventory().selected, food_);
        }
        bot.startUsingItem(InteractionHand.MAIN_HAND);
        eating = true;
        eatTicks = 0;
        LOGGER.info("[LIVING] start eating {} at food={} (threshold={})",
                lastEaten, food, HUNGER_THRESHOLD);
    }

    /**
     * 「식량 선택: 포만감·포화도 높은 것 우선, 위험 식량 회피」. Score is the actual restore: nutrition
     * plus the saturation it grants (vanilla saturation = nutrition × modifier × 2).
     */
    private static int bestFoodSlot(AICompanionBot bot) {
        int best = -1;
        double bestScore = -1;
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            ItemStack st = bot.getInventory().getItem(i);
            if (st.isEmpty() || !st.getItem().isEdible()) {
                continue;
            }
            if (isDangerous(st.getItem())) {
                continue;
            }
            FoodProperties fp = st.getItem().getFoodProperties();
            if (fp == null) {
                continue;
            }
            double score = fp.getNutrition() + fp.getNutrition() * fp.getSaturationModifier() * 2.0;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** 위험 식량: the three named in 15.1, plus anything carrying a harmful effect. */
    public static boolean isDangerous(Item item) {
        if (NAMED_DANGEROUS.contains(item)) {
            return true;
        }
        FoodProperties fp = item.getFoodProperties();
        if (fp == null) {
            return false;
        }
        for (var pair : fp.getEffects()) {
            MobEffectInstance inst = pair.getFirst();
            if (inst == null) {
                continue;
            }
            MobEffect effect = inst.getEffect();
            if (effect != null && !effect.isBeneficial()) {
                return true;
            }
        }
        return false;
    }

    private static boolean inCombat(AICompanionBot bot) {
        return bot.meleeCombat().hasTarget() || bot.rangedCombat().hasTarget();
    }

    /**
     * 「포만도 6 이하에서 스프린트 불가(바닐라 값 사용)」. Vanilla enforces this on the CLIENT
     * (LocalPlayer.aiStep); the connection-less bot never runs that code, so the clamp lives here.
     * It runs last so it overrides whatever controller asked to sprint this tick.
     */
    public static void applySprintClamp(AICompanionBot bot) {
        if (bot.getFoodData().getFoodLevel() <= SPRINT_MIN_FOOD && bot.isSprinting()) {
            bot.setSprinting(false);
        }
    }

    // ---------------------------------------------------------------- 15.2 수면

    private void tickSleep(AICompanionBot bot) {
        ServerPlayer user = bot.perception().user;
        boolean userAsleep = user != null && user.isSleeping();

        if (!userAsleep) {
            // 「유저 기상 → 봇도 기상」
            if (sleeping || lyingBeside) {
                wake(bot);
            }
            return;
        }
        if (sleeping || lyingBeside) {
            return;
        }

        ServerLevel level = (ServerLevel) bot.level();
        BlockPos bed = findFreeBed(level, user.blockPosition(), user);
        if (bed != null) {
            bot.startSleeping(bed);
            // startSleeping does not flip the block state; do it so a second sleeper (or a re-scan)
            // does not pick the same bed.
            BlockState st = level.getBlockState(bed);
            if (st.getBlock() instanceof BedBlock && st.hasProperty(BedBlock.OCCUPIED)) {
                level.setBlock(bed, st.setValue(BedBlock.OCCUPIED, Boolean.TRUE), 3);
            }
            occupiedBed = bed;
            sleeping = true;
            LOGGER.info("[LIVING] sleep in bed at {} (user asleep at {})", bed, user.blockPosition());
            return;
        }
        // 「침대 없음 → 침대 옆 바닥에 눕는 포즈 연출」. Server-side that is the sleeping pose anchored
        // at the bot's own block, with no bed state touched.
        BlockPos beside = bot.blockPosition();
        if (bot.position().distanceTo(user.position()) > LIE_BESIDE_DIST + 6.0) {
            // Far away: still lie down where it stands — 16장's follow behaviour owns approaching.
            beside = bot.blockPosition();
        }
        bot.setSleepingPos(beside);
        bot.setPose(Pose.SLEEPING);
        lyingBeside = true;
        LOGGER.info("[LIVING] no free bed within {} — lying beside at {}", BED_SEARCH, beside);
    }

    private void wake(AICompanionBot bot) {
        if (sleeping) {
            bot.stopSleeping();
            if (occupiedBed != null && bot.level() instanceof ServerLevel level) {
                BlockState st = level.getBlockState(occupiedBed);
                if (st.getBlock() instanceof BedBlock && st.hasProperty(BedBlock.OCCUPIED)) {
                    level.setBlock(occupiedBed, st.setValue(BedBlock.OCCUPIED, Boolean.FALSE), 3);
                }
            }
        }
        if (lyingBeside) {
            bot.clearSleepingPos();
            bot.setPose(Pose.STANDING);
        }
        LOGGER.info("[LIVING] wake (bed={} lying={})", sleeping, lyingBeside);
        sleeping = false;
        lyingBeside = false;
        occupiedBed = null;
    }

    /** 「주변 침대 탐색」 — nearest unoccupied bed block within {@link #BED_SEARCH} of the user. */
    @Nullable
    private static BlockPos findFreeBed(ServerLevel level, BlockPos around, ServerPlayer user) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos userBed = user.getSleepingPos().orElse(null);
        for (int dx = -BED_SEARCH; dx <= BED_SEARCH; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -BED_SEARCH; dz <= BED_SEARCH; dz++) {
                    BlockPos p = around.offset(dx, dy, dz);
                    BlockState st = level.getBlockState(p);
                    if (!(st.getBlock() instanceof BedBlock)) {
                        continue;
                    }
                    if (st.hasProperty(BedBlock.OCCUPIED) && st.getValue(BedBlock.OCCUPIED)) {
                        continue;
                    }
                    if (userBed != null && sameBed(level, p, userBed)) {
                        continue; // 「빈 침대」 — not the one the user is in
                    }
                    double d = p.distSqr(around);
                    if (d < bestD) {
                        bestD = d;
                        best = p.immutable();
                    }
                }
            }
        }
        return best;
    }

    /** Two positions belong to the same bed if they are the head/foot halves of one another. */
    private static boolean sameBed(ServerLevel level, BlockPos a, BlockPos b) {
        if (a.equals(b)) {
            return true;
        }
        BlockState sa = level.getBlockState(a);
        if (!(sa.getBlock() instanceof BedBlock)) {
            return false;
        }
        return a.relative(BedBlock.getConnectedDirection(sa)).equals(b);
    }
}
