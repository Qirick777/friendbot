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
 * (3c) MIRROR IMAGE of {@link BotKiteFlipTest}. The flip test shows the invalid-observation gate
 * refusing to call a glued fast target "slow". That gate can fail the other way: if it blocks too
 * much, a target that has never produced a valid observation stays on the conservative
 * {@code canKite=false} forever, and the bot never kites ANY mob — a failure no existing harness
 * can see, because "false" is also the correct answer in the flip test.
 *
 * <p>Scenario: a genuinely SLOW target (default zombie, ≈0.114 b/t vs the bot's 0.2806 sprint) starts
 * GLUED to a pinned bot. Every tick of that phase is an invalid observation, so the estimate must
 * stay unset and the verdict must be the conservative false. Then the bot breaks away; the gap opens
 * past {@code GLUED_GAP} and the bot is moving, so observations become valid, and once a full window
 * has accumulated the verdict must RECOVER to {@code canKite=true}.</p>
 *
 * <p>Measured: {@code kiteRecovered} and {@code ticksToRecover} — the recovery latency the gate
 * costs.</p>
 */
public class BotKiteRecoverTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int GLUE_TICKS = 80;    // longer than MIN_SPAN_TICKS(60): if the gate were
                                                 // absent, a full window of ~0 approach would have
                                                 // been scored "slow" well before this ends.
    private static final int FLEE_TICKS = 240;

    private Zombie chaser;
    private boolean gatedAtGlue;         // verdict was still the conservative false when flight began
    private boolean hadObsAtGlue;        // ... and no valid observation had been banked
    private boolean kiteRecovered;
    private int fleeStartTick = -1;
    private int recoverTick = -1;
    private double observedAtRecover;
    private double gapAtRecover;
    private double maxGap;
    private double botFledDist;
    private Vec3 botPosAtFlee;
    private boolean harnessAssistedEscape;

    @Override
    public int[] arenaBounds() {
        return new int[]{-94, 24, -14, 14};
    }

    @Override
    public String name() {
        return "bot_kite_recover";
    }

    @Override
    public int timeoutTicks() {
        return GLUE_TICKS + FLEE_TICKS + 60;
    }

    /** P-6 (유형 #9): 이 판정이 측정된 세계의 전제. */
    @Override
    public String scenarioSpec() {
        return "setBaseValue(MAX_HEALTH) 호출값: 200.0 (봇/표적 구분은 setup() 참조). bot.setInvulnerable(false). 스폰 "
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
        bot.setInvulnerable(false);   // stay a valid target for the zombie's AI
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        // Default-speed zombie, glued from tick 0. No attribute override: the point is that a mob the
        // bot really CAN outrun must end up judged kiteable.
        chaser = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + 1, o.getY(), o.getZ()));
        if (chaser != null) {
            chaser.setInvulnerable(true);
            chaser.setPersistenceRequired();
            chaser.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(1.0);
        }
        gatedAtGlue = false;
        hadObsAtGlue = false;
        kiteRecovered = false;
        fleeStartTick = -1;
        recoverTick = -1;
        maxGap = 0;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || chaser == null) {
            return true;
        }
        chaser.setTarget(bot);
        bot.setHealth(bot.getMaxHealth());   // the glue phase is not about damage
        int t = ctx.elapsedTicks;
        double gap = Math.hypot(chaser.getX() - bot.getX(), chaser.getZ() - bot.getZ());
        maxGap = Math.max(maxGap, gap);

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
            // Phase 1: pinned bot + adjacent target → every tick is an invalid observation.
            bot.setDeltaMovement(Vec3.ZERO);
            bot.moveTo(ctx.origin.getX() + 0.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5, -90.0F, 0.0F);
            return false;
        }

        if (t == GLUE_TICKS) {
            fleeStartTick = t;
            botPosAtFlee = bot.position();
            gatedAtGlue = kite != null && !kite;
            hadObsAtGlue = bot.perception().speeds.hasObservation(chaser);
            LOGGER.info("[KITEREC] glue ends t={} canKite={} hasObservation={} observed={} gap={}",
                    t, kite, hadObsAtGlue, String.format("%.4f", observed), String.format("%.2f", gap));
        }

        // Phase 2: break away, using the bot's OWN disengage path. Engaging the target hands
        // movement to BotRangedCombat, which keeps the rule-3 band and therefore backs off a mob
        // standing on top of it. Writing zza here would be pointless: the harness ticks in the END
        // phase, and the combat/mover layer rewrites the movement inputs during the next bot tick
        // (measured: with no target engaged the bot never left the zombie, maxGap 4.11 over 240t).
        bot.rangedCombat().setTarget(chaser);
        if (botPosAtFlee != null) {
            botFledDist = Math.hypot(bot.getX() - botPosAtFlee.x, bot.getZ() - botPosAtFlee.z);
        }
        // Fallback: if the bot's own layers still have not broken contact after a full window, drive
        // the separation from the harness so the OBSERVER — the thing under test — is still
        // exercised. Reported as a separate value; it is not silently folded into the verdict.
        // It engages only after TWO full windows, so it can never be confused with the first
        // possible recovery (which needs exactly one window of valid samples).
        if (t > GLUE_TICKS + 2 * com.aicompanion.bot.perception.SpeedObserver.MIN_SPAN_TICKS
                && gap < com.aicompanion.bot.perception.SpeedObserver.GLUED_GAP + 1.0) {
            harnessAssistedEscape = true;
            float away = Mth.wrapDegrees((float) (Mth.atan2(bot.getZ() - chaser.getZ(),
                    bot.getX() - chaser.getX()) * (180.0 / Math.PI)) - 90.0F);
            double rad = Math.toRadians(away + 90.0F);
            bot.moveTo(bot.getX() + Math.cos(rad) * 0.25, bot.getY(), bot.getZ() + Math.sin(rad) * 0.25,
                    away, 0.0F);
        }

        if (!kiteRecovered && kite != null && kite && t > GLUE_TICKS) {
            kiteRecovered = true;
            recoverTick = t;
            observedAtRecover = observed;
            gapAtRecover = gap;
            LOGGER.info("[KITEREC] recovered canKite=true at t={} (+{} ticks) observed={} gap={}",
                    t, t - fleeStartTick, String.format("%.4f", observed), String.format("%.2f", gap));
        }
        return kiteRecovered || t >= GLUE_TICKS + FLEE_TICKS;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        int ticksToRecover = kiteRecovered ? recoverTick - fleeStartTick : -1;
        // The premise must hold, or the test is vacuous: if the verdict was ALREADY true while the
        // target sat glued to a pinned bot, nothing was gated and "recovery" measures nothing.
        boolean premise = gatedAtGlue && !hadObsAtGlue;
        boolean ok = premise && kiteRecovered;

        LOGGER.info("[KITEREC] RESULT gatedAtGlue={} hadObsAtGlue={} kiteRecovered={} ticksToRecover={}",
                gatedAtGlue, hadObsAtGlue, kiteRecovered, ticksToRecover);
        String measured = String.format(
                "gatedAtGlue:%b,hadObsAtGlue:%b,kiteRecovered:%b,ticksToRecover:%d,"
                        + "observedAtRecover:%.4f,gapAtRecover:%.2f,maxGap:%.2f,botFledDist:%.2f,"
                        + "harnessAssistedEscape:%b,enterLine:%.4f,minSpan:%d",
                gatedAtGlue, hadObsAtGlue, kiteRecovered, ticksToRecover, observedAtRecover,
                gapAtRecover, maxGap, botFledDist, harnessAssistedEscape,
                CombatStats.BOT_SPRINT_SPEED * CombatRules.KITE_ENTER_RATIO,
                com.aicompanion.bot.perception.SpeedObserver.MIN_SPAN_TICKS);
        String expected = "glued slow target starts gated (canKite=false, no valid observation); once "
                + "the bot breaks away and a valid window accumulates, the verdict recovers to "
                + "canKite=true — the gate blocks bad data, not all data";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
