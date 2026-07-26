package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.combat.CombatStats;
import com.aicompanion.bot.perception.SpeedObserver;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The OTHER distribution rule 1 might need.
 *
 * <p>{@code bot_coldstart_dist} pins the bot with {@code moveTo} every tick, so what it measures is
 * a mob's approach rate against a STATIONARY target (measured: mean 0.2380, sd 0.0205 over 25 moving
 * engagements). But the question rule 1 was declared to ask is "can this thing run me down while I
 * flee", and there is no guarantee those are the same quantity — effective speed is known to depend
 * on AI state here, with attribute-to-b/t ratios spread 3.43x across mobs.</p>
 *
 * <p>The cold-start defence is that the first verdict lands at tick ~61, before the bot has begun to
 * flee, so a stationary-target rate is the right input AT THAT MOMENT. If that is right, rule 1 needs
 * TWO distributions — cold start (stationary) and sustained (fleeing) — and the redesign has to say
 * whether they may be treated as one. This harness supplies the second number so that question can
 * be answered from data instead of guessed.</p>
 *
 * <p>Measured: the warden's observed approach rate while the bot is genuinely retreating, sampled
 * only on ticks where the bot actually moved, plus an independent trailing-window rate. PASS is the
 * measurement PREMISE (the bot really fled, enough samples) — never a comparison against the
 * stationary value, which would be tuning the answer.</p>
 */
public class BotWardenPursuitTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int START_DIST = 20;
    private static final int SETTLE = 80;       // spawn + emerge/roar, then engage
    private static final int RUN_TICKS = 420;
    private static final double STATIONARY_REFERENCE = 0.2380;   // bot_coldstart_dist moving mean

    private Warden warden;
    private final List<Double> samples = new ArrayList<>();
    /**
     * THE draw — one per engagement, exactly as bot_coldstart_dist takes one. Both read the same
     * field ({@code TargetInfo.observedSpeed} = SpeedObserver W60, displacement between window ends
     * over elapsed ticks, stationary ticks included). But coldstart takes a SINGLE window and
     * averaging many overlapping windows here would shrink the between-engagement spread by
     * construction, so the two sd values would not be comparable even though the underlying
     * estimator is identical. Same estimator, same sampling.
     */
    private double draw = -1;
    private int movingTicks;
    private Vec3 botStart;
    private double botFled;
    private double trailingRate;
    private final java.util.ArrayDeque<double[]> trail = new java.util.ArrayDeque<>();

    // --- gap diagnostics: is the warden CLOSING, or matching pace sideways? -------------------
    // A-signal measures the target's ABSOLUTE displacement. With the bot holding a band and turning,
    // that displacement mixes an approach component with a lateral tracking component, and 0.2766
    // cannot distinguish "running me down" from "circling with me". This is rule 1's actual question:
    // if the warden closes, it is not kiteable and design 6.5's inference fails; if it only tracks
    // laterally, kiting holds and absolute displacement is the wrong quantity for rule 1.
    /** (tick, gap, wardenX, wardenZ) for the trailing window. */
    private final java.util.ArrayDeque<double[]> gapTrail = new java.util.ArrayDeque<>();
    private double gapAtFlightStart = -1;
    private double gapAtDraw = -1;
    private double gapWindowStart = -1;
    private double gapMin = Double.MAX_VALUE;
    private double gapMax = -1;
    private double gapAtEnd = -1;
    private double approachSum;      // warden displacement projected onto the bot-facing axis
    private double lateralSum;       // ... and perpendicular to it
    private double botPathLen;       // cumulative, vs botFled which is NET displacement
    private Vec3 lastBotPos;
    private Vec3 lastWardenPos;

    // --- signal B observation (diagnostic only; the pass gate is unchanged) -------------------
    // dGapDt measured at -0.0738 b/t, nine times below B's redefined threshold MIN_OPENING_RATE
    // (0.0084), sustained across all 60 window ticks in 30/30 engagements. If B does not fire here
    // it does not fire anywhere. Recording WHY it stays silent matters as much as whether it does:
    // intent gate unmet / threshold not crossed / never evaluated are three different defects.
    private boolean bFired;
    private int bFiredTick = -1;
    private double bGapAtFire = -1;
    private int bIntentTicks;        // ticks the monitor saw an intent to open distance
    private int bMaxFailingTicks;    // high-water mark of its consecutive-failing counter
    private int bEvaluatedTicks;     // ticks the target was actually in the monitor's map

    @Override
    public int[] arenaBounds() {
        return new int[]{-94, 30, -14, 14};
    }

    @Override
    public String name() {
        return "bot_warden_pursuit";
    }

    /** O-5(2): 봇 자율 이동 활성 여부를 전제로 명시한다. */
    @Override
    public String scenarioSpec() {
        return "봇 최대체력 400 + 매 틱 만피 회복(생존 계층이 틱을 가로채지 못하게), 워든 setInvulnerable(true). 유저 없음(TestUser.spawn 미호출) → Perception.java:131 level.players()가 봇 외 플레이어를 찾지 못해 perception().user==null → BotIdle.java:87-89 즉시 반환. 16장 자율 이동 비활성이며 판정 라인의 idleCommandedTicks:0로 값 확인된다(O-5(2)).";
    }

    /**
     * 30, matching {@code bot_coldstart_dist}'s engagement count exactly. The question this harness
     * feeds is "is the fleeing-target rate within 1~2x sd(0.0205) of the stationary rate, and is any
     * difference reproducible" — reproducibility cannot be judged from one engagement, and the
     * comparison target is a 30-engagement distribution, so this must produce its own
     * between-engagement sd over the same n. The per-trial premise (>=60 sampled ticks) only makes a
     * single trial's mean trustworthy; that is one independent window at W60 and says nothing at all
     * about spread.
     */
    @Override
    public int repeats() {
        return 30;
    }

    @Override
    public int timeoutTicks() {
        return RUN_TICKS + 60;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;

        for (int dx = -90; dx <= START_DIST + 6; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
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
        // NOT invulnerable: Warden.canTargetEntity rejects invulnerable targets, which would turn the
        // pursuit into a stroll and quietly measure the wrong thing.
        bot.setInvulnerable(false);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        warden = ctx.env.spawn(EntityType.WARDEN, new BlockPos(o.getX() + START_DIST, o.getY(), o.getZ()));
        if (warden != null) {
            warden.setInvulnerable(true);
            warden.increaseAngerAt(bot);
            warden.setAttackTarget(bot);
        }
        samples.clear();
        trail.clear();
        botStart = null;
        botFled = 0;
        trailingRate = 0;
        draw = -1;
        movingTicks = 0;
        gapTrail.clear();
        gapAtFlightStart = -1;
        gapAtDraw = -1;
        gapWindowStart = -1;
        gapMin = Double.MAX_VALUE;
        gapMax = -1;
        gapAtEnd = -1;
        approachSum = 0;
        lateralSum = 0;
        botPathLen = 0;
        lastBotPos = null;
        lastWardenPos = null;
        bFired = false;
        bFiredTick = -1;
        bGapAtFire = -1;
        bIntentTicks = 0;
        bMaxFailingTicks = 0;
        bEvaluatedTicks = 0;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || warden == null) {
            return true;
        }
        int t = ctx.elapsedTicks;
        bot.setHealth(bot.getMaxHealth());   // survival must not hijack the tick; speed is the subject
        if (t % 40 == 0) {
            warden.increaseAngerAt(bot);
            warden.setAttackTarget(bot);
        }
        if (t < SETTLE) {
            bot.setDeltaMovement(Vec3.ZERO);
            bot.moveTo(ctx.origin.getX() + 0.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5, 90.0F, 0.0F);
            return false;
        }
        if (botStart == null) {
            botStart = bot.position();
        }
        double gap = Math.hypot(warden.getX() - bot.getX(), warden.getZ() - bot.getZ());
        if (gapAtFlightStart < 0) {
            gapAtFlightStart = gap;
        }
        gapAtEnd = gap;
        gapMin = Math.min(gapMin, gap);
        gapMax = Math.max(gapMax, gap);
        if (lastBotPos != null) {
            botPathLen += Math.hypot(bot.getX() - lastBotPos.x, bot.getZ() - lastBotPos.z);
        }
        if (lastWardenPos != null) {
            // Decompose this tick's warden movement about the bot->warden axis.
            double ax = warden.getX() - bot.getX();
            double az = warden.getZ() - bot.getZ();
            double alen = Math.hypot(ax, az);
            if (alen > 1.0E-6) {
                double ux = ax / alen;
                double uz = az / alen;
                double dx = warden.getX() - lastWardenPos.x;
                double dz = warden.getZ() - lastWardenPos.z;
                double radial = dx * ux + dz * uz;      // + = moving away from the bot
                double lateral = dx * (-uz) + dz * ux;  // perpendicular
                approachSum += -radial;                 // + = closing on the bot
                lateralSum += Math.abs(lateral);
            }
        }
        lastBotPos = bot.position();
        lastWardenPos = warden.position();
        // Signal B, observed not consumed: the harness never acts on it.
        int failing = bot.kiteMonitor().closingTicks(warden);
        if (failing > 0 || bot.kiteMonitor().intendedOpen()) {
            bEvaluatedTicks++;
        }
        if (bot.kiteMonitor().intendedOpen()) {
            bIntentTicks++;
        }
        bMaxFailingTicks = Math.max(bMaxFailingTicks, failing);
        if (!bFired && bot.kiteMonitor().failing(warden)) {
            bFired = true;
            bFiredTick = t;
            bGapAtFire = gap;
            LOGGER.info("[PURSUIT] signal B fired t={} gap={} (flightStart t={})",
                    t, String.format("%.2f", gap), SETTLE);
        }
        gapTrail.addLast(new double[]{t, gap});
        while (!gapTrail.isEmpty() && t - gapTrail.peekFirst()[0] > SpeedObserver.WINDOW_TICKS) {
            gapTrail.removeFirst();
        }
        // The bot's own disengage path: engaging hands movement to the ranged controller, which keeps
        // the rule-3 band and therefore retreats from a closing warden.
        bot.rangedCombat().setTarget(warden);
        botFled = Math.hypot(bot.getX() - botStart.x, bot.getZ() - botStart.z);

        // Independent trailing-window rate, same window the observer uses.
        trail.addLast(new double[]{t, warden.getX(), warden.getZ()});
        while (!trail.isEmpty() && t - trail.peekFirst()[0] > SpeedObserver.WINDOW_TICKS) {
            trail.removeFirst();
        }
        if (trail.size() > 1) {
            double[] a = trail.peekFirst();
            double[] b = trail.peekLast();
            double sp = b[0] - a[0];
            if (sp > 0) {
                trailingRate = Math.hypot(b[1] - a[1], b[2] - a[2]) / sp;
            }
        }

        // Sample only while the bot is genuinely moving: a stalled bot would silently turn this back
        // into the stationary-target measurement the harness exists to distinguish itself from.
        if (bot.perception().botDisplacementPerTick > SpeedObserver.BOT_MOVING_MIN) {
            movingTicks++;
            for (TargetInfo ti : bot.perception().targets) {
                if (ti.entity == warden && ti.speedObserved) {
                    samples.add(ti.observedSpeed);
                    if (draw < 0 && movingTicks >= SpeedObserver.MIN_SPAN_TICKS) {
                        // First valid window taken entirely while the bot was in flight.
                        draw = ti.observedSpeed;
                        gapAtDraw = gap;
                        gapWindowStart = gapTrail.isEmpty() ? gap : gapTrail.peekFirst()[1];
                        LOGGER.info("[PURSUIT] draw={} at t={} movingTicks={} botFled={}",
                                String.format("%.4f", draw), t, movingTicks,
                                String.format("%.2f", botFled));
                    }
                    break;
                }
            }
        }
        return t >= RUN_TICKS;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        int n = samples.size();
        double runMean = n > 0 ? samples.stream().mapToDouble(Double::doubleValue).average().orElse(0) : 0;

        // PREMISE: enough ticks IN MOTION for one valid window, NOT "the bot escaped far enough".
        // Selecting on distance fled would select on how well the flight went, which correlates with
        // the warden being slow or far — biasing the pursuit rate low and manufacturing exactly the
        // "looks like the stationary value, so one distribution" conclusion this measurement exists
        // to test. botFled is reported, never gated on.
        boolean enoughMotion = movingTicks >= SpeedObserver.MIN_SPAN_TICKS;
        boolean gotDraw = draw >= 0;
        boolean ok = enoughMotion && gotDraw;

        double windowSpan = gapWindowStart >= 0 ? gapAtDraw - gapWindowStart : Double.NaN;
        double dGapDt = gapWindowStart >= 0 ? windowSpan / SpeedObserver.WINDOW_TICKS : Double.NaN;
        double gapNet = gapAtFlightStart >= 0 ? gapAtEnd - gapAtFlightStart : Double.NaN;
        LOGGER.info("[PURSUIT] RESULT draw={} gap {}->{} (net {}) windowDelta={} approach={} "
                        + "lateral={} botPath={} botFled={}",
                String.format("%.4f", draw), String.format("%.2f", gapAtFlightStart),
                String.format("%.2f", gapAtEnd), String.format("%+.2f", gapNet),
                String.format("%+.2f", windowSpan), String.format("%+.2f", approachSum),
                String.format("%.2f", lateralSum), String.format("%.2f", botPathLen),
                String.format("%.2f", botFled));
        String measured = String.format(
                "draw:%.4f,runMean:%.4f,movingTicks:%d,botFled:%.2f,botPathLen:%.2f,samples:%d,"
                        + "indepTrailing:%.4f,gapFlightStart:%.2f,gapWindowStart:%.2f,gapAtDraw:%.2f,"
                        + "gapWindowDelta:%+.2f,dGapDt:%+.5f,gapMin:%.2f,gapMax:%.2f,gapAtEnd:%.2f,"
                        + "gapNet:%+.2f,approachSum:%+.2f,lateralSum:%.2f,"
                        + "bFired:%b,bFiredTick:%d,bGapAtFire:%.2f,bIntentTicks:%d,"
                        + "bMaxFailingTicks:%d,bEvaluatedTicks:%d,bThreshold:%.4f,"
                        + "stationaryRef:%.4f,delta:%+.4f,botSprint:%.4f",
                draw, runMean, movingTicks, botFled, botPathLen, n, trailingRate,
                gapAtFlightStart, gapWindowStart, gapAtDraw, windowSpan, dGapDt, gapMin, gapMax,
                gapAtEnd, gapNet, approachSum, lateralSum,
                bFired, bFiredTick, bGapAtFire, bIntentTicks, bMaxFailingTicks, bEvaluatedTicks,
                com.aicompanion.bot.combat.KiteMonitor.MIN_OPENING_RATE, STATIONARY_REFERENCE,
                draw >= 0 ? draw - STATIONARY_REFERENCE : Double.NaN, CombatStats.BOT_SPRINT_SPEED);
        String expected = "premise: >=" + SpeedObserver.MIN_SPAN_TICKS + " ticks in motion and one "
                + "valid in-flight window — the pursuit rate itself is reported, never gated";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
