package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.combat.CombatRules;
import com.aicompanion.bot.combat.CombatStats;
import com.aicompanion.bot.perception.TargetInfo;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * W6 LIVE gate (audit item 2a). The T4.6 spec line is 「규칙1이 워든 <b>저속</b>을 읽어 카이팅 자동
 * 판정」, and the existing warden harnesses all pin the warden with {@code setNoAi}, so the observer
 * reads 0.0 — a value a rock would also produce. Here the warden is genuinely brain-driven, angry
 * and closing, so the bot must observe its real approach speed (~0.198 b/t from bot_speed_probe)
 * and derive {@code canKite=true} from that measurement.
 *
 * <p>PASS iff the observed speed is in a live-movement band (clearly non-zero, clearly below the
 * bot's sprint) AND rule 1 says kiteable.</p>
 */
public class BotWardenLiveSpeedTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int START_DIST = 28;
    private static final int SETTLE = 60;      // spawn + emerge/roar animation lock-in
    private static final double MIN_LIVE = 0.05;  // "actually moving", not a pinned dummy
    private static final double MAX_LIVE = 0.28;  // still below the bot's measured sprint 0.2806

    private Warden warden;
    private double observedAtJudge;
    private boolean observedFlag;
    private Boolean canKite;
    private double independentSpeed;
    private Vec3 measureStart;
    private int measureStartTick = -1;
    // (2a) the same 60-tick trailing window the observer uses, measured independently, so a
    // long-span average and a recent-window average can be told apart.
    private final java.util.ArrayDeque<double[]> trail = new java.util.ArrayDeque<>();
    private double trailingSpeed;
    private double pathSpeed;      // path length / ticks (upper bound on displacement/ticks)
    private double pathSum;
    private Vec3 lastWardenPos;

    @Override
    public String name() {
        return "bot_warden_live_speed";
    }

    @Override
    public int repeats() {
        return 3;
    }

    @Override
    public double successThreshold() {
        return 1.00;
    }

    @Override
    public int timeoutTicks() {
        return 400;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);

        for (int dx = -8; dx <= START_DIST + 10; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 4; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        bot.survival().reset();
        bot.getInventory().clearContent();
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(400.0);
        bot.setHealth(400.0F);
        bot.getFoodData().setFoodLevel(20);
        // NOT invulnerable: Warden.canTargetEntity rejects invulnerable targets, so a pinned-but-
        // invulnerable bot would silently turn the warden back into a stationary dummy.
        bot.setInvulnerable(false);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        // Brain ON, no speed override: this warden really walks.
        warden = ctx.env.spawn(EntityType.WARDEN, new BlockPos(o.getX() + START_DIST, o.getY(), o.getZ()));
        if (warden != null) {
            warden.setInvulnerable(true);
            warden.increaseAngerAt(bot);
            warden.setAttackTarget(bot);
        }
        observedFlag = false;
        canKite = null;
        measureStartTick = -1;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || warden == null) {
            return true;
        }
        bot.setHealth(bot.getMaxHealth());
        // The bot stays put; only the warden moves, so the observation is purely its approach.
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(ctx.origin.getX() + 0.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5, 90.0F, 0.0F);
        if (ctx.elapsedTicks % 40 == 0) {
            warden.increaseAngerAt(bot);
            warden.setAttackTarget(bot);
        }

        int t = ctx.elapsedTicks;
        double gap = Math.hypot(warden.getX() - bot.getX(), warden.getZ() - bot.getZ());
        if (t >= SETTLE && gap > 4.0) {
            if (measureStartTick < 0) {
                measureStartTick = t;
                measureStart = warden.position();
            } else {
                independentSpeed = Math.hypot(warden.getX() - measureStart.x, warden.getZ() - measureStart.z)
                        / (t - measureStartTick);
            }
            // trailing window, same length as SpeedObserver.WINDOW_TICKS
            trail.addLast(new double[]{t, warden.getX(), warden.getZ()});
            while (!trail.isEmpty() && t - trail.peekFirst()[0] > 60) {
                trail.removeFirst();
            }
            if (trail.size() > 1) {
                double[] a = trail.peekFirst();
                double[] b = trail.peekLast();
                double sp = b[0] - a[0];
                if (sp > 0) {
                    trailingSpeed = Math.hypot(b[1] - a[1], b[2] - a[2]) / sp;
                }
            }
            if (lastWardenPos != null) {
                pathSum += Math.hypot(warden.getX() - lastWardenPos.x, warden.getZ() - lastWardenPos.z);
                pathSpeed = pathSum / Math.max(1, t - measureStartTick);
            }
            lastWardenPos = warden.position();
            for (TargetInfo ti : bot.perception().targets) {
                if (ti.entity == warden) {
                    observedAtJudge = ti.observedSpeed;
                    observedFlag = ti.speedObserved;
                    canKite = CombatRules.canKite(ti, CombatStats.of(bot).sprintSpeed);
                    break;
                }
            }
        }
        // Stop once the warden has closed in (nothing left to observe) or on the window end.
        return (measureStartTick > 0 && gap <= 4.5) || t >= 360;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        boolean live = observedAtJudge >= MIN_LIVE && observedAtJudge <= MAX_LIVE;
        boolean ok = observedFlag && live && canKite != null && canKite;
        LOGGER.info("[WARDEN] live speed observed={} indepWhole={} indepTrail60={} indepPath={} "
                        + "canKite={} botSprint={}",
                String.format("%.4f", observedAtJudge), String.format("%.4f", independentSpeed),
                String.format("%.4f", trailingSpeed), String.format("%.4f", pathSpeed),
                canKite, CombatStats.BOT_SPRINT_SPEED);
        String measured = String.format(
                "observedSpeed:%.4f,indepWholeSpan:%.4f,indepTrailing60:%.4f,indepPathLen:%.4f,"
                        + "liveBand:%.2f~%.2f,speedObserved:%b,canKite:%s,botSprint:%.4f",
                observedAtJudge, independentSpeed, trailingSpeed, pathSpeed, MIN_LIVE, MAX_LIVE,
                observedFlag, String.valueOf(canKite), CombatStats.BOT_SPRINT_SPEED);
        String expected = "a MOVING warden's observed speed is non-zero and below the bot's sprint, "
                + "and rule 1 derives canKite=true from that measurement (not from a pinned 0.0)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
