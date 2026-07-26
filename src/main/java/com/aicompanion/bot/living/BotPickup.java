package com.aicompanion.bot.living;

import com.aicompanion.bot.AICompanionBot;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * T5.5 자원 조달 — design ch.17, verbatim:
 *
 * <pre>
 * 17.1 유저가 던져준 것 수락
 *   유저가 봇을 향해 아이템을 던짐:
 *     유저 조준선 오차범위 내(관대하게, 봇 중심 반경 넉넉히)로 떨어지면
 *       → "나에게 주는 것"으로 인식
 *     비전투 상황 → 달려가서 받음
 *     (전투 중엔 받으러 가지 않음)
 *   너무 빡빡하게 조준을 요구하지 않는다 — 대략 봇 방향이면 수락.
 *
 * 17.2 유용한 드롭 대신 줍기 (제한적)
 *   유저가 던진 것만 기본 수락.
 *   자연 드롭(전리품)은:
 *     유저가 그것을 줍지 못하는 경우에만 봇이 주움
 *     (뺏어가는 느낌이 들지 않게)
 * </pre>
 *
 * <p>Like {@link BotIdle} this only sets a goal — the planner and mover walk there, and vanilla's
 * own {@code ItemEntity} pickup does the taking once the bot is on top of it. It runs immediately
 * before the idle layer, so a gift outranks 배회 but nothing else.</p>
 */
public class BotPickup {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 「봇 중심 반경 넉넉히」 — how far off the bot the throw may land and still read as a gift. */
    public static final double AIM_RADIUS = 6.0;
    /** 「대략 봇 방향이면」 — cosine of the allowed aim error between throw bearing and the bot. */
    public static final double AIM_COS = 0.5;              // ±60°
    /** How far the bot will go to fetch. */
    public static final double FETCH_RANGE = 24.0;
    /** 17.2: the user cannot pick it up if it is further from them than this and nobody moves. */
    public static final double USER_UNREACHABLE_DIST = 16.0;
    /** Ticks a natural drop must sit unclaimed before 「유저가 줍지 못하는 경우」 is established. */
    public static final int ABANDONED_TICKS = 200;

    @Nullable
    private UUID targetItem;
    private boolean fetching;
    @Nullable
    private String lastReason;

    public boolean isFetching() {
        return fetching;
    }

    @Nullable
    public String lastReason() {
        return lastReason;
    }

    public void reset() {
        targetItem = null;
        fetching = false;
        lastReason = null;
    }

    /**
     * Chooses a fetch goal. Called from the idle branch, above {@link BotIdle}.
     *
     * @return true if it claimed the goal this tick (so 배회 must stand down).
     */
    public boolean tick(AICompanionBot bot) {
        // 「전투 중엔 받으러 가지 않음」
        if (bot.meleeCombat().hasTarget() || bot.rangedCombat().hasTarget()) {
            fetching = false;
            targetItem = null;
            lastReason = "combat";
            return false;
        }
        ServerPlayer user = bot.perception().user;
        ItemEntity best = null;
        double bestD = Double.MAX_VALUE;
        String reason = null;

        AABB box = bot.getBoundingBox().inflate(FETCH_RANGE);
        for (ItemEntity it : bot.level().getEntitiesOfClass(ItemEntity.class, box, ItemEntity::isAlive)) {
            String why = accept(bot, user, it);
            if (why == null) {
                continue;
            }
            double d = bot.position().distanceTo(it.position());
            if (d < bestD) {
                bestD = d;
                best = it;
                reason = why;
            }
        }

        if (best == null) {
            fetching = false;
            targetItem = null;
            lastReason = null;
            return false;
        }
        if (!best.getUUID().equals(targetItem)) {
            targetItem = best.getUUID();
            LOGGER.info("[PICKUP] fetching {} ({}) d={}",
                    best.getItem().getItem(), reason, String.format("%.2f", bestD));
        }
        lastReason = reason;
        fetching = true;
        bot.planner().setGoal(BlockPos.containing(best.position()));
        bot.setSprinting(bot.getFoodData().getFoodLevel() > BotLiving.SPRINT_MIN_FOOD);
        return true;
    }

    /**
     * 17.1 / 17.2 admission. Returns the reason string when the bot may fetch this item, else null.
     */
    @Nullable
    private static String accept(AICompanionBot bot, @Nullable ServerPlayer user, ItemEntity item) {
        // 17.1 「유저가 봇을 향해 아이템을 던짐」. Vanilla stamps the thrower on a tossed stack; that is
        // the only place "who threw this" exists, so it is what 「유저가 던진 것」 reads.
        net.minecraft.world.entity.Entity thrower = item.getOwner();
        boolean fromUser = user != null && thrower == user;
        if (fromUser) {
            // 「조준선 오차범위 내(관대하게)」: two loose terms, either of which is enough — it landed
            // near the bot, OR it was thrown roughly in the bot's direction. Requiring both would be
            // 「빡빡하게 조준을 요구」, which 17.1 explicitly forbids.
            double landedNear = bot.position().distanceTo(item.position());
            if (landedNear <= AIM_RADIUS) {
                return "gift-near";
            }
            Vec3 toBot = bot.position().subtract(user.position());
            Vec3 throwDir = item.position().subtract(user.position());
            if (toBot.lengthSqr() > 1.0E-6 && throwDir.lengthSqr() > 1.0E-6
                    && toBot.normalize().dot(throwDir.normalize()) >= AIM_COS) {
                return "gift-aimed";
            }
            return null;
        }
        // 17.2 자연 드롭: only when the user cannot pick it up 「뺏어가는 느낌이 들지 않게」.
        if (user == null) {
            return null;
        }
        boolean userFar = user.position().distanceTo(item.position()) > USER_UNREACHABLE_DIST;
        boolean abandoned = item.tickCount > ABANDONED_TICKS;
        if (userFar && abandoned) {
            return "abandoned";
        }
        return null;
    }
}
