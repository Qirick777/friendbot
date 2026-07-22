package com.aicompanion.bot.combat;

import com.aicompanion.bot.AICompanionBot;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * Environment manipulation reflex (T4.4 / design ch.11 block placement + ch.12 fall survival R2).
 * Runs in the reflex layer. Two behaviours:
 *
 * <ul>
 *   <li><b>Fall survival (R2)</b>: while falling toward reachable ground, drop water below (top
 *       priority — negates all fall damage), else a fatal-only solid/boat fallback.</li>
 *   <li><b>Creeper wall</b>: on a swelling creeper near the user/bot, place a block on the
 *       creeper→target line (blast attenuation) + self-shield + step out; if the fuse is too short
 *       or no blocks, shield + flee instead.</li>
 * </ul>
 *
 * <p>Blocks/water are placed via a direct server {@code setBlockAndUpdate} + manual inventory
 * consume (not the vanilla {@code useItemOn} path) for fake-player reliability; every placement is
 * confirmed by re-reading {@code getBlockState}/{@code getFluidState}.</p>
 */
public class BotEnvironment {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Fall survival (12).
    private static final float SAFE_FALL = 3.0F;     // vanilla fall damage begins above 3
    private static final float FATAL_FALL = 8.0F;    // "치명적" threshold for the solid/boat fallback
    private static final int WATER_RANGE = 4;        // 물 사거리 (design "약 4블록")
    private static final int WATER_COLUMN = 4;       // fill this many blocks so a fast fall can't skip

    // Creeper wall (11).
    private static final double CREEPER_DETECT = 12.0;
    private static final double BLAST_R = 6.0;       // 폭심 근처 판정
    private static final int MAX_SWELL = 30;         // Creeper.maxSwell
    private static final int PLACE_MIN_TICKS = 6;    // fuse below this → no place, shield+flee

    // Value-aware block selection (11.2 / 4.4): never spend these on a wall.
    private static final Set<Item> HIGH_VALUE = Set.of(
            Items.DIAMOND_BLOCK, Items.GOLD_BLOCK, Items.EMERALD_BLOCK, Items.IRON_BLOCK,
            Items.NETHERITE_BLOCK, Items.ANCIENT_DEBRIS, Items.LAPIS_BLOCK, Items.REDSTONE_BLOCK);
    // Prefer common blocks first.
    private static final Item[] COMMON_BLOCKS = {
            Items.COBBLESTONE, Items.DIRT, Items.NETHERRACK, Items.STONE,
            Items.COBBLED_DEEPSLATE, Items.ANDESITE, Items.GRANITE, Items.DIORITE};

    private boolean waterDeployed;      // one water drop per fall
    @Nullable
    private BlockPos lastWaterPos;
    @Nullable
    private BlockPos lastWallPos;

    @Nullable
    public BlockPos lastWaterPos() {
        return lastWaterPos;
    }

    @Nullable
    public BlockPos lastWallPos() {
        return lastWallPos;
    }

    // ===================== Fall survival (R2) — F1..F6 =====================

    public void tickFallSurvival(AICompanionBot bot) {
        if (bot.onGround() || bot.isInWater()) {
            waterDeployed = false; // reset for the next fall
            return;
        }
        // fallDistance is now real: AICompanionBot drives ServerPlayer.doCheckFallDamage() each tick
        // (the connection-less fake player has no client to drive it — like doTick), so both the
        // R2 trigger and vanilla fall damage work off genuine accumulation.
        double dy = bot.getDeltaMovement().y;
        if (dy >= 0.0 || bot.fallDistance <= SAFE_FALL) {
            return; // F1: must be descending past the safe fall
        }
        ServerLevel level = (ServerLevel) bot.level();
        int groundDist = groundDistanceBelow(bot, level); // F2
        if (groundDist < 0 || groundDist > WATER_RANGE) {
            return; // ground not reachable "right now"
        }
        BlockPos feet = bot.blockPosition();
        BlockPos placeAt = feet.below(groundDist); // the air block just above the ground

        // F3: water bucket → top priority (負 damage possibility is enough).
        int wb = findItem(bot, Items.WATER_BUCKET);
        if (wb >= 0) {
            if (!waterDeployed) {
                deployWaterColumn(level, placeAt);
                consumeWaterBucket(bot, wb);
                waterDeployed = true;
                lastWaterPos = placeAt;
                LOGGER.info("[ENV] R2 water at {} groundDist={} fallDist={} isWater={}",
                        placeAt, groundDist, fmt(bot.fallDistance),
                        level.getFluidState(placeAt).is(FluidTags.WATER));
            }
            return;
        }

        // F4/F5: fatal-only fallback (물 없을 때).
        if (bot.fallDistance >= FATAL_FALL) {
            int solid = findFirstItem(bot, Items.SLIME_BLOCK, Items.HAY_BLOCK);
            if (solid >= 0 && placeSolid(bot, level, placeAt, solid)) {
                LOGGER.info("[ENV] R2 fatal fallback solid at {} fallDist={}", placeAt, fmt(bot.fallDistance));
                return;
            }
            int boat = findBoat(bot);
            if (boat >= 0) {
                deployBoat(bot, level, placeAt, boat);
                LOGGER.info("[ENV] R2 fatal fallback boat at {}", placeAt);
                return;
            }
        }
        // F6: 감수 (nothing to deploy).
    }

    private void deployWaterColumn(ServerLevel level, BlockPos base) {
        // Fill an upward column so a high-velocity fall's AABB always overlaps water this tick.
        for (int i = 0; i < WATER_COLUMN; i++) {
            BlockPos p = base.above(i);
            if (level.getFluidState(p).isEmpty() && level.getBlockState(p).canBeReplaced()) {
                level.setBlockAndUpdate(p, Blocks.WATER.defaultBlockState());
            }
        }
    }

    /** First solid ground below the feet: returns air-gap distance (0 = solid directly below feet). */
    private int groundDistanceBelow(AICompanionBot bot, ServerLevel level) {
        BlockPos feet = bot.blockPosition();
        for (int d = 1; d <= WATER_RANGE + 1; d++) {
            BlockPos p = feet.below(d);
            BlockState s = level.getBlockState(p);
            if (!s.getCollisionShape(level, p).isEmpty()) {
                return d - 1; // air blocks between feet and the solid top
            }
        }
        return -1;
    }

    // ===================== Creeper wall (11) — E1..E10 =====================

    /** @return true if the creeper reflex took over movement this tick. */
    public boolean tickCreeperDefense(AICompanionBot bot) {
        ServerLevel level = (ServerLevel) bot.level();
        Creeper creeper = nearestSwellingCreeper(bot, level); // E1
        if (creeper == null) {
            return false;
        }
        // E2: is the user or the bot near the blast?
        ServerPlayer user = bot.perception().user;
        Entity protectee = null;
        if (user != null && creeper.distanceTo(user) <= BLAST_R) {
            protectee = user;
        } else if (creeper.distanceTo(bot) <= BLAST_R) {
            protectee = bot;
        }
        if (protectee == null) {
            return false;
        }

        int r = fuseRemaining(creeper);
        int blockSlot = findBuildingBlock(bot); // E9 value-aware
        boolean canPlace = blockSlot >= 0 && r >= PLACE_MIN_TICKS;

        if (canPlace) {
            BlockPos wall = wallPos(creeper, protectee, level); // E3/E10
            if (wall != null) {
                BlockState state = blockStateOf(bot, blockSlot);
                level.setBlockAndUpdate(wall, state);
                consumeOne(bot, blockSlot);
                lastWallPos = wall;
                LOGGER.info("[ENV] creeper WALL at {} r={} block={} onLine={} nowSolid={}",
                        wall, r, state.getBlock(),
                        onSegment(wall, creeper.position(), protectee.position()),
                        !level.getBlockState(wall).getCollisionShape(level, wall).isEmpty());
            }
            selfProtect(bot);                 // E4
            stepAwayFrom(bot, creeper);       // E5
            return true;
        }

        // E6: fuse too short OR no blocks → shield + flee (no placement).
        raiseShield(bot);
        stepAwayFrom(bot, creeper);
        LOGGER.info("[ENV] creeper fuse low/no-block r={} hasBlock={} -> shield+flee (isBlocking={})",
                r, blockSlot >= 0, bot.isBlocking());
        return true;
    }

    @Nullable
    private Creeper nearestSwellingCreeper(AICompanionBot bot, ServerLevel level) {
        AABB box = bot.getBoundingBox().inflate(CREEPER_DETECT);
        Creeper best = null;
        double bestSq = Double.MAX_VALUE;
        for (Creeper c : level.getEntitiesOfClass(Creeper.class, box, Entity::isAlive)) {
            if (c.getSwellDir() > 0 || c.isIgnited()) { // E1: ignited/swelling
                double sq = c.distanceToSqr(bot);
                if (sq < bestSq) {
                    bestSq = sq;
                    best = c;
                }
            }
        }
        return best;
    }

    private static int fuseRemaining(Creeper c) {
        // getSwelling = swell / (maxSwell-2); remaining ticks = maxSwell - swell.
        float swell = c.getSwelling(1.0F) * (MAX_SWELL - 2);
        return Math.max(0, Math.round(MAX_SWELL - swell));
    }

    /** Block on the creeper→target line, one step from the target, with a solid support below. */
    @Nullable
    private BlockPos wallPos(Creeper creeper, Entity target, ServerLevel level) {
        Vec3 c = creeper.position();
        Vec3 t = target.position();
        Vec3 dir = c.subtract(t);
        double len = dir.length();
        if (len < 1.0E-6) {
            return null;
        }
        dir = dir.scale(1.0 / len);
        Vec3 spot = t.add(dir.scale(1.5)); // 1.5 blocks from the target toward the creeper
        BlockPos base = BlockPos.containing(spot.x, t.y, spot.z);
        // E10: prefer an air/replaceable cell with a solid block beneath (no mid-air placement).
        for (int dy = 0; dy >= -1; dy--) {
            BlockPos p = base.above(dy);
            boolean replaceable = level.getBlockState(p).canBeReplaced()
                    && level.getFluidState(p).isEmpty();
            boolean support = !level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty();
            if (replaceable && support) {
                return p;
            }
        }
        return replaceableOrNull(level, base);
    }

    @Nullable
    private static BlockPos replaceableOrNull(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).canBeReplaced() ? p : null;
    }

    private void selfProtect(AICompanionBot bot) {
        // E4: shield toward the blast if held; blocks alone already shield the bot's front.
        raiseShield(bot);
    }

    private void raiseShield(AICompanionBot bot) {
        if (bot.getOffhandItem().getItem() == Items.SHIELD && !bot.isUsingItem()) {
            bot.startUsingItem(InteractionHand.OFF_HAND);
        }
    }

    private void stepAwayFrom(AICompanionBot bot, Entity threat) {
        // E5/E6: step outside the ignition radius (face the threat, walk backward).
        double dx = threat.getX() - bot.getX();
        double dz = threat.getZ() - bot.getZ();
        float yaw = Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F);
        bot.setYRot(yaw);
        bot.setYBodyRot(yaw);
        bot.setYHeadRot(yaw);
        bot.zza = -1.0F; // backpedal, keep eyes on the creeper
        bot.xxa = 0.0F;
        bot.setSprinting(false);
        bot.setJumping(false);
    }

    // ===================== inventory / geometry helpers =====================

    private static boolean onSegment(BlockPos p, Vec3 a, Vec3 b) {
        Vec3 pt = new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        Vec3 ab = b.subtract(a);
        double abLen2 = ab.lengthSqr();
        if (abLen2 < 1.0E-9) {
            return false;
        }
        double s = pt.subtract(a).dot(ab) / abLen2;         // projection parameter
        if (s < 0.0 || s > 1.0) {
            return false;                                    // between the two points
        }
        Vec3 proj = a.add(ab.scale(s));
        double distXZ = Math.hypot(pt.x - proj.x, pt.z - proj.z);
        return distXZ < 1.0;                                 // within 1 block of the line (XZ)
    }

    private static int findItem(AICompanionBot bot, Item item) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    private static int findFirstItem(AICompanionBot bot, Item... items) {
        for (Item it : items) {
            int s = findItem(bot, it);
            if (s >= 0) {
                return s;
            }
        }
        return -1;
    }

    /** E9: pick a common building block; never a high-value one. */
    private static int findBuildingBlock(AICompanionBot bot) {
        for (Item common : COMMON_BLOCKS) {
            int s = findItem(bot, common);
            if (s >= 0) {
                return s;
            }
        }
        // Any other non-high-value BlockItem as a fallback.
        Inventory inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.getItem() instanceof BlockItem && !HIGH_VALUE.contains(st.getItem())) {
                return i;
            }
        }
        return -1;
    }

    private static int findBoat(AICompanionBot bot) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() instanceof net.minecraft.world.item.BoatItem) {
                return i;
            }
        }
        return -1;
    }

    private static BlockState blockStateOf(AICompanionBot bot, int slot) {
        Item it = bot.getInventory().getItem(slot).getItem();
        if (it instanceof BlockItem bi) {
            return bi.getBlock().defaultBlockState();
        }
        return Blocks.COBBLESTONE.defaultBlockState();
    }

    private boolean placeSolid(AICompanionBot bot, ServerLevel level, BlockPos pos, int slot) {
        if (!level.getBlockState(pos).canBeReplaced()) {
            return false;
        }
        level.setBlockAndUpdate(pos, blockStateOf(bot, slot));
        consumeOne(bot, slot);
        lastWallPos = pos;
        return true;
    }

    private void deployBoat(AICompanionBot bot, ServerLevel level, BlockPos pos, int slot) {
        Boat boat = new Boat(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        level.addFreshEntity(boat);
        bot.startRiding(boat, true);
        consumeOne(bot, slot);
    }

    private static void consumeOne(AICompanionBot bot, int slot) {
        bot.getInventory().getItem(slot).shrink(1);
    }

    private static void consumeWaterBucket(AICompanionBot bot, int slot) {
        ItemStack s = bot.getInventory().getItem(slot);
        s.shrink(1);
        bot.getInventory().add(new ItemStack(Items.BUCKET)); // water bucket → empty bucket
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
