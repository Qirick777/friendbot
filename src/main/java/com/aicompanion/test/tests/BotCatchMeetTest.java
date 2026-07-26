package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.aicompanion.test.TestUser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * C4/C5 of the fall-catch chain (design 13.2).
 *
 * <p>Until now only C2/C3 existed — catching a user the bot was ALREADY under. If the bot stood
 * anywhere else it did nothing, which {@code bot_catch_none} recorded as the user taking 8 damage.
 * 13.2 defines two more rungs before "개입 불가":</p>
 *
 * <pre>
 *   ELIF 봇이 근처 + 착지지점 갈 시간 됨 → 마중 나가 받기, 실패 시 아래에 물/블록
 *   ELIF 유저에게 물/블록 깔아줄 수 있음  → 착지 지점에 물/블록
 * </pre>
 *
 * <p><b>meet</b> — the bot starts well off the descent line but has time to reach the landing spot:
 * it must run there and catch, and the user must land unharmed.<br>
 * <b>water</b> — the bot is too far to arrive in time but holds a bucket: it must lay water at the
 * landing point, and the user's fall damage must be reduced to zero.</p>
 */
public class BotCatchMeetTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 70 blocks ≈ 48 ticks of fall (vanilla {@code v' = (v − 0.08) × 0.98}). C4's own admission test
     * is {@code meetDist / MEET_SPEED(0.20) < ticksToLand}, so the meet arm's 6-block offset needs
     * 30 ticks of budget; detection itself costs ~8 ticks (it waits for {@code userFall > 3}),
     * leaving ~40. The height is what makes the meet arm reachable at all, and it is stated here
     * rather than left implicit (결함 유형 #9).
     */
    private static final int FALL_HEIGHT = 70;
    private static final int RUN = 200;

    private final boolean meetable;
    private ServerPlayer user;
    private float userHpStart;
    private float userHpMin;
    private boolean everMounted;
    private boolean waterLaid;
    private int meetTicks = -1;
    private double meetDist = -1;
    private float maxFallDistance;
    private double userStartY;
    private double minUserY = Double.MAX_VALUE;

    protected BotCatchMeetTest(boolean meetable) {
        this.meetable = meetable;
    }

    @Override
    public int[] arenaBounds() {
        return new int[]{-40, 40, -40, 40};
    }

    @Override
    public String scenarioSpec() {
        return String.format(
                "fall %d blocks (≈%d ticks) onto stone; bot %s the descent line, user 20hp and "
                + "vulnerable; C4 admission is meetDist/MEET_SPEED(0.20) < ticksToLand, and the "
                + "height is what makes the meet arm reachable at all",
                FALL_HEIGHT, 48, meetable ? "6 blocks off (inside MEET_RANGE 24)"
                        : "30 blocks off (outside MEET_RANGE 24, bucket held)");
    }

    @Override
    public String name() {
        return meetable ? "bot_catch_meet" : "bot_catch_water";
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
        for (int dx = -34; dx <= 34; dx++) {
            for (int dz = -34; dz <= 34; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= FALL_HEIGHT + 6; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        bot.survival().reset();
        bot.getInventory().clearContent();
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        bot.setHealth(20.0F);
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        // Off the descent line in both arms — the C2/C3 arm must not be the one that fires
        // (CATCH_XZ = 2.5). meet: 6 > 2.5 but inside MEET_RANGE and reachable in time.
        // water: 30 > MEET_RANGE(24), so C4 is refused by its own condition and C5 must carry it.
        int botOffset = meetable ? 6 : 30;
        bot.moveTo(o.getX() + botOffset + 0.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();
        if (!meetable) {
            bot.getInventory().add(new ItemStack(Items.WATER_BUCKET));
        }
        bot.equipment().invalidate();

        user = TestUser.spawn(ctx.server, ctx.level, new BlockPos(o.getX(), o.getY(), o.getZ()));
        user.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        user.setHealth(20.0F);
        user.setInvulnerable(false);        // fall damage must be real
        user.setDeltaMovement(Vec3.ZERO);
        user.moveTo(o.getX() + 0.5, o.getY() + FALL_HEIGHT, o.getZ() + 0.5, 0.0F, 0.0F);
        user.fallDistance = 0.0F;

        userHpStart = user.getHealth();
        userHpMin = userHpStart;
        userStartY = user.getY();
        minUserY = userStartY;
        maxFallDistance = 0.0F;
        everMounted = false;
        waterLaid = false;
        meetTicks = -1;
        meetDist = -1;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || user == null) {
            return true;
        }
        // The fake user has no connection, so nothing ticks it: without this the "fall" never
        // happens at all. First run of this harness measured userDamage 0.0 with meetTicks -1 —
        // not a C4 failure, a user that hovered at y+50 for 200 ticks. Same drive as
        // bot_catch_none: doTick() then the fall-damage check with this tick's displacement.
        if (user.getVehicle() == null) {
            double px = user.getX();
            double py = user.getY();
            double pz = user.getZ();
            user.doTick();
            maxFallDistance = Math.max(maxFallDistance, user.fallDistance);
            user.doCheckFallDamage(user.getX() - px, user.getY() - py, user.getZ() - pz,
                    user.onGround());
        } else if (user.getVehicle() == bot) {
            everMounted = true;
        }
        if (bot.rescue().waterFallbackPos() != null) {
            waterLaid = true;
        }
        if (bot.rescue().lastMeetTicks() >= 0 && meetTicks < 0) {
            meetTicks = bot.rescue().lastMeetTicks();
            meetDist = bot.rescue().lastMeetDist();
        }
        userHpMin = Math.min(userHpMin, user.getHealth());
        minUserY = Math.min(minUserY, user.getY());
        return ctx.elapsedTicks >= RUN;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        double lost = userHpStart - userHpMin;
        boolean unharmed = lost <= 0.01;
        // Premise: the user must actually have fallen a lethal distance. "unharmed" is trivially
        // true of a user that never moved, which is exactly how the first run of this harness
        // produced a clean-looking 20.0→20.0 while nothing happened.
        double fallen = userStartY - minUserY;
        boolean reallyFell = fallen > FALL_HEIGHT - 4;
        boolean ok = reallyFell && (meetable ? (everMounted && unharmed) : (waterLaid && unharmed));

        LOGGER.info("[CATCHMEET] arm={} mounted={} waterLaid={} meetTicks={} meetDist={} fell={} userHp={}->{}",
                name(), everMounted, waterLaid, meetTicks, String.format("%.2f", meetDist),
                String.format("%.2f", fallen), userHpStart, userHpMin);
        String measured = String.format(
                "arm:%s,everMounted:%b,waterLaid:%b,meetTicksToLand:%d,meetDist:%.2f,"
                        + "userFell:%.1f,fallDistance:%.2f,userHp:%.1f->%.1f,userDamage:%.1f,fallHeight:%d",
                meetable ? "meet" : "water", everMounted, waterLaid, meetTicks, meetDist,
                fallen, maxFallDistance, userHpStart, userHpMin, lost, FALL_HEIGHT);
        String expected = meetable
                ? "bot starts off the descent line but can reach the landing spot in time → it runs "
                        + "there, mounts the user (C4), and the user takes no fall damage"
                : "bot cannot arrive in time but holds a bucket → water is laid at the landing spot "
                        + "(C5) and the user takes no fall damage";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    /** C4 — run to meet the fall. */
    public static class Meet extends BotCatchMeetTest {
        public Meet() {
            super(true);
        }
    }

    /** C5 — too far to meet it; lay water instead. */
    public static class Water extends BotCatchMeetTest {
        public Water() {
            super(false);
        }
    }
}
