package com.aicompanion.bot.living;

import com.aicompanion.bot.AICompanionBot;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * T5.4 기본 상태 — design ch.16, verbatim:
 *
 * <pre>
 * 평상시:
 *   유저 중심 8블록 반경에서 배회(어슬렁)
 *
 *   유저가 12블록 밖으로 '달리면서' 나감:
 *     → 유저가 어디론가 향하는 것으로 간주, 따라옴
 *     → 유저 3블록 근처서 정지, 다시 배회
 *
 *   유저 정지 → 주변 배회
 *
 *   배고픈 상태(스프린트 불가):
 *     → 유저가 멀어져도 걸어서 따라옴(느려도)
 *     → 완전히 놓쳐도 걷기로 계속 추적(텔레포트 안 함)
 * </pre>
 *
 * <p>This layer only chooses a GOAL. The existing A* planner and movement executor carry it out, so
 * it is wired into the bot's last branch (the one that already runs planner→mover) and nowhere
 * else — combat, survival and rescue all outrank it by owning the tick before it is reached.</p>
 *
 * <p>Note on 「'달리면서' 나감」: the trigger implemented here is distance alone, not "the user is
 * sprinting". The last clause of ch.16 requires the bot to keep walking after the user is
 * 「완전히 놓쳐도」 — lost entirely — and a user who has already run out of sight is no longer
 * observable as running. Gating on an observed sprint would make the follow stop exactly when the
 * spec says it must continue.</p>
 */
public class BotIdle {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 「유저 중심 8블록 반경에서 배회」. */
    public static final double WANDER_RADIUS = 8.0;
    /** 「유저가 12블록 밖으로 … 나감」 → follow. */
    public static final double FOLLOW_ENTER = 12.0;
    /** 「유저 3블록 근처서 정지」 → stop following, wander again. */
    public static final double FOLLOW_EXIT = 3.0;
    /** Ticks to stand still between wander hops (어슬렁, not a treadmill). */
    private static final int WANDER_PAUSE = 40;
    private static final int WANDER_TRIES = 12;

    public enum State { WANDER, FOLLOW }

    private State state = State.WANDER;
    private int pause;
    @Nullable
    private BlockPos wanderGoal;

    public State state() {
        return state;
    }

    public void reset() {
        state = State.WANDER;
        pause = 0;
        wanderGoal = null;
    }

    /** Decides this tick's goal. Called immediately before the planner in the idle branch. */
    public void tick(AICompanionBot bot) {
        ServerPlayer user = bot.perception().user;
        if (user == null) {
            return;
        }
        // 15.2 outranks 배회: a bot that is in a bed (or lying beside one) must not wander off.
        if (bot.living().isSleepingInBed() || bot.living().isLyingBeside()) {
            bot.planner().stop();
            bot.mover().stop();
            return;
        }
        double dist = bot.position().distanceTo(user.position());

        // Hysteresis, both ends given by the spec: enter at 12, leave at 3.
        if (state == State.WANDER && dist > FOLLOW_ENTER) {
            state = State.FOLLOW;
            wanderGoal = null;
            LOGGER.info("[IDLE] WANDER→FOLLOW dist={}", String.format("%.2f", dist));
        } else if (state == State.FOLLOW && dist <= FOLLOW_EXIT) {
            state = State.WANDER;
            bot.planner().stop();
            bot.mover().stop();
            bot.setSprinting(false);
            pause = WANDER_PAUSE;
            LOGGER.info("[IDLE] FOLLOW→WANDER dist={} (정지)", String.format("%.2f", dist));
        }

        if (state == State.FOLLOW) {
            // 「걸어서 따라옴(느려도) … 텔레포트 안 함」 — the goal is always a walkable position and
            // the mover does the walking. Sprint is requested only when the food bar allows it;
            // BotLiving.applySprintClamp is what actually enforces 15.1's ≤6 rule.
            bot.planner().setGoal(standingPos(user));
            bot.setSprinting(bot.getFoodData().getFoodLevel() > BotLiving.SPRINT_MIN_FOOD);
            return;
        }

        // WANDER: 유저 중심 8블록 안을 어슬렁.
        if (pause > 0) {
            pause--;
            bot.planner().stop();
            bot.mover().stop();
            return;
        }
        if (wanderGoal == null || bot.planner().arrived() || bot.planner().isUnreachable()
                || bot.blockPosition().distSqr(wanderGoal) <= 2.0) {
            wanderGoal = pickWanderGoal(bot, user);
            pause = WANDER_PAUSE;
            if (wanderGoal != null) {
                bot.planner().setGoal(wanderGoal);
            }
            return;
        }
        bot.planner().setGoal(wanderGoal);
    }

    /** The block the user is standing in — a walkable A* goal, unlike the block beneath them. */
    private static BlockPos standingPos(ServerPlayer user) {
        return user.blockPosition();
    }

    /** A random standable spot within {@link #WANDER_RADIUS} of the user. */
    @Nullable
    private static BlockPos pickWanderGoal(AICompanionBot bot, ServerPlayer user) {
        Level level = bot.level();
        BlockPos base = user.blockPosition();
        for (int i = 0; i < WANDER_TRIES; i++) {
            int dx = bot.getRandom().nextInt((int) (WANDER_RADIUS * 2) + 1) - (int) WANDER_RADIUS;
            int dz = bot.getRandom().nextInt((int) (WANDER_RADIUS * 2) + 1) - (int) WANDER_RADIUS;
            BlockPos p = base.offset(dx, 0, dz);
            if (dx * dx + dz * dz > WANDER_RADIUS * WANDER_RADIUS) {
                continue;
            }
            BlockPos below = p.below();
            boolean floor = !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
            boolean clear = level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                    && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty();
            if (floor && clear) {
                return p;
            }
        }
        return null;
    }
}
