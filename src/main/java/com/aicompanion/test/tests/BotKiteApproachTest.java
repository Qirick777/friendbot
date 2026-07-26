package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.combat.KiteMonitor;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Row 3 of signal B's coverage table: the FALSE POSITIVE case.
 *
 * <p>{@code d(gap)/dt} is negative both when a target closes on the bot and when the BOT closes on
 * the target. Under the old predicate that was indistinguishable, and it showed: the monitor latched
 * at {@code ticksToExec:0} while the bot was merely walking in to its rule-3 band, before the pursuer
 * had moved at all.</p>
 *
 * <p>Here the bot advances on a stationary target under its own melee controller — a large, genuine,
 * sustained gap reduction with no kiting anywhere in sight. B must stay silent, because the intent
 * gate sees the bot asking to move TOWARD the target. PASS iff the bot really closed the distance AND
 * B never fired.</p>
 */
public class BotKiteApproachTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int START_GAP = 16;
    private static final int RUN_TICKS = 200;

    private Zombie dummy;
    private double gapStart = -1;
    private double minGap = Double.MAX_VALUE;
    private boolean fired;
    private int firedTick = -1;
    private int maxFailingTicks;
    private int intentOpenTicks;

    @Override
    public int[] arenaBounds() {
        return new int[]{-12, 28, -14, 14};
    }

    @Override
    public String name() {
        return "bot_kite_approach";
    }

    @Override
    public int timeoutTicks() {
        return RUN_TICKS + 60;
    }

    /** P-6 (유형 #9): 이 판정이 측정된 세계의 전제. */
    @Override
    public String scenarioSpec() {
        return "setBaseValue(MAX_HEALTH) 호출값: 200.0 (봇/표적 구분은 setup() 참조). bot.setInvulnerable(true). 스폰 "
                + "몹: zombie. 관측 창과 트라이얼 수는 리터럴이 아니라 상수 계산식이다(timeoutTicks()/repeats() 참조). 유저 없음(TestUser.spawn 미호출) → Perception.java:131이 봇 외 플레이어를 찾지 "
                + "못해 user==null → BotIdle.java:87-89 즉시 반환. 16장 자율 이동 비활성. ";
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;

        for (int dx = -8; dx <= START_GAP + 8; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 3; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        bot.survival().reset();
        bot.getInventory().clearContent();
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0);
        bot.setHealth(200.0F);
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();
        bot.kiteMonitor().reset();

        dummy = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + START_GAP, o.getY(), o.getZ()));
        if (dummy != null) {
            dummy.setInvulnerable(true);
            dummy.setNoAi(true);          // stationary: every bit of gap change is the BOT's doing
            dummy.setPersistenceRequired();
        }
        gapStart = -1;
        minGap = Double.MAX_VALUE;
        fired = false;
        firedTick = -1;
        maxFailingTicks = 0;
        intentOpenTicks = 0;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || dummy == null) {
            return true;
        }
        // Melee owns movement and walks the bot straight at the target.
        bot.meleeCombat().setTarget(dummy);

        double gap = Math.hypot(dummy.getX() - bot.getX(), dummy.getZ() - bot.getZ());
        if (gapStart < 0) {
            gapStart = gap;
        }
        minGap = Math.min(minGap, gap);
        maxFailingTicks = Math.max(maxFailingTicks, bot.kiteMonitor().closingTicks(dummy));
        if (bot.kiteMonitor().intendedOpen()) {
            intentOpenTicks++;
        }
        if (!fired && bot.kiteMonitor().failing(dummy)) {
            fired = true;
            firedTick = ctx.elapsedTicks;
            LOGGER.info("[APPROACH] signal B fired at t={} gap={}->{} (FALSE POSITIVE)",
                    firedTick, String.format("%.2f", gapStart), String.format("%.2f", gap));
        }
        return ctx.elapsedTicks >= RUN_TICKS;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        double closed = gapStart - minGap;
        boolean approached = closed > 5.0;   // the bot genuinely covered ground toward the target
        boolean ok = approached && !fired;

        LOGGER.info("[APPROACH] RESULT closed={} fired={} maxFailingTicks={} intentOpenTicks={}",
                String.format("%.2f", closed), fired, maxFailingTicks, intentOpenTicks);
        String measured = String.format(
                "botApproached:%b,gapClosed:%.2f(%.2f->%.2f),execMonitorFired:%b,firedTick:%d,"
                        + "maxFailingTicks:%d,intentOpenTicks:%d,failThreshold:%d,minOpeningRate:%.4f",
                approached, closed, gapStart, minGap, fired, firedTick, maxFailingTicks,
                intentOpenTicks, KiteMonitor.KITE_FAIL_TICKS, KiteMonitor.MIN_OPENING_RATE);
        String expected = "the bot closing distance on its own initiative is NOT a kiting failure: "
                + "gap shrinks by >5 blocks and signal B never fires (intent gate holds)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
