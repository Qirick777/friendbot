package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.living.BotLiving;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.aicompanion.test.TestUser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * T5.3 [검증] second half, verbatim: 「유저 취침 + 옆 빈 침대 → 봇도 취침 상태 되는지.
 * <b>판정: … 유저 취침 시 봇 isSleeping==true</b>」, plus 15.2's two remaining lines —
 * 「침대 없음 → 침대 옆 바닥에 눕는 포즈」 and 「유저 기상 → 봇도 기상」.
 *
 * <p><b>bed</b> — two beds: the user's (occupied by the user) and a free one three blocks away.
 * The bot must take the free one, and when the user gets up the bot must too.<br>
 * <b>nobed</b> — the CONTRAST: only the user's bed exists, so no free bed is in range. The bot must
 * NOT be in a bed, but must be in the lie-beside pose. Without this arm, an implementation that
 * lay down unconditionally would pass the bed arm.</p>
 *
 * <p>Premise recorded rather than assumed (결함 유형 #9): the world is night (the canary's 18000),
 * because {@code Player.tick} wakes any sleeper at daybreak — a day-time run would measure the
 * vanilla wake, not this feature.</p>
 */
public class BotLivingSleepTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int SLEEP_WINDOW = 60;    // observe the bot going to sleep
    private static final int RUN = 140;            // then wake the user and observe the bot waking

    private final boolean freeBedAvailable;
    private ServerPlayer user;
    private BlockPos userBed;
    private BlockPos freeBed;
    private boolean botSleptInBed;
    private boolean botLayBeside;
    private boolean botAwakeAfterUserWoke;
    private int userAsleepTicks;
    private int botSleepingTicks;
    private boolean wokeIssued;

    protected BotLivingSleepTest(boolean freeBedAvailable) {
        this.freeBedAvailable = freeBedAvailable;
    }

    @Override
    public int[] arenaBounds() {
        return new int[]{-12, 12, -12, 12};
    }

    @Override
    public String scenarioSpec() {
        return String.format(
                "night (daytime 18000 — Player.tick wakes any sleeper at dawn, so a day run would "
                + "measure vanilla, not this feature); user asleep in its own bed marked OCCUPIED; "
                + "%s; bot 20hp food20, wake issued at tick %d",
                freeBedAvailable ? "one FREE bed 3 blocks away" : "NO free bed in range",
                SLEEP_WINDOW);
    }

    @Override
    public String name() {
        return freeBedAvailable ? "bot_live_sleep" : "bot_live_sleep_nobed";
    }

    @Override
    public int timeoutTicks() {
        return RUN + 60;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);   // night — see the class note
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 3; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        userBed = new BlockPos(o.getX(), o.getY(), o.getZ());
        placeBed(ctx.level, userBed, Direction.NORTH);
        if (freeBedAvailable) {
            freeBed = new BlockPos(o.getX() + 3, o.getY(), o.getZ());
            placeBed(ctx.level, freeBed, Direction.NORTH);
        } else {
            freeBed = null;
        }

        bot.survival().reset();
        bot.living().reset();
        bot.stopSleeping();
        bot.getInventory().clearContent();
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        bot.setHealth(20.0F);
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 1.5, o.getY(), o.getZ() + 2.5, 0.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        user = TestUser.spawn(ctx.server, ctx.level, new BlockPos(o.getX(), o.getY(), o.getZ() + 1));
        user.setInvulnerable(true);
        user.setDeltaMovement(Vec3.ZERO);
        user.startSleeping(userBed);
        // Mark the user's own bed occupied so 「빈 침대」 means what it says.
        BlockState ub = ctx.level.getBlockState(userBed);
        if (ub.getBlock() instanceof BedBlock) {
            ctx.level.setBlock(userBed, ub.setValue(BedBlock.OCCUPIED, Boolean.TRUE), 3);
        }

        botSleptInBed = false;
        botLayBeside = false;
        botAwakeAfterUserWoke = false;
        userAsleepTicks = 0;
        botSleepingTicks = 0;
        wokeIssued = false;
    }

    /** A bed is two blocks; place FOOT at {@code foot} and HEAD one step along {@code facing}. */
    private static void placeBed(ServerLevel level, BlockPos foot, Direction facing) {
        BlockState base = Blocks.RED_BED.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, facing)
                .setValue(BedBlock.OCCUPIED, Boolean.FALSE);
        level.setBlock(foot, base.setValue(BedBlock.PART, BedPart.FOOT), 3);
        level.setBlock(foot.relative(facing), base.setValue(BedBlock.PART, BedPart.HEAD), 3);
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || user == null) {
            return true;
        }
        if (user.isSleeping()) {
            userAsleepTicks++;
        }
        if (bot.living().isSleepingInBed()) {
            botSleptInBed = true;
        }
        if (bot.living().isLyingBeside()) {
            botLayBeside = true;
        }
        if (bot.isSleeping()) {
            botSleepingTicks++;
        }

        if (ctx.elapsedTicks == SLEEP_WINDOW && !wokeIssued) {
            // 「유저 기상 → 봇도 기상」: get the user up and watch the bot follow.
            user.stopSleeping();
            wokeIssued = true;
            LOGGER.info("[LIVING-TEST] user woke at tick {} (botInBed={} lying={})",
                    ctx.elapsedTicks, bot.living().isSleepingInBed(), bot.living().isLyingBeside());
        }
        if (wokeIssued && ctx.elapsedTicks >= SLEEP_WINDOW + 10) {
            botAwakeAfterUserWoke =
                    !bot.living().isSleepingInBed() && !bot.living().isLyingBeside()
                            && !bot.isSleeping();
        }
        return ctx.elapsedTicks >= RUN;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        // Premise: the user must have actually stayed asleep through the pre-wake window.
        boolean premiseOk = userAsleepTicks >= SLEEP_WINDOW - 5;
        boolean ok = premiseOk && botAwakeAfterUserWoke && (freeBedAvailable
                ? (botSleptInBed && !botLayBeside && botSleepingTicks > 0)
                : (!botSleptInBed && botLayBeside));

        LOGGER.info("[LIVING-TEST] arm={} inBed={} lying={} sleepTicks={} userAsleep={} wokeWithUser={}",
                name(), botSleptInBed, botLayBeside, botSleepingTicks, userAsleepTicks,
                botAwakeAfterUserWoke);
        String measured = String.format(
                "arm:%s,freeBed:%s,botSleptInBed:%b,botLayBeside:%b,botSleepingTicks:%d,"
                        + "userAsleepTicks:%d,wokeWithUser:%b,premiseOk:%b,bedSearch:%d",
                freeBedAvailable ? "bed" : "nobed", String.valueOf(freeBed), botSleptInBed,
                botLayBeside, botSleepingTicks, userAsleepTicks, botAwakeAfterUserWoke, premiseOk,
                BotLiving.BED_SEARCH);
        String expected = freeBedAvailable
                ? "user asleep + one FREE bed in range → bot sleeps in that bed (isSleeping==true), "
                        + "not the floor pose; user gets up → bot gets up"
                : "user asleep + only the user's own (occupied) bed → no bed taken, floor lie-down "
                        + "pose instead; user gets up → bot gets up";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    /** A free bed is in range: 15.2's first branch. */
    public static class Bed extends BotLivingSleepTest {
        public Bed() {
            super(true);
        }
    }

    /** No free bed: 15.2's fallback branch. */
    public static class NoBed extends BotLivingSleepTest {
        public NoBed() {
            super(false);
        }
    }
}
