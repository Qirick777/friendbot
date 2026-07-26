package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.living.BotIdle;
import com.aicompanion.bot.living.BotLiving;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.aicompanion.test.TestUser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * T5.4 [검증] verbatim: 「유저를 봇에서 12블록 밖으로 이동 → 봇이 따라와 3블록 근처서 정지하는지.
 * <b>판정: 봇-유저 거리가 3블록 근처로 수렴 AND 정지(속도≈0).</b>」
 *
 * <p>Three arms, because the [검증] sentence covers only the first:</p>
 * <ul>
 *   <li><b>follow</b> — the spec's case: user placed 16 blocks away, bot must converge to ~3 and
 *       stop.</li>
 *   <li><b>hungry</b> — ch.16's last clause: 「배고픈 상태(스프린트 불가) → 걸어서 따라옴(느려도)
 *       … 텔레포트 안 함」. Same geometry with the food bar at 6. It must still converge, must never
 *       sprint, and no single tick may move it further than a walk could.</li>
 *   <li><b>wander</b> — the CONTRAST: the user stands 2 blocks away and never leaves. The bot must
 *       NOT be in FOLLOW, must stay inside the 8-block wander radius, and must actually move
 *       (배회 is not standing still). Without this arm, "converges to 3 and stops" would also be
 *       satisfied by a bot that simply walks to the user and never does anything else.</li>
 * </ul>
 */
public class BotIdleTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int START_DIST = 16;   // 「12블록 밖」
    private static final int RUN = 260;
    /** A walk is ~0.1 b/t and a sprint ~0.28; anything past this in one tick is a teleport. */
    private static final double TELEPORT_STEP = 1.0;

    private final Mode mode;
    private ServerPlayer user;
    private double finalDist;
    private double minDist = Double.MAX_VALUE;
    private double maxDist;
    private double endSpeed;
    private double maxStep;
    private double botTravel;
    private int sprintTicks;
    private int followTicks;
    private Vec3 prevPos = Vec3.ZERO;
    /** First tick the bot got inside 3 blocks — 「유저 3블록 근처서 정지」 is measured from HERE. */
    private int arriveTick = -1;
    private double arriveDist = -1;
    private double postArrivalSpeed;
    private double maxDistAfterArrival;

    public enum Mode { FOLLOW, HUNGRY, WANDER }

    protected BotIdleTest(Mode mode) {
        this.mode = mode;
    }

    @Override
    public int[] arenaBounds() {
        return new int[]{-26, 26, -26, 26};
    }

    @Override
    public String scenarioSpec() {
        return String.format(
                "flat 44×44 stone arena, user stationary and invulnerable at %d blocks, bot food "
                + "%d (%s), no enemies at all — the idle branch is the only thing that can move "
                + "the bot; teleport ceiling %.1f b/tick",
                mode == Mode.WANDER ? 2 : START_DIST,
                mode == Mode.HUNGRY ? com.aicompanion.bot.living.BotLiving.SPRINT_MIN_FOOD : 20,
                mode == Mode.HUNGRY ? "sprint impossible" : "sprint allowed", TELEPORT_STEP);
    }

    @Override
    public String name() {
        return switch (mode) {
            case FOLLOW -> "bot_idle_follow";
            case HUNGRY -> "bot_idle_follow_hungry";
            case WANDER -> "bot_idle_wander";
        };
    }

    @Override
    public int timeoutTicks() {
        return RUN + 80;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        for (int dx = -22; dx <= 22; dx++) {
            for (int dz = -22; dz <= 22; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 3; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        bot.survival().reset();
        bot.living().reset();
        bot.idle().reset();
        bot.planner().stop();
        bot.mover().stop();
        bot.getInventory().clearContent();
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        bot.setHealth(20.0F);
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();
        // 15.1's clamp is what makes the hungry arm a walk; 6 is the vanilla no-sprint level.
        bot.getFoodData().setFoodLevel(mode == Mode.HUNGRY ? BotLiving.SPRINT_MIN_FOOD : 20);
        bot.getFoodData().setSaturation(0.0F);

        int userOffset = mode == Mode.WANDER ? 2 : START_DIST;
        user = TestUser.spawn(ctx.server, ctx.level,
                new BlockPos(o.getX() + userOffset, o.getY(), o.getZ()));
        user.setInvulnerable(true);
        user.setDeltaMovement(Vec3.ZERO);

        finalDist = bot.position().distanceTo(user.position());
        minDist = finalDist;
        maxDist = finalDist;
        endSpeed = 0;
        arriveTick = -1;
        arriveDist = -1;
        postArrivalSpeed = 0;
        maxDistAfterArrival = 0;
        maxStep = 0;
        botTravel = 0;
        sprintTicks = 0;
        followTicks = 0;
        prevPos = bot.position();
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || user == null) {
            return true;
        }
        // Hold the user still: the spec's scene is a user who has arrived somewhere, not a chase.
        user.setDeltaMovement(Vec3.ZERO);

        Vec3 now = bot.position();
        double step = now.distanceTo(prevPos);
        maxStep = Math.max(maxStep, step);
        botTravel += step;
        prevPos = now;

        double d = now.distanceTo(user.position());
        finalDist = d;
        minDist = Math.min(minDist, d);
        maxDist = Math.max(maxDist, d);
        if (bot.isSprinting()) {
            sprintTicks++;
        }
        if (bot.idle().state() == BotIdle.State.FOLLOW) {
            followTicks++;
        }
        if (ctx.elapsedTicks >= RUN - 20) {
            endSpeed = Math.max(endSpeed, step);
        }
        // 「유저 3블록 근처서 정지, 다시 배회」 — the stop is at ARRIVAL, and wandering afterwards is
        // what the spec asks for. Measuring the speed at t=RUN instead judged the 배회 that ch.16
        // requires as a failure to stop (measured: finalDist 6.43, endSpeed 0.0056 — a bot doing
        // exactly what the sentence says). So the stop window is anchored to the arrival tick.
        if (arriveTick < 0 && d <= BotIdle.FOLLOW_EXIT) {
            arriveTick = ctx.elapsedTicks;
            arriveDist = d;
        }
        if (arriveTick >= 0) {
            maxDistAfterArrival = Math.max(maxDistAfterArrival, d);
            // The window starts at +10, not +1: a sprinting bot cannot stop dead. Vanilla friction
            // sheds ~40% of the velocity per input-free tick, so ticks +1..+5 are the deceleration
            // itself (measured 0.1514 on the first, from a 0.28 sprint). Measuring there judges the
            // physics, not the behaviour. +10..+30 sits well inside BotIdle's 40-tick pause.
            if (ctx.elapsedTicks >= arriveTick + 10 && ctx.elapsedTicks <= arriveTick + 30) {
                postArrivalSpeed = Math.max(postArrivalSpeed, step);
            }
        }
        return ctx.elapsedTicks >= RUN;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        boolean noTeleport = maxStep < TELEPORT_STEP;
        boolean converged = arriveTick >= 0;
        boolean stopped = converged && postArrivalSpeed < 0.02;
        // After the stop the bot goes back to 배회, which ch.16 bounds at 8 blocks around the user.
        boolean stayedNear = maxDistAfterArrival <= BotIdle.WANDER_RADIUS + 2.0;

        boolean ok = switch (mode) {
            case FOLLOW -> converged && stopped && stayedNear && noTeleport && followTicks > 0;
            // 「스프린트 불가 … 걸어서 따라옴(느려도) … 텔레포트 안 함」
            case HUNGRY -> converged && stayedNear && noTeleport && sprintTicks == 0
                    && followTicks > 0;
            // 「유저 정지 → 주변 배회」: never enters FOLLOW, stays in the 8-block radius, and moves.
            case WANDER -> followTicks == 0 && maxDist <= BotIdle.WANDER_RADIUS + 2.0
                    && botTravel > 4.0 && noTeleport;
        };

        LOGGER.info("[IDLE-TEST] arm={} dist={}→{} min={} max={} travel={} sprint={} follow={} step={}",
                name(), START_DIST, String.format("%.2f", finalDist), String.format("%.2f", minDist),
                String.format("%.2f", maxDist), String.format("%.2f", botTravel), sprintTicks,
                followTicks, String.format("%.3f", maxStep));
        String measured = String.format(
                "arm:%s,arriveTick:%d,arriveDist:%.2f,postArrivalSpeed[+10..+30]:%.4f,maxDistAfterArrival:%.2f,"
                        + "finalDist:%.2f,minDist:%.2f,maxDist:%.2f,endSpeed:%.4f,botTravel:%.2f,"
                        + "sprintTicks:%d,followTicks:%d,maxStep:%.3f,food:%d,enter:%.0f,exit:%.0f",
                mode.name().toLowerCase(), arriveTick, arriveDist, postArrivalSpeed,
                maxDistAfterArrival, finalDist, minDist, maxDist, endSpeed, botTravel,
                sprintTicks, followTicks, maxStep,
                BotManager.current() == null ? -1 : BotManager.current().getFoodData().getFoodLevel(),
                BotIdle.FOLLOW_ENTER, BotIdle.FOLLOW_EXIT);
        String expected = switch (mode) {
            case FOLLOW -> "user 16 blocks away → bot follows, reaches ≈3 blocks and STOPS THERE "
                    + "(speed≈0 for the 20 ticks after arrival), then resumes 배회 inside 8 blocks";
            case HUNGRY -> "food 6 (sprint impossible) → still reaches ≈3, sprintTicks 0, and no "
                    + "single tick exceeds a walk (no teleport)";
            case WANDER -> "user 2 blocks away and stationary → never enters FOLLOW, stays inside "
                    + "the 8-block wander radius, and still moves (어슬렁, not frozen)";
        };
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    /** The spec's own case. */
    public static class Follow extends BotIdleTest {
        public Follow() {
            super(Mode.FOLLOW);
        }
    }

    /** ch.16's hunger clause. */
    public static class Hungry extends BotIdleTest {
        public Hungry() {
            super(Mode.HUNGRY);
        }
    }

    /** Contrast: the user never leaves. */
    public static class Wander extends BotIdleTest {
        public Wander() {
            super(Mode.WANDER);
        }
    }
}
