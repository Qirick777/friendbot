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
    private Vec3 botStart;
    private double botFled;
    private double trailingRate;
    private final java.util.ArrayDeque<double[]> trail = new java.util.ArrayDeque<>();

    @Override
    public int[] arenaBounds() {
        return new int[]{-94, 30, -14, 14};
    }

    @Override
    public String name() {
        return "bot_warden_pursuit";
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
            for (TargetInfo ti : bot.perception().targets) {
                if (ti.entity == warden && ti.speedObserved) {
                    samples.add(ti.observedSpeed);
                    break;
                }
            }
        }
        return t >= RUN_TICKS;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        int n = samples.size();
        double mean = 0;
        double sd = 0;
        double min = 0;
        double max = 0;
        if (n > 0) {
            mean = samples.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            min = samples.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            max = samples.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            if (n > 1) {
                final double m = mean;
                double v = samples.stream().mapToDouble(d -> (d - m) * (d - m)).sum() / (n - 1);
                sd = Math.sqrt(v);
            }
        }
        // PREMISE only. Judging on "does it match the stationary value" would decide the redesign's
        // question by choosing the threshold, which is the thing this measurement exists to avoid.
        boolean fled = botFled > 10.0;
        boolean enough = n >= 60;
        boolean ok = fled && enough;

        LOGGER.info("[PURSUIT] n={} mean={} sd={} trailing={} botFled={} vsStationary={}",
                n, String.format("%.4f", mean), String.format("%.4f", sd),
                String.format("%.4f", trailingRate), String.format("%.2f", botFled),
                String.format("%+.4f", mean - STATIONARY_REFERENCE));
        String measured = String.format(
                "pursuitRate:%.4f(%.1f%%),sd:%.4f,min:%.4f,max:%.4f,samples:%d,indepTrailing:%.4f,"
                        + "botFled:%.2f,stationaryRef:%.4f,delta:%+.4f,botSprint:%.4f",
                mean, 100.0 * mean / CombatStats.BOT_SPRINT_SPEED, sd, min, max, n, trailingRate,
                botFled, STATIONARY_REFERENCE, mean - STATIONARY_REFERENCE,
                CombatStats.BOT_SPRINT_SPEED);
        String expected = "premise: the bot actually fled (>10 blocks) and >=60 sampled ticks — the "
                + "pursuit rate itself is reported for the redesign, not gated";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
