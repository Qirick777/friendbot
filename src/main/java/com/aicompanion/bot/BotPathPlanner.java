package com.aicompanion.bot;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Strategic path planner (T2.3). Owns the current goal, runs the A* search incrementally
 * across ticks (budgeted), caches the resulting path, skips recompute while the goal is
 * unchanged, and follows the path node-by-node through the T2.1 movement executor.
 */
public class BotPathPlanner {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int EXPANSIONS_PER_TICK = 1500; // tick-split budget
    private static final int MAX_EXPANSIONS = 8000;      // give-up budget
    private static final double NODE_REACH_H = 0.7;      // horizontal arrival at a node

    @Nullable
    private BlockPos goal;
    @Nullable
    private BotPathfinder.Search search;
    @Nullable
    private List<BlockPos> path;
    private int pathIndex;
    private boolean unreachable;

    /** Request a path to goal. No-op if the goal is unchanged (design "목표 미변 시 스킵"). */
    public void setGoal(BlockPos newGoal) {
        if (goal != null && goal.equals(newGoal) && !unreachable) {
            return; // unchanged → keep cached path/search
        }
        this.goal = newGoal;
        this.search = null;
        this.path = null;
        this.pathIndex = 0;
        this.unreachable = false;
    }

    public void stop() {
        this.goal = null;
        this.search = null;
        this.path = null;
        this.pathIndex = 0;
        this.unreachable = false;
    }

    public boolean hasGoal() {
        return goal != null;
    }

    public boolean isUnreachable() {
        return unreachable;
    }

    public boolean arrived() {
        return goal == null && path == null && !unreachable;
    }

    /** Called every tick from {@link AICompanionBot#tick()} BEFORE the movement executor. */
    public void tick(AICompanionBot bot) {
        if (goal == null || unreachable) {
            return;
        }
        Level level = bot.level();

        // 1) Search (incremental, tick-split).
        if (path == null) {
            if (search == null) {
                search = new BotPathfinder.Search(level, bot.blockPosition(), goal, MAX_EXPANSIONS);
            }
            search.step(EXPANSIONS_PER_TICK);
            if (search.isDone()) {
                path = search.result();
                pathIndex = 0;
                if (path == null) {
                    unreachable = true;
                    bot.mover().stop();
                    LOGGER.info("[BOT] path unreachable to {} (expansions={})", goal, search.expansions());
                }
                search = null;
            }
            return; // start following next tick
        }

        // 2) Follow the path via the movement executor.
        followPath(bot);
    }

    private void followPath(AICompanionBot bot) {
        if (path == null || pathIndex >= path.size()) {
            arriveAtGoal(bot);
            return;
        }
        BlockPos node = path.get(pathIndex);
        double tx = node.getX() + 0.5;
        double tz = node.getZ() + 0.5;
        bot.mover().moveTo(tx, tz);

        double dx = tx - bot.getX();
        double dz = tz - bot.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        boolean yClose = Math.abs(bot.getY() - node.getY()) <= 1.0;

        if (horiz <= NODE_REACH_H && yClose) {
            pathIndex++;
            if (pathIndex >= path.size()) {
                arriveAtGoal(bot);
            }
        }
    }

    private void arriveAtGoal(AICompanionBot bot) {
        bot.mover().stop();
        this.path = null;
        this.goal = null;
        this.pathIndex = 0;
    }
}
