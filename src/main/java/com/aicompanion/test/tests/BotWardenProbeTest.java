package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * T4.6 STEP-1 PROBE (no feature wiring yet). Establishes two facts the plan depends on, by runtime
 * measurement rather than assumption:
 *
 * <ul>
 *   <li><b>(나) charge signal</b> — whether the sonic-boom charge window is readable SERVER-side.
 *       Candidate: the Brain memory {@code SONIC_BOOM_SOUND_DELAY} that {@code SonicBoom.start()}
 *       sets for 34 ticks. The client-only {@code sonicBoomAnimationState}/particles are rejected.</li>
 *   <li><b>(R-1) rule-1 speed units</b> — the bot's measured sprint speed and the warden's measured
 *       chase speed, both in blocks/tick, so rule 1 can be compared in one real unit instead of raw
 *       attribute values of unverified scale.</li>
 * </ul>
 */
public class BotWardenProbeTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int PHASE_A_END = 80;    // bot sprint measurement
    private static final int PHASE_B_END = 240;   // warden chase measurement
    private static final int SONIC_DIST = 12;     // inside SonicBoom's 15-block horizontal window

    private Warden warden;

    // R-1 measurement accumulators.
    private Vec3 lastBotPos;
    private double botDist;
    private int botTicks;
    private Vec3 lastWardenPos;
    private double wardenDist;
    private int wardenTicks;

    // (나) charge observation.
    private boolean chargeSeen;
    private int chargeFirstTick = -1;
    private int chargeTicksHeld;
    private boolean cooldownSeen;
    private float botHpAtChargeStart = -1;
    private float botHpMin = Float.MAX_VALUE;
    private boolean prevCharging;

    @Override
    public String name() {
        return "bot_warden_probe";
    }

    /** O-5(2): 봇 자율 이동 활성 여부를 전제로 명시한다. */
    @Override
    public String scenarioSpec() {
        return "워든 어트리뷰트/실측 속도 프로브. 유저 없음(TestUser.spawn 미호출) → Perception.java:131 level.players()가 봇 외 플레이어를 찾지 못해 perception().user==null → BotIdle.java:87-89 즉시 반환. 16장 자율 이동 비활성이며 판정 라인의 idleCommandedTicks:0로 값 확인된다(O-5(2)).";
    }

    @Override
    public int timeoutTicks() {
        return 900;
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return new int[]{-6, 70, -6, 6};
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);

        for (int dx = -6; dx <= 70; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
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
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        bot.setHealth(20.0F);
        bot.getFoodData().setFoodLevel(20); // sprint requires food > 6
        // NOT invulnerable: Warden.canTargetEntity() rejects invulnerable entities, and phase C
        // needs a real sonic-boom damage reading.
        bot.setInvulnerable(false);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();
        // Phase A: sprint straight down +X.
        bot.mover().moveTo(o.getX() + 65.0, o.getZ() + 0.5);

        warden = ctx.env.spawn(EntityType.WARDEN, new BlockPos(o.getX() + 50, o.getY(), o.getZ() + 4));
        if (warden != null) {
            warden.setInvulnerable(true); // it must survive the whole probe
        }

        lastBotPos = bot.position();
        lastWardenPos = warden != null ? warden.position() : Vec3.ZERO;
        LOGGER.info("[WARDENPROBE] setup: wardenAttrSpeed={} botSprintConst(T3.2)={}",
                warden != null ? warden.getAttributeValue(Attributes.MOVEMENT_SPEED) : -1,
                com.aicompanion.bot.combat.CombatStats.BOT_SPRINT_SPEED);
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || warden == null) {
            return true;
        }
        int t = ctx.elapsedTicks;

        if (t < PHASE_A_END) {
            // --- Phase A: measure the BOT's sprint speed (blocks/tick), skipping acceleration. ---
            bot.setSprinting(true);
            Vec3 p = bot.position();
            if (t > 20) {
                botDist += Math.hypot(p.x - lastBotPos.x, p.z - lastBotPos.z);
                botTicks++;
            }
            lastBotPos = p;
            if (t == PHASE_A_END - 1) {
                LOGGER.info("[WARDENPROBE] phaseA bot sprint measured={} b/t over {} ticks (sprinting={})",
                        fmt(botDist / Math.max(1, botTicks)), botTicks, bot.isSprinting());
            }
            return false;
        }

        if (t == PHASE_A_END) {
            // --- Phase B setup: freeze the bot, make the warden chase it. ---
            bot.mover().stop();
            bot.setSprinting(false);
            bot.setDeltaMovement(Vec3.ZERO);
            bot.moveTo(ctx.origin.getX() + 0.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5, 90.0F, 0.0F);
            warden.moveTo(ctx.origin.getX() + 30.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5, -90.0F, 0.0F);
            warden.increaseAngerAt(bot);
            warden.setAttackTarget(bot);
            lastWardenPos = warden.position();
            LOGGER.info("[WARDENPROBE] phaseB start: warden angry={} target set", warden.getAngerLevel());
            return false;
        }

        if (t < PHASE_B_END) {
            // --- Phase B: measure the WARDEN's chase speed (blocks/tick). ---
            bot.setDeltaMovement(Vec3.ZERO);
            bot.moveTo(ctx.origin.getX() + 0.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5, 90.0F, 0.0F);
            Vec3 w = warden.position();
            double step = Math.hypot(w.x - lastWardenPos.x, w.z - lastWardenPos.z);
            if (t > PHASE_A_END + 40 && step > 1.0E-4) { // ignore emerge/roar animation lock-in
                wardenDist += step;
                wardenTicks++;
            }
            lastWardenPos = w;
            if (t == PHASE_B_END - 1) {
                LOGGER.info("[WARDENPROBE] phaseB warden chase measured={} b/t over {} moving ticks",
                        fmt(wardenDist / Math.max(1, wardenTicks)), wardenTicks);
            }
            return false;
        }

        if (t == PHASE_B_END) {
            // --- Phase C setup: park the warden inside the sonic window and immobilize it, so the
            // SonicBoom behaviour (not melee) is what runs. The charge memory must arise NATURALLY.
            warden.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0);
            warden.moveTo(ctx.origin.getX() + SONIC_DIST + 0.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5,
                    -90.0F, 0.0F);
            warden.increaseAngerAt(bot);
            warden.setAttackTarget(bot);
            LOGGER.info("[WARDENPROBE] phaseC start: warden parked at {} blocks, waiting for natural charge",
                    SONIC_DIST);
            return false;
        }

        // --- Phase C: watch the SERVER-side charge memory. ---
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(ctx.origin.getX() + 0.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5, 90.0F, 0.0F);
        if (t % 40 == 0) {
            warden.increaseAngerAt(bot); // keep it angry so the FIGHT activity stays active
        }

        boolean charging = warden.getBrain().hasMemoryValue(MemoryModuleType.SONIC_BOOM_SOUND_DELAY);
        boolean cooling = warden.getBrain().hasMemoryValue(MemoryModuleType.SONIC_BOOM_COOLDOWN);
        if (cooling) {
            cooldownSeen = true;
        }
        botHpMin = Math.min(botHpMin, bot.getHealth());
        if (charging) {
            chargeTicksHeld++;
            if (!chargeSeen) {
                chargeSeen = true;
                chargeFirstTick = t;
                botHpAtChargeStart = bot.getHealth();
                LOGGER.info("[WARDENPROBE] CHARGE DETECTED (server Brain SONIC_BOOM_SOUND_DELAY) at t={} "
                        + "dist={} botHp={}", t, fmt(bot.distanceTo(warden)), bot.getHealth());
            }
        } else if (prevCharging) {
            LOGGER.info("[WARDENPROBE] charge window ended at t={} heldTicks={} botHp={} (fire happens here)",
                    t, chargeTicksHeld, bot.getHealth());
        }
        prevCharging = charging;

        return chargeSeen && !charging && t > chargeFirstTick + 40;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        double botSpeed = botDist / Math.max(1, botTicks);
        double wardenSpeed = wardenDist / Math.max(1, wardenTicks);
        double marginPct = botSpeed > 0 ? ((botSpeed - wardenSpeed) / botSpeed) * 100.0 : 0.0;
        double sonicDamage = botHpAtChargeStart >= 0 ? botHpAtChargeStart - botHpMin : -1;

        LOGGER.info("[WARDENPROBE] RESULT botSprint={} b/t, wardenChase={} b/t, margin={}%, "
                        + "chargeSeen={} at t={} heldTicks={} cooldownSeen={} sonicDamage={}",
                fmt(botSpeed), fmt(wardenSpeed), fmt(marginPct), chargeSeen, chargeFirstTick,
                chargeTicksHeld, cooldownSeen, fmt(sonicDamage));

        boolean speedsMeasured = botTicks > 10 && wardenTicks > 10;
        boolean ok = speedsMeasured && chargeSeen;
        String measured = String.format(
                "botSprint:%.4f,wardenChase:%.4f,margin:%.1f%%,chargeSeen:%b(t=%d,held=%d),sonicDamage:%.1f",
                botSpeed, wardenSpeed, marginPct, chargeSeen, chargeFirstTick, chargeTicksHeld, sonicDamage);
        String expected = "both speeds measured (b/t) AND server-side SONIC_BOOM_SOUND_DELAY observed";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }
}
