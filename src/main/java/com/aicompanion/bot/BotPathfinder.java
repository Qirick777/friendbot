package com.aicompanion.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Grid A* over player-hitbox movement rules (T2.3 / design 4.3). Basic terrain only —
 * ability-aware neighbors (water/boat/place) come in T5.1.
 *
 * <p>Node = block position of the bot's feet. A position is "standable" when the block
 * below supports it and the two blocks at foot+head are clear (0.6×1.8 hitbox fits a
 * 1-wide, 2-tall column). Neighbors: 4 horizontal, +1 step up, and drops down to the first
 * standable block (≤ maxSafeFall free, beyond that costly, too far forbidden). Lava/fire/
 * cactus/magma are forbidden.</p>
 *
 * <p>The search is <b>stepable</b> ({@link Search#step}) so it can be split across ticks,
 * and bounded by an expansion budget (give-up → null) to prevent lag.</p>
 */
public final class BotPathfinder {

    public static final int BASE_COST = 10;
    private static final int STEP_UP_COST = 4;   // extra for a 1-block climb
    private static final int DROP_COST = 3;      // per block of a safe drop
    private static final int RISKY_DROP_COST = 60; // flat penalty for drops beyond maxSafeFall
    private static final int MAX_SAFE_FALL = 3;
    private static final int MAX_FALL_SEARCH = 8; // beyond this a drop is a lethal cliff (forbidden)
    private static final int DEFAULT_MAX_EXPANSIONS = 8000;

    private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private BotPathfinder() {
    }

    /** Convenience: run a bounded search to completion in one call. Null if unreachable. */
    @Nullable
    public static List<BlockPos> findPathSync(Level level, BlockPos start, BlockPos goal, int maxExpansions) {
        Search s = new Search(level, start, goal, maxExpansions);
        while (!s.isDone()) {
            s.step(maxExpansions);
        }
        return s.result();
    }

    @Nullable
    public static List<BlockPos> findPathSync(Level level, BlockPos start, BlockPos goal) {
        return findPathSync(level, start, goal, DEFAULT_MAX_EXPANSIONS);
    }

    // ---- terrain rules ------------------------------------------------------

    private static boolean blocked(Level level, BlockPos p) {
        return level.getBlockState(p).blocksMotion();
    }

    private static boolean hazard(Level level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.is(Blocks.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.CACTUS) || s.is(Blocks.MAGMA_BLOCK);
    }

    /** Player can stand with feet at p: support below, 2 clear blocks, no hazard on/below. */
    static boolean standable(Level level, BlockPos p) {
        if (!blocked(level, p.below())) {
            return false; // no floor support
        }
        if (blocked(level, p) || blocked(level, p.above())) {
            return false; // hitbox (1×2) does not fit
        }
        if (hazard(level, p) || hazard(level, p.below())) {
            return false; // standing on/in a hazard
        }
        return true;
    }

    /** Snap a goal to the nearest standable block in its column (down a few), or null. */
    @Nullable
    static BlockPos snapToGround(Level level, BlockPos goal) {
        for (int dy = 0; dy <= MAX_FALL_SEARCH; dy++) {
            BlockPos q = goal.below(dy);
            if (standable(level, q)) {
                return q;
            }
        }
        return null;
    }

    // ---- stepable search ----------------------------------------------------

    private static final class Node {
        final BlockPos pos;
        final int g;
        final int f;
        @Nullable
        final Node parent;

        Node(BlockPos pos, int g, int f, @Nullable Node parent) {
            this.pos = pos;
            this.g = g;
            this.f = f;
            this.parent = parent;
        }
    }

    public static final class Search {
        private final Level level;
        private final BlockPos start;
        @Nullable
        private final BlockPos goal;
        private final int maxExpansions;

        private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Integer.compare(a.f, b.f));
        private final Map<Long, Integer> bestG = new HashMap<>();
        private int expansions;
        private boolean done;
        @Nullable
        private List<BlockPos> result;

        public Search(Level level, BlockPos start, BlockPos goal, int maxExpansions) {
            this.level = level;
            this.maxExpansions = maxExpansions;

            BlockPos s = standable(level, start) ? start : snapToGround(level, start);
            BlockPos g = (goal == null) ? null : (standable(level, goal) ? goal : snapToGround(level, goal));
            this.start = s;
            this.goal = g;

            if (s == null || g == null) {
                this.done = true;
                this.result = null;
                return;
            }
            Node root = new Node(s, 0, heuristic(s, g), null);
            open.add(root);
            bestG.put(s.asLong(), 0);
        }

        public boolean isDone() {
            return done;
        }

        @Nullable
        public List<BlockPos> result() {
            return result;
        }

        public int expansions() {
            return expansions;
        }

        /** Advance up to {@code budget} node expansions this tick. */
        public void step(int budget) {
            if (done) {
                return;
            }
            int localBudget = budget;
            while (localBudget-- > 0) {
                if (open.isEmpty()) {
                    finish(null);
                    return;
                }
                if (expansions >= maxExpansions) {
                    finish(null); // give-up budget exceeded
                    return;
                }
                Node cur = open.poll();
                expansions++;

                if (cur.pos.equals(goal)) {
                    finish(reconstruct(cur));
                    return;
                }
                // Skip stale queue entries (a better g was found later).
                Integer bg = bestG.get(cur.pos.asLong());
                if (bg != null && cur.g > bg) {
                    continue;
                }
                expand(cur);
            }
        }

        private void expand(Node cur) {
            for (int[] d : HORIZONTAL) {
                BlockPos flat = cur.pos.offset(d[0], 0, d[1]);
                // same level
                if (standable(level, flat)) {
                    relax(cur, flat, BASE_COST);
                    continue;
                }
                // step up 1 (2+ forbidden): destination is one higher, head clearance needed
                BlockPos up = cur.pos.offset(d[0], 1, d[1]);
                if (standable(level, up) && !blocked(level, cur.pos.above(2))) {
                    relax(cur, up, BASE_COST + STEP_UP_COST);
                    continue;
                }
                // drop: front column must be clear to walk off, land on first standable below
                if (!blocked(level, flat) && !blocked(level, flat.above())) {
                    for (int k = 1; k <= MAX_FALL_SEARCH; k++) {
                        BlockPos down = cur.pos.offset(d[0], -k, d[1]);
                        if (standable(level, down)) {
                            int cost = (k <= MAX_SAFE_FALL)
                                    ? BASE_COST + k * DROP_COST
                                    : BASE_COST + RISKY_DROP_COST;
                            relax(cur, down, cost);
                            break;
                        }
                        if (blocked(level, down)) {
                            break; // hit a wall going down without a landing
                        }
                    }
                }
            }
        }

        private void relax(Node cur, BlockPos next, int stepCost) {
            int ng = cur.g + stepCost;
            long key = next.asLong();
            Integer known = bestG.get(key);
            if (known != null && ng >= known) {
                return;
            }
            bestG.put(key, ng);
            open.add(new Node(next, ng, ng + heuristic(next, goal), cur));
        }

        private void finish(@Nullable List<BlockPos> r) {
            this.result = r;
            this.done = true;
        }

        private static List<BlockPos> reconstruct(Node end) {
            List<BlockPos> path = new ArrayList<>();
            for (Node n = end; n != null; n = n.parent) {
                path.add(n.pos);
            }
            Collections.reverse(path);
            return path;
        }
    }

    private static int heuristic(BlockPos a, BlockPos b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        return (dx + dy + dz) * BASE_COST;
    }
}
