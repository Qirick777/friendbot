package com.aicompanion.bot.combat;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.perception.TargetInfo;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * Kidnap-escape + user fall-catch via mounting (T4.5 / design ch.10 & ch.13). Both behaviours make
 * the user a passenger of the bot ({@code user.startRiding(bot)} — a real server-side vehicle/
 * passenger relationship, not a flag) and then either sprint the user to safety (escape) or simply
 * hold the caught user (fall catch, then dismount on the ground).
 *
 * <p>The user is a connection-less fake player too, so its passenger {@code rideTick}/positionRider
 * may not run — {@link AICompanionBot} therefore drives {@link #positionPassengers} every tick after
 * the physics tick so the passenger actually follows the bot.</p>
 */
public class BotRescue {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final float USER_CRIT = 0.15F;      // 유저 치명선 → 도주
    private static final float SAFE_FALL = 3.0F;       // 유저 낙하 감지 안전값
    private static final double CATCH_XZ = 2.5;        // 봇이 유저 하강선 아래로 볼 수평 거리
    private static final double MOUNT_DIST = 2.5;      // 이 안이면 태운다
    private static final double THREAT_DIST = 12.0;    // 위협이 이 안이어야 도주 발동 (히스테리시스 하한)
    private static final double SAFE_ESCAPE_DIST = 18.0; // 이 밖으로 벗어나면 안전권 → 하차 (상한)
    private static final int CATCH_HOLD = 20;          // 받은 뒤 잠깐 유지 후 지면에서 하차 (C6)
    /** C4: run to meet the fall only when within this horizontal distance (「봇이 근처」). */
    public static final double MEET_RANGE = 24.0;
    /** Conservative ground speed used to decide 「착지지점 갈 시간 됨」; below the measured sprint. */
    private static final double MEET_SPEED = 0.20;
    /**
     * How far below the user the landing raycast searches, capped only so a user over the void
     * terminates the loop. It was 40, an arbitrary number with no basis in 13.2 — and it created a
     * blind spot the harness caught: a user falling from more than 40 blocks is invisible to C4/C5
     * until the ground comes within 40, by which point 「착지지점 갈 시간 됨」 is already false for any
     * meaningful distance. Measured: both arms of bot_catch_meet first evaluated at ticksToLand=23
     * for a 50-block fall that should have given ~32. The cap is a search depth, not a behaviour
     * threshold; the spec asks for the landing point to be predicted, so it scans to the world floor.
     */
    private static final int LANDING_SEARCH = 384;

    public enum Mode { NONE, CATCH, ESCAPE }

    private Mode mode = Mode.NONE;
    @Nullable
    private Double userPeakY;   // self-tracked user fall (fake user has no driven fallDistance)
    private int rideTicks;
    private int lastMeetTicks = -1;
    private double lastMeetDist = -1;
    @Nullable
    private BlockPos waterFallbackPos;

    /** C4 diagnostics: predicted ticks to landing and the distance the bot had to cover. */
    public int lastMeetTicks() {
        return lastMeetTicks;
    }

    public double lastMeetDist() {
        return lastMeetDist;
    }

    /** C5 diagnostics: where the water fallback was laid, or null. */
    @Nullable
    public BlockPos waterFallbackPos() {
        return waterFallbackPos;
    }

    public Mode mode() {
        return mode;
    }

    /** Called in the reflex layer (before survival/protection). @return true if it owns the tick. */
    public boolean tick(AICompanionBot bot) {
        ServerPlayer user = bot.perception().user;
        if (user == null) {
            userPeakY = null;
            return false;
        }
        boolean carrying = bot.hasPassenger(user);

        // C1: track the user's fall from its peak Y (its fallDistance isn't driven server-side).
        double uy = user.getY();
        if (!carrying) {
            if (userPeakY == null || uy > userPeakY) {
                userPeakY = uy;
            }
        }
        double userFall = userPeakY != null ? userPeakY - uy : 0.0;

        if (carrying) {
            rideTicks++;
            return carryTick(bot, user);
        }
        rideTicks = 0;

        // C2/C3: catch a falling user the bot is under.
        double dx = user.getX() - bot.getX();
        double dz = user.getZ() - bot.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        boolean underPath = horiz <= CATCH_XZ && bot.getY() < user.getY();
        if (userFall > SAFE_FALL && bot.onGround() && underPath) {
            boolean ok = user.startRiding(bot, true);
            mode = Mode.CATCH;
            userPeakY = null;
            LOGGER.info("[RESCUE] CATCH startRiding ok={} userFall={} horiz={} vehicle==bot={} passengers={}",
                    ok, fmt(userFall), fmt(horiz), user.getVehicle() == bot, bot.getPassengers().size());
            return ok;
        }

        // C4/C5 fallback chain (design 13.2). Reached only when the bot is NOT already under the
        // descent line — that arm is above and stays the most elegant outcome.
        //   ELIF 봇이 근처 + 착지지점 갈 시간 됨 → 마중 나가 받기, 실패 시 아래에 물/블록
        //   ELIF 유저에게 물/블록 깔아줄 수 있음 → 착지 지점에 물/블록
        //   ELSE → 개입 불가(거리 부족)
        // 13.3 says the only NEW part is the "is the bot under the descent line" comparison; landing
        // detection reuses R2's downward raycast and the fallback reuses the structure placer.
        if (userFall > SAFE_FALL && !underPath) {
            ServerLevel level = (ServerLevel) bot.level();
            BlockPos landing = predictLanding(level, user);
            if (landing != null) {
                int ticksToLand = ticksToFall(user, landing.getY());
                double meetDist = Math.sqrt(
                        Math.pow(landing.getX() + 0.5 - bot.getX(), 2)
                                + Math.pow(landing.getZ() + 0.5 - bot.getZ(), 2));
                boolean canMeet = meetDist <= MEET_RANGE
                        && meetDist / MEET_SPEED < ticksToLand;
                if (canMeet) {
                    // C4 — go meet it. Arriving under the line hands over to the mount arm above.
                    // The branch returns true, so AICompanionBot.tick's planner/mover branch is
                    // skipped for this tick — this arm has to drive them itself or the bot stands
                    // still with a goal set and nothing moves (defect type #10).
                    // The goal is the STANDING position, not the block itself: predictLanding
                    // returns the solid block the user lands ON, and A* rejects a solid goal
                    // outright (measured: "path unreachable … expansions=0", bot never moved).
                    bot.planner().setGoal(landing.above());
                    bot.setSprinting(true);
                    bot.planner().tick(bot);
                    bot.mover().tick(bot);
                    mode = Mode.CATCH;
                    lastMeetTicks = ticksToLand;
                    lastMeetDist = meetDist;
                    LOGGER.info("[RESCUE] C4 meet-the-fall landing={} meetDist={} ticksToLand={}",
                            landing, fmt(meetDist), ticksToLand);
                    return true;
                }
                // C5 — cannot get under it in time: lay water at the landing spot instead.
                if (bot.environment().consumeWaterFor(bot)) {
                    bot.environment().deployWaterColumnAt(level, landing.above());
                    waterFallbackPos = landing.above();
                    mode = Mode.CATCH;
                    LOGGER.info("[RESCUE] C5 water fallback at {} (meetDist={} ticksToLand={})",
                            landing.above(), fmt(meetDist), ticksToLand);
                    return true;
                }
                LOGGER.info("[RESCUE] no intervention: meetDist={} ticksToLand={} noWater",
                        fmt(meetDist), ticksToLand);
            }
        }

        // R1/R4: escape mount when the user is critical, can't be potion-saved here, AND a threat is
        // actually near (설계 10 도주 = 위협에서 벗어나기). The THREAT(12)/SAFE(18) gap is hysteresis:
        // after a safe dismount the threat is >18 away, so the escape does not re-trigger (no
        // mount↔dismount oscillation).
        float userHp = user.getMaxHealth() > 0 ? user.getHealth() / user.getMaxHealth() : 1.0F;
        Entity threat = nearestEnemy(bot);
        boolean threatNear = threat != null
                && threat.position().distanceTo(user.position()) <= THREAT_DIST;
        boolean escapeTrigger = userHp <= USER_CRIT && !hasThrowableHeal(bot) && threatNear;
        if (escapeTrigger) {
            double dist = bot.position().distanceTo(user.position());
            if (dist <= MOUNT_DIST) {
                boolean ok = user.startRiding(bot, true);
                mode = Mode.ESCAPE;
                LOGGER.info("[RESCUE] ESCAPE startRiding ok={} userHp={}% vehicle==bot={} passengers={}",
                        ok, fmt(userHp * 100), user.getVehicle() == bot, bot.getPassengers().size());
            } else {
                approach(bot, user); // R? 봇이 유저에게 달려옴 (direct; A* refinement later)
            }
            return true;
        }

        if (user.onGround()) {
            userPeakY = null;
        }
        mode = Mode.NONE;
        return false;
    }

    private boolean carryTick(AICompanionBot bot, ServerPlayer user) {
        if (mode == Mode.ESCAPE) {
            Entity enemy = nearestEnemy(bot);
            if (enemy == null || bot.distanceTo(enemy) > SAFE_ESCAPE_DIST) {
                user.stopRiding(); // R9: safe zone → dismount
                mode = Mode.NONE;
                LOGGER.info("[RESCUE] ESCAPE reached safety -> stopRiding");
                return true;
            }
            faceAwayAndSprint(bot, enemy); // R8: bot drives movement (sprint away)
            return true;
        }
        // CATCH ride: received on the ground → hold briefly then dismount (C6, no sprint).
        if (bot.onGround() && rideTicks >= CATCH_HOLD) {
            user.stopRiding();
            mode = Mode.NONE;
            LOGGER.info("[RESCUE] CATCH on ground -> stopRiding after hold");
        }
        return true;
    }

    /** Drive passengers to the bot's head every tick (fake passengers aren't ticked normally). */
    /**
     * Landing point under a falling user: R2's downward raycast applied to the user (13.3 「착지 지점
     * 레이캐스트 | R2의 아래 거리 재기」). Returns the solid block they will land ON, or null.
     */
    @Nullable
    private static BlockPos predictLanding(ServerLevel level, ServerPlayer user) {
        BlockPos p = user.blockPosition();
        int depth = Math.min(LANDING_SEARCH, p.getY() - level.getMinBuildHeight());
        for (int d = 1; d <= depth; d++) {
            BlockPos below = p.below(d);
            if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
                return below;
            }
        }
        return null;
    }

    /**
     * Ticks until the user reaches {@code landingY}, simulated with vanilla fall physics
     * (v' = (v - 0.08) * 0.98) from their current vertical velocity. A closed form would need the
     * drag series; iterating is exact and costs a few dozen steps.
     */
    private static int ticksToFall(ServerPlayer user, int landingY) {
        double y = user.getY();
        double vy = user.getDeltaMovement().y;
        for (int t = 1; t <= 200; t++) {
            vy = (vy - 0.08) * 0.98;
            y += vy;
            if (y <= landingY + 1) {
                return t;
            }
        }
        return 200;
    }

    public void positionPassengers(AICompanionBot bot) {
        if (bot.getPassengers().isEmpty()) {
            return;
        }
        double baseY = bot.getY() + bot.getPassengersRidingOffset();
        for (Entity p : bot.getPassengers()) {
            p.setPos(bot.getX(), baseY + p.getMyRidingOffset(), bot.getZ());
            p.setDeltaMovement(Vec3.ZERO);
            p.fallDistance = 0.0F; // R6/C3: no fall damage while carried
        }
    }

    // --- movement helpers ---

    private static void approach(AICompanionBot bot, ServerPlayer user) {
        float yaw = yawTo(bot.getX(), bot.getZ(), user.getX(), user.getZ());
        bot.setYRot(yaw);
        bot.setYBodyRot(yaw);
        bot.setYHeadRot(yaw);
        bot.zza = 1.0F;
        bot.xxa = 0.0F;
        bot.setSprinting(true);
        bot.setJumping(false);
    }

    private static void faceAwayAndSprint(AICompanionBot bot, Entity enemy) {
        float away = Mth.wrapDegrees(yawTo(bot.getX(), bot.getZ(), enemy.getX(), enemy.getZ()) + 180.0F);
        bot.setYRot(away);
        bot.setYBodyRot(away);
        bot.setYHeadRot(away);
        bot.zza = 1.0F;
        bot.xxa = 0.0F;
        bot.setSprinting(true);
        bot.setJumping(false);
    }

    @Nullable
    private static Entity nearestEnemy(AICompanionBot bot) {
        for (TargetInfo t : bot.perception().targets) {
            if (t.entity != null && t.entity.isAlive()) {
                return t.entity;
            }
        }
        return null;
    }

    private static boolean hasThrowableHeal(AICompanionBot bot) {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() == Items.SPLASH_POTION
                    || inv.getItem(i).getItem() == Items.LINGERING_POTION) {
                return true;
            }
        }
        return false;
    }

    private static float yawTo(double x, double z, double tx, double tz) {
        return Mth.wrapDegrees((float) (Mth.atan2(tz - z, tx - x) * (180.0 / Math.PI)) - 90.0F);
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
