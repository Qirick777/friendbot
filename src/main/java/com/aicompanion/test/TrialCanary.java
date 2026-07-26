package com.aicompanion.test;

import com.aicompanion.bot.AICompanionBot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * TRIAL ISOLATION CONTRACT.
 *
 * <p>Two state leaks were found one entity at a time: the bot ({@code resetBot}) and the second fake
 * player ({@code resetUser}). Both surfaced only because a harness happened to fail legibly —
 * {@code bot_path_reach} failing twice at the identical coordinate, {@code bot_escape_ride}
 * reporting {@code userMoved:0.00} twice. Patching the next candidate as each is discovered
 * guarantees the one after it is discovered the same way, so the leak is closed as a CLASS: every
 * repeat trial must begin from the same state as the first, checked with values.</p>
 *
 * <p>Three nested regions, each with a different job — the split is measured, not stylistic:</p>
 * <ul>
 *   <li><b>Judged</b> = {@code arenaBounds} ∩ ±{@link #JUDGE_R}, shrunk 1 inward. A mismatch here
 *       fails the trial. It must never exceed what the harness actually builds: with a fixed ±8,
 *       {@code protect_priority} (builds dx −6..+10) and {@code creeper_wall} (dx −6..+8, dz ±4)
 *       were being asked to guarantee ground they never touched, and water intruding there failed
 *       them (9 and 14 core blocks, moving each trial).</li>
 *   <li><b>Restored</b> = the harness's declared {@code arenaBounds}. Wider than judged so real
 *       leftovers are still cleaned — {@code bot_catch_fall}'s water sat at dx −24 and restoring it
 *       took that harness from 1/3 to 3/3. Changes here are logged, not fatal, because fluid flows
 *       back in from surrounding terrain every trial (scheduled ticks, so {@code randomTickSpeed=0}
 *       does not stop them): measured 149→119 blocks at protect_priority, 67~117 at kite_flip.</li>
 *   <li><b>Beyond</b> = a {@link #GUARD_MARGIN} shell outside the declaration. Logged only. Its job
 *       is to catch an under-declared box the same way values caught the under-sized ±24 default,
 *       instead of trusting that someone read the harness source correctly.</li>
 * </ul>
 */
public final class TrialCanary {

    /** Judged core half-extent, before intersecting with the harness's own declared box. */
    private static final int JUDGE_R = 8;
    /** How far outside the declaration the guard shell looks (logged, never judged). */
    private static final int GUARD_MARGIN = 12;
    private static final int BLOCK_Y_LO = -1;
    private static final int BLOCK_Y_HI = 3;
    private static final int Y_SPAN = BLOCK_Y_HI - BLOCK_Y_LO + 1;

    public record Snapshot(int mobs, int items, int projectiles, int otherEntities, int players,
                           int nonAirBlocks, long dayTime, String botState, String userState,
                           int[] blockIds, int[] guardIds, int[] bounds, int[] built,
                           long scanNanos) {
    }

    private TrialCanary() {
    }

    /** Entity sweep radius for a harness: always covers its own declared box. */
    public static double sweepRadius(int[] b) {
        int max = Math.max(Math.max(Math.abs(b[0]), Math.abs(b[1])),
                Math.max(Math.abs(b[2]), Math.abs(b[3])));
        return Math.max(64.0, max + GUARD_MARGIN + 4.0);
    }

    /**
     * Remove entities that arena construction and arena RESTORE produce, neither of which is harness
     * state. Item drops come from breaking natural blocks on the first trial only. Falling blocks are
     * worse and were self-inflicted: restore rewrites baseline blocks, and a gravity block restored
     * without support becomes a {@link FallingBlockEntity} — measured as
     * {@code entities[creeper=1,player=2] -> [creeper=1,falling_block=2,player=2]} in
     * {@code bot_creeper_wall}. The repair mechanism was manufacturing the leak it exists to remove.
     */
    public static void sweepConstructionDebris(ServerLevel level, BlockPos origin, int[] bounds) {
        sweepConstructionDebris(level, origin, bounds, false);
    }

    /**
     * @param itemsAreSubject when true, item entities are NOT swept and are excluded from the
     *     canary comparison. Needed by tests whose subject IS an item on the ground (ch.17 자원
     *     조달): the sweep runs after {@code setup()}, so it deleted the very stack the test had
     *     just placed — measured as {@code itemEntityGone:true, fetchTicks:0} with the canary
     *     baseline reporting {@code items=0}. The exemption is declared per test, not global, so
     *     every other harness keeps the strict contract.
     */
    public static void sweepConstructionDebris(ServerLevel level, BlockPos origin, int[] bounds,
                                               boolean itemsAreSubject) {
        AABB box = new AABB(origin).inflate(sweepRadius(bounds));
        if (!itemsAreSubject) {
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) {
                e.discard();
            }
        }
        for (FallingBlockEntity e : level.getEntitiesOfClass(FallingBlockEntity.class, box)) {
            e.discard();
        }
    }

    public static Snapshot capture(ServerLevel level, BlockPos origin, AICompanionBot bot,
                                   int[] bounds) {
        return capture(level, origin, bot, bounds, bounds);
    }

    /**
     * @param built the footprint the harness actually constructs (P-1). The judged core is the
     *     intersection of this with the declared box and ±{@link #JUDGE_R}, shrunk one inward.
     *     Everything the harness did not build is restored and logged but never judged.
     */
    public static Snapshot capture(ServerLevel level, BlockPos origin, AICompanionBot bot,
                                   int[] bounds, int[] built) {
        long t0 = System.nanoTime();
        AABB box = new AABB(origin).inflate(sweepRadius(bounds));
        int mobs = 0;
        int items = 0;
        int projectiles = 0;
        int other = 0;
        int players = 0;
        java.util.TreeMap<String, Integer> byType = new java.util.TreeMap<>();
        for (Entity e : level.getEntities().getAll()) {
            if (!e.isAlive() || !box.contains(e.position())) {
                continue;
            }
            byType.merge(e.getType().toShortString(), 1, Integer::sum);
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

        int[] ids = new int[size(bounds)];
        int nonAir = scan(level, origin, bounds, ids);
        int[] guard = new int[size(expand(bounds))];
        scan(level, origin, expand(bounds), guard);

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
        StringBuilder bd = new StringBuilder();
        for (var en : byType.entrySet()) {
            if (bd.length() > 0) {
                bd.append(',');
            }
            bd.append(en.getKey()).append('=').append(en.getValue());
        }
        return new Snapshot(mobs, items, projectiles, other, players, nonAir, level.getDayTime(),
                botState, userState + "|types:" + bd, ids, guard, bounds.clone(),
                built == null ? bounds.clone() : built.clone(),
                System.nanoTime() - t0);
    }

    private static int[] expand(int[] b) {
        return new int[]{b[0] - GUARD_MARGIN, b[1] + GUARD_MARGIN,
                b[2] - GUARD_MARGIN, b[3] + GUARD_MARGIN};
    }

    private static int size(int[] b) {
        return (b[1] - b[0] + 1) * (b[3] - b[2] + 1) * Y_SPAN;
    }

    private static int scan(ServerLevel level, BlockPos origin, int[] b, int[] out) {
        int at = 0;
        int nonAir = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = b[0]; dx <= b[1]; dx++) {
            for (int dz = b[2]; dz <= b[3]; dz++) {
                for (int dy = BLOCK_Y_LO; dy <= BLOCK_Y_HI; dy++) {
                    p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    var state = level.getBlockState(p);
                    out[at++] = Block.getId(state);
                    if (!state.isAir()) {
                        nonAir++;
                    }
                }
            }
        }
        return nonAir;
    }

    /**
     * Restore the declared arena block-for-block. Flags deliberately omit neighbour updates: the
     * default path let restored gravity blocks convert into falling-block entities.
     */
    public static void restore(ServerLevel level, BlockPos origin, Snapshot base) {
        if (base == null || base.blockIds() == null) {
            return;
        }
        int[] b = base.bounds();
        int[] ids = base.blockIds();
        int at = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = b[0]; dx <= b[1]; dx++) {
            for (int dz = b[2]; dz <= b[3]; dz++) {
                for (int dy = BLOCK_Y_LO; dy <= BLOCK_Y_HI; dy++) {
                    if (at >= ids.length) {
                        return;
                    }
                    p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    var want = Block.stateById(ids[at++]);
                    if (!level.getBlockState(p).equals(want)) {
                        level.setBlock(p, want, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                }
            }
        }
    }

    /** Fatal diff: entity/bot/user state, plus blocks in the judged core only. */
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
        if (base.dayTime() != now.dayTime()) {
            d.add("dayTime " + base.dayTime() + "->" + now.dayTime());
        }
        if (!base.botState().equals(now.botState())) {
            d.add("bot[" + base.botState() + "] -> [" + now.botState() + "]");
        }
        if (!base.userState().equals(now.userState())) {
            d.add("user/types[" + base.userState() + "] -> [" + now.userState() + "]");
        }
        String core = blockDiff(base, now, Region.JUDGED);
        if (!core.isEmpty()) {
            d.add(core);
        }
        return String.join("; ", d);
    }

    /** Ring 1: inside the declared arena, outside the judged core. Restored, logged, not fatal. */
    public static String outerDiff(Snapshot base, Snapshot now) {
        return blockDiff(base, now, Region.RING);
    }

    /** Ring 2: outside the declaration entirely. Logged only — the guard on the declaration itself. */
    public static String guardDiff(Snapshot base, Snapshot now) {
        int[] a = base.guardIds();
        int[] b = now.guardIds();
        if (a == null || b == null || a.length != b.length) {
            return "";
        }
        int[] eb = expand(base.bounds());
        int[] inner = base.bounds();
        int changed = 0;
        String first = "?";
        List<String> detail = new ArrayList<>();
        int at = 0;
        for (int dx = eb[0]; dx <= eb[1]; dx++) {
            for (int dz = eb[2]; dz <= eb[3]; dz++) {
                for (int dy = BLOCK_Y_LO; dy <= BLOCK_Y_HI; dy++, at++) {
                    boolean insideDeclared = dx >= inner[0] && dx <= inner[1]
                            && dz >= inner[2] && dz <= inner[3];
                    if (insideDeclared || a[at] == b[at]) {
                        continue;
                    }
                    changed++;
                    if (changed == 1) {
                        first = String.format("(%+d,%+d,%+d) id %d->%d", dx, dy, dz, a[at], b[at]);
                    }
                    if (detail.size() < DETAIL_CAP) {
                        detail.add(String.format("(%+d,%+d,%+d) %s => %s",
                                dx, dy, dz, desc(a[at]), desc(b[at])));
                    }
                }
            }
        }
        return changed == 0 ? ""
                : "beyondDeclared changed:" + changed + " first" + first
                        + " states{" + String.join(" ; ", detail) + "}"
                        + " (declaration may be too small)";
    }

    private enum Region { JUDGED, RING }

    /** Human-readable judged core, so an empty one is visible in the log rather than inferred. */
    public static String judgedCoreDesc(Snapshot s) {
        int[] bd = s.bounds();
        int[] bt = s.built() == null ? bd : s.built();
        int jx0 = Math.max(Math.max(bd[0], bt[0]), -JUDGE_R) + 1;
        int jx1 = Math.min(Math.min(bd[1], bt[1]), JUDGE_R) - 1;
        int jz0 = Math.max(Math.max(bd[2], bt[2]), -JUDGE_R) + 1;
        int jz1 = Math.min(Math.min(bd[3], bt[3]), JUDGE_R) - 1;
        if (jx0 > jx1 || jz0 > jz1) {
            return "EMPTY (harness builds nothing in range; blocks are restored and logged, not judged)";
        }
        return String.format("x%d..%d z%d..%d (%d cells)", jx0, jx1, jz0, jz1,
                (jx1 - jx0 + 1) * (jz1 - jz0 + 1) * Y_SPAN);
    }

    /**
     * O-2(1): the canary reported coordinates and palette ids three times and the block's IDENTITY
     * zero times, so the same signature (|Δid| = 4 at three different bases: 276, 296, 412) could
     * not be attributed to a cause. {@code Block.getId} is the BLOCKSTATE palette id, so
     * {@code Block.stateById} decodes it back to name + every property — which is the whole of the
     * answer to 「무엇이 무엇으로 바뀌었는가」. Diagnostic only: nothing about the verdict changes.
     */
    private static String desc(int id) {
        try {
            return Block.stateById(id).toString();
        } catch (RuntimeException e) {
            return "id" + id + "(undecodable)";
        }
    }

    /** How many changed cells get their full blockstate printed before the list is truncated. */
    private static final int DETAIL_CAP = 8;

    private static String blockDiff(Snapshot base, Snapshot now, Region region) {
        int[] a = base.blockIds();
        int[] b = now.blockIds();
        if (a == null || b == null || a.length != b.length) {
            return "";
        }
        int[] bd = base.bounds();
        // Judged core: declared box ∩ BUILT box ∩ +/-JUDGE_R, then shrunk one block inward.
        // The outermost ring of a harness's own floor is exactly where neighbouring fluid arrives,
        // and the BUILT term is P-1: judging ground the harness never constructed put world-gen
        // oak leaves inside the core, whose neighbour-derived `distance` no restore can reproduce.
        int[] bt = base.built() == null ? bd : base.built();
        int jx0 = Math.max(Math.max(bd[0], bt[0]), -JUDGE_R) + 1;
        int jx1 = Math.min(Math.min(bd[1], bt[1]), JUDGE_R) - 1;
        int jz0 = Math.max(Math.max(bd[2], bt[2]), -JUDGE_R) + 1;
        int jz1 = Math.min(Math.min(bd[3], bt[3]), JUDGE_R) - 1;
        int changed = 0;
        String first = "?";
        List<String> detail = new ArrayList<>();
        int at = 0;
        for (int dx = bd[0]; dx <= bd[1]; dx++) {
            for (int dz = bd[2]; dz <= bd[3]; dz++) {
                for (int dy = BLOCK_Y_LO; dy <= BLOCK_Y_HI; dy++, at++) {
                    boolean judged = dx >= jx0 && dx <= jx1 && dz >= jz0 && dz <= jz1;
                    if (judged != (region == Region.JUDGED) || a[at] == b[at]) {
                        continue;
                    }
                    changed++;
                    if (changed == 1) {
                        first = String.format("(%+d,%+d,%+d) id %d->%d", dx, dy, dz, a[at], b[at]);
                    }
                    if (detail.size() < DETAIL_CAP) {
                        detail.add(String.format("(%+d,%+d,%+d) %s => %s",
                                dx, dy, dz, desc(a[at]), desc(b[at])));
                    }
                }
            }
        }
        if (changed == 0) {
            return "";
        }
        return (region == Region.JUDGED ? "coreBlocks changed:" : "outerBlocks changed:")
                + changed + " first" + first
                + " states{" + String.join(" ; ", detail)
                + (changed > detail.size() ? " ; +" + (changed - detail.size()) + " more" : "") + "}";
    }
}
