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

    /** Entity scan radius. Wider than the harness arenas' active area. */
    private static final double ENTITY_RADIUS = 64.0;
    /** Block signature region (stride 1). Covers the area harnesses actually build/modify. */
    private static final int BLOCK_R = 24;
    private static final int BLOCK_Y_LO = -2;
    private static final int BLOCK_Y_HI = 3;

    public record Snapshot(int mobs, int items, int projectiles, int otherEntities, int players,
                           long blockHash, int nonAirBlocks, long dayTime,
                           String botState, String userState) {
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
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -BLOCK_R; dx <= BLOCK_R; dx++) {
            for (int dz = -BLOCK_R; dz <= BLOCK_R; dz++) {
                for (int dy = BLOCK_Y_LO; dy <= BLOCK_Y_HI; dy++) {
                    p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    var state = level.getBlockState(p);
                    int id = net.minecraft.world.level.block.Block.getId(state);
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
                level.getDayTime(), botState, userState);
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
            d.add("blockHash differs");
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
