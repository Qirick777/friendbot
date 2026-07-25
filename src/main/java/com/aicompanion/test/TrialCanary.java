package com.aicompanion.test;

import com.aicompanion.bot.AICompanionBot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * TRIAL ISOLATION CONTRACT.
 *
 * <p>Two state leaks were found one entity at a time: the bot (fixed by {@code resetBot}) and the
 * second fake player (fixed by {@code resetUser}). Both were found only because a harness happened
 * to fail in a legible way — {@code bot_path_reach} failing twice at the identical coordinate,
 * {@code bot_escape_ride} reporting {@code userMoved:0.00} twice. The queue of remaining candidates
 * is obvious: leftover mobs, blocks a harness placed (creeper walls, MLG water), dropped items,
 * arrows in flight, the bot's inventory and off-hand, world time, injected layer-2 profiles. Patching
 * them one at a time as each is discovered guarantees the next one is discovered the same way.
 *
 * <p>So the leak is closed as a CLASS instead: every repeat trial must begin from the same state as
 * the first one, and that is checked with values rather than assumed. The baseline is captured right
 * after trial 1's {@code setup()} — the intended starting state — and every later trial is compared
 * against it after its own reset and setup. A mismatch fails that trial immediately and prints the
 * diff, so a leak surfaces as "the canary says 3 mobs survived", not as a mysterious FAIL three
 * harnesses later.</p>
 */
public final class TrialCanary {

    /** Entity scan radius. The manager sweeps the same radius, so the two can never disagree. */
    public static final double ENTITY_RADIUS = 64.0;
    /** Block signature region (stride 1). Covers the area harnesses actually build/modify. */
    private static final int BLOCK_R = 24;
    /**
     * Floor level and up. Harnesses build floors at y-1 and everything else above it; y-2 and below
     * is untouched natural terrain that REACTS to the construction (measured: 544 sub-floor blocks
     * changed id 304->300 after trial 1's platform went down, identically every trial). That is the
     * world settling, not harness state, and including it made the canary cry wolf.
     */
    private static final int BLOCK_Y_LO = -1;
    private static final int BLOCK_Y_HI = 3;

    public record Snapshot(int mobs, int items, int projectiles, int otherEntities, int players,
                           long blockHash, int nonAirBlocks, long dayTime,
                           String botState, String userState, int[] blockIds) {
    }

    /**
     * Remove item entities produced by arena construction. Placing a platform over natural terrain
     * breaks grass/flowers and drops them on the first trial only, which the canary would otherwise
     * report forever as "items 3 -> 0".
     */
    public static void sweepConstructionDebris(ServerLevel level, BlockPos origin) {
        AABB box = new AABB(origin).inflate(ENTITY_RADIUS);
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) {
            e.discard();
        }
    }

    /**
     * Restore the arena to the baseline block-for-block. Detection alone would leave the operator to
     * clean up per harness, which is the entity-at-a-time treadmill this class exists to end: a
     * harness that leaves water where it caught a falling user (measured: 21 blocks at y+1) is
     * cleaned here generically, not by editing that harness.
     */
    public static void restore(ServerLevel level, BlockPos origin, Snapshot base) {
        if (base == null || base.blockIds() == null) {
            return;
        }
        int[] ids = base.blockIds();
        int at = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -BLOCK_R; dx <= BLOCK_R; dx++) {
            for (int dz = -BLOCK_R; dz <= BLOCK_R; dz++) {
                for (int dy = BLOCK_Y_LO; dy <= BLOCK_Y_HI; dy++) {
                    if (at >= ids.length) {
                        return;
                    }
                    p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    var want = net.minecraft.world.level.block.Block.stateById(ids[at++]);
                    if (!level.getBlockState(p).equals(want)) {
                        level.setBlock(p, want, 2);
                    }
                }
            }
        }
    }

    private TrialCanary() {
    }

    public static Snapshot capture(ServerLevel level, BlockPos origin, AICompanionBot bot) {
        AABB box = new AABB(origin).inflate(ENTITY_RADIUS);
        int mobs = 0;
        int items = 0;
        int projectiles = 0;
        int other = 0;
        int players = 0;
        for (Entity e : level.getEntities().getAll()) {
            if (!e.isAlive() || !box.contains(e.position())) {
                continue;
            }
            if (e instanceof ServerPlayer) {
                players++;
            } else if (e instanceof Mob) {
                mobs++;
            } else if (e instanceof ItemEntity) {
                items++;
            } else if (e instanceof Projectile) {
                projectiles++;
            } else {
                other++;
            }
        }

        long hash = 1125899906842597L;
        int nonAir = 0;
        int span = 2 * BLOCK_R + 1;
        int[] ids = new int[span * span * (BLOCK_Y_HI - BLOCK_Y_LO + 1)];
        int at = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -BLOCK_R; dx <= BLOCK_R; dx++) {
            for (int dz = -BLOCK_R; dz <= BLOCK_R; dz++) {
                for (int dy = BLOCK_Y_LO; dy <= BLOCK_Y_HI; dy++) {
                    p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    var state = level.getBlockState(p);
                    int id = net.minecraft.world.level.block.Block.getId(state);
                    ids[at++] = id;
                    hash = hash * 31 + id;
                    if (!state.isAir()) {
                        nonAir++;
                    }
                }
            }
        }

        String botState = "none";
        if (bot != null) {
            botState = String.format("pos(%.0f,%.0f,%.0f) hp%.1f food%d veh%b pass%d inv%b sprint%b",
                    bot.getX(), bot.getY(), bot.getZ(), bot.getHealth(),
                    bot.getFoodData().getFoodLevel(), bot.getVehicle() != null,
                    bot.getPassengers().size(), bot.getInventory().isEmpty(), bot.isSprinting());
        }
        ServerPlayer user = TestUser.current();
        String userState = "none";
        if (user != null && user.isAlive()) {
            userState = String.format("pos(%.0f,%.0f,%.0f) hp%.1f veh%b",
                    user.getX(), user.getY(), user.getZ(), user.getHealth(), user.getVehicle() != null);
        }
        return new Snapshot(mobs, items, projectiles, other, players, hash, nonAir,
                level.getDayTime(), botState, userState, ids);
    }

    /** Empty string when the trial starts from the baseline; otherwise a human-readable diff. */
    public static String diff(Snapshot base, Snapshot now) {
        List<String> d = new ArrayList<>();
        if (base.mobs() != now.mobs()) {
            d.add("mobs " + base.mobs() + "->" + now.mobs());
        }
        if (base.items() != now.items()) {
            d.add("items " + base.items() + "->" + now.items());
        }
        if (base.projectiles() != now.projectiles()) {
            d.add("projectiles " + base.projectiles() + "->" + now.projectiles());
        }
        if (base.otherEntities() != now.otherEntities()) {
            d.add("otherEntities " + base.otherEntities() + "->" + now.otherEntities());
        }
        if (base.players() != now.players()) {
            d.add("players " + base.players() + "->" + now.players());
        }
        if (base.nonAirBlocks() != now.nonAirBlocks()) {
            d.add("nonAirBlocks " + base.nonAirBlocks() + "->" + now.nonAirBlocks());
        }
        if (base.blockHash() != now.blockHash()) {
            // "differs" is not actionable. Say how many sampled positions changed and name one, so a
            // leak points at the block that caused it instead of at a hash.
            int changed = 0;
            String first = "?";
            int[] a = base.blockIds();
            int[] b = now.blockIds();
            int span = 2 * BLOCK_R + 1;
            int yspan = BLOCK_Y_HI - BLOCK_Y_LO + 1;
            for (int i = 0; i < Math.min(a.length, b.length); i++) {
                if (a[i] != b[i]) {
                    changed++;
                    if (changed == 1) {
                        int dx = i / (span * yspan) - BLOCK_R;
                        int rem = i % (span * yspan);
                        int dz = rem / yspan - BLOCK_R;
                        int dy = rem % yspan + BLOCK_Y_LO;
                        first = String.format("(%+d,%+d,%+d) id %d->%d", dx, dy, dz, a[i], b[i]);
                    }
                }
            }
            d.add("blocks changed:" + changed + " first" + first);
        }
        if (base.dayTime() != now.dayTime()) {
            d.add("dayTime " + base.dayTime() + "->" + now.dayTime());
        }
        if (!base.botState().equals(now.botState())) {
            d.add("bot[" + base.botState() + "] -> [" + now.botState() + "]");
        }
        if (!base.userState().equals(now.userState())) {
            d.add("user[" + base.userState() + "] -> [" + now.userState() + "]");
        }
        return String.join("; ", d);
    }
}
