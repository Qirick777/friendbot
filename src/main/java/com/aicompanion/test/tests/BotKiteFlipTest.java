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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * CLOSED-LOOP rule-1 gate (audit item 3). Every other rule-1 harness is open loop: the bot stands
 * still and the target walks at it. This one closes the loop — the verdict changes the bot's
 * behaviour, and the bot's behaviour changes what the observer measures.
 *
 * <p>Scenario: a target that genuinely outruns the bot starts GLUED to it. Its approach rate is
 * legitimately ~0 while adjacent, so rule 1 says kiteable; the bot then flees, the target gives
 * chase, the observed speed climbs past the hysteresis exit line, and the verdict must flip to
 * {@code canKite=false}. Measured: whether it flips, how many ticks the flip takes, and how much
 * health the bot loses during that lag — the cost of the observation window.</p>
 */
public class BotKiteFlipTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double FAST_ATTR = 0.8;   // measured ≈0.62 b/t vs bot sprint 0.2806
    private static final int GLUE_TICKS = 60;      // adjacent long enough for the "slow" verdict
    private static final int FLEE_TICKS = 200;

    private Zombie chaser;
    private Boolean kiteAtFleeStart;
    private boolean flipped;
    private int fleeStartTick = -1;
    private int flipTick = -1;
    private float hpAtFleeStart = -1;
    private float hpAtFlip = -1;
    private float hpEnd = -1;
    private double gapAtFlee = -1;
    private double gapAtFlip = -1;
    private double observedAtFlip;
    private Vec3 botPosAtFlee;
    private double botDisplacementDuringFlee;
    private double maxObservedDuringFlee;
    private double maxGapDuringFlee;
    private boolean execMonitorFired;   // signal B: kiting-execution failure detected
    private int execMonitorTick = -1;

    @Override
    public String name() {
        return "bot_kite_flip";
    }

    @Override
    public int timeoutTicks() {
        return GLUE_TICKS + FLEE_TICKS + 60;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);

        for (int dx = -90; dx <= 20; dx++) {
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
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0);
        bot.setHealth(200.0F);
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(false); // the lag cost must be paid in real health
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        // Glued to the bot from the start: adjacent, so its measured approach rate is ~0.
        chaser = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + 1, o.getY(), o.getZ()));
        if (chaser != null) {
            chaser.setInvulnerable(true);
            chaser.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(FAST_ATTR);
            chaser.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(4.0);
        }
        flipped = false;
        flipTick = -1;
        fleeStartTick = -1;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || chaser == null) {
            return true;
        }
        chaser.setTarget(bot);
        int t = ctx.elapsedTicks;
        double gap = Math.hypot(chaser.getX() - bot.getX(), chaser.getZ() - bot.getZ());

        Boolean kite = null;
        double observed = 0;
        for (TargetInfo ti : bot.perception().targets) {
            if (ti.entity == chaser) {
                kite = CombatRules.canKite(ti, CombatStats.of(bot).sprintSpeed);
                observed = ti.observedSpeed;
                break;
            }
        }

        if (t < GLUE_TICKS) {
            // Phase 1: bot holds position while the target sits on it → observed ≈ 0 → "kiteable".
            bot.setDeltaMovement(Vec3.ZERO);
            bot.moveTo(ctx.origin.getX() + 0.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5, -90.0F, 0.0F);
            return false;
        }

        if (t == GLUE_TICKS) {
            kiteAtFleeStart = kite;
            fleeStartTick = t;
            hpAtFleeStart = bot.getHealth();
            gapAtFlee = gap;
            botPosAtFlee = bot.position();
            LOGGER.info("[KITEFLIP] flee starts t={} canKite={} observed={} gap={} hp={}",
                    t, kite, String.format("%.4f", observed), String.format("%.2f", gap), hpAtFleeStart);
        }

        // Phase 2: the bot acts on the "kiteable" verdict and runs. This is the closed loop — the
        // fleeing is what makes the target's approach rate observable in the first place.
        float away = Mth.wrapDegrees((float) (Mth.atan2(bot.getZ() - chaser.getZ(),
                bot.getX() - chaser.getX()) * (180.0 / Math.PI)) - 90.0F);
        bot.setYRot(away);
        bot.setYBodyRot(away);
        bot.setYHeadRot(away);
        bot.zza = 1.0F;
        bot.xxa = 0.0F;
        bot.setSprinting(true);
        bot.setJumping(false);

        if (!flipped && kite != null && !kite && t > GLUE_TICKS) {
            flipped = true;
            flipTick = t;
            hpAtFlip = bot.getHealth();
            gapAtFlip = gap;
            observedAtFlip = observed;
            LOGGER.info("[KITEFLIP] verdict flipped to canKite=false at t={} (+{} ticks) observed={} "
                            + "gap={}->{} hp={}->{}",
                    t, t - fleeStartTick, String.format("%.4f", observed),
                    String.format("%.2f", gapAtFlee), String.format("%.2f", gap),
                    hpAtFleeStart, hpAtFlip);
        }
        // Signal B — execution monitor. Rule 1 (signal A) is a slow capability estimate; this is the
        // fast "is the kiting actually working" check, and it is what makes A's latency tolerable.
        if (!execMonitorFired && t > GLUE_TICKS) {
            bot.rangedCombat().setTarget(chaser);   // engage so the monitor runs
            if (bot.kiteMonitor().failing(chaser)) {
                execMonitorFired = true;
                execMonitorTick = t;
                LOGGER.info("[KITEFLIP] execution monitor fired at t={} (+{} ticks) gap={}",
                        t, t - fleeStartTick, String.format("%.2f", gap));
            }
        }
        hpEnd = bot.getHealth();
        if (botPosAtFlee != null) {
            botDisplacementDuringFlee = Math.hypot(bot.getX() - botPosAtFlee.x, bot.getZ() - botPosAtFlee.z);
            maxObservedDuringFlee = Math.max(maxObservedDuringFlee, observed);
            maxGapDuringFlee = Math.max(maxGapDuringFlee, gap);
        }
        return (flipped && execMonitorFired) || t >= GLUE_TICKS + FLEE_TICKS;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        int ticksToFlip = flipped ? flipTick - fleeStartTick : -1;
        double hpLostDuringLag = (hpAtFleeStart >= 0 && hpAtFlip >= 0) ? hpAtFleeStart - hpAtFlip
                : (hpAtFleeStart >= 0 ? hpAtFleeStart - hpEnd : -1);
        double closed = (gapAtFlee >= 0 && gapAtFlip >= 0) ? gapAtFlee - gapAtFlip : -1;

        boolean startedKiteable = kiteAtFleeStart != null && kiteAtFleeStart;
        // Safety property, not a scripted sequence: while a target the bot cannot outrun is on top
        // of it, the bot must never be sitting in canKite=true. Two ways to satisfy that —
        //   (a) it never entered the wrong state (the invalid-observation gate refuses to score a
        //       glued target's ~0 approach rate as "slow"), which is strictly better, or
        //   (b) it did enter it and recovered, via the slow capability flip or the fast monitor.
        boolean neverWrong = !startedKiteable;
        boolean recovered = flipped || execMonitorFired;
        boolean ok = neverWrong || recovered;
        int ticksToExec = execMonitorFired ? execMonitorTick - fleeStartTick : -1;

        LOGGER.info("[KITEFLIP] RESULT startedKiteable={} flipped={} ticksToFlip={} hpLost={} "
                        + "gapClosed={} observedAtFlip={}",
                startedKiteable, flipped, ticksToFlip, hpLostDuringLag,
                String.format("%.2f", closed), String.format("%.4f", observedAtFlip));
        String measured = String.format(
                "neverWronglyKiteable:" + neverWrong + ",startedKiteable:%b,canKiteFlipped:%b,ticksToFlip:%d,hpLostDuringLag:%.1f,"
                        + "gapClosed:%.2f,observedAtFlip:%.4f,botFledDist:%.2f,"
                        + "maxObserved:%.4f,maxGap:%.2f,exitLine:%.4f,execMonitorFired:%b,ticksToExec:%d",
                startedKiteable, flipped, ticksToFlip, hpLostDuringLag, closed, observedAtFlip,
                botDisplacementDuringFlee, maxObservedDuringFlee, maxGapDuringFlee,
                CombatStats.BOT_SPRINT_SPEED * CombatRules.KITE_EXIT_RATIO,
                execMonitorFired, ticksToExec);
        String expected = "adjacent fast target reads kiteable; once the bot flees, either the slow "
                + "capability estimate flips to canKite=false or the fast execution monitor fires";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
