package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.combat.CombatRules;
import com.aicompanion.bot.combat.CombatStats;
import com.aicompanion.bot.combat.Layer2Profile;
import com.aicompanion.bot.combat.Layer2Registry;
import com.aicompanion.bot.combat.TacticalDecision;
import com.aicompanion.bot.perception.TargetInfo;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * T4.6 [검증] part 1 — the tactical dump for a warden must be
 * {카이팅=true, 원거리강제, 밴드16~20, 방패off}. Three variants share this harness so the SAME four
 * values can be compared across them (see {@link #variant()}):
 *
 * <ul>
 *   <li>{@code bot_warden_tactics} — a real warden with its layer-2 profile.</li>
 *   <li>{@code bot_warden_noname} — a ZOMBIE given warden stats + the warden profile. Must produce
 *       the identical four values, proving the tactics come from data, not the mob's identity.</li>
 *   <li>{@code bot_warden_generic} — a real WARDEN whose profile differs ONLY in armorPiercing
 *       (false). Exactly one value must flip (shield off→on), isolating the cause.</li>
 * </ul>
 */
public abstract class BotWardenTacticsTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    protected enum Variant { WARDEN, NONAME, GENERIC }

    private LivingEntity target;
    @Nullable
    private TacticalDecision decision;
    private String targetType = "none";
    private double measuredSpeedBpt;

    protected abstract Variant variant();

    @Override
    public int timeoutTicks() {
        return 120;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);
        Layer2Registry.clearAllOverrides();

        for (int dx = -4; dx <= 24; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
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
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        BlockPos spot = new BlockPos(o.getX() + 8, o.getY(), o.getZ());
        switch (variant()) {
            case WARDEN -> {
                target = ctx.env.spawn(EntityType.WARDEN, spot);
                targetType = "warden(layer2=WARDEN)";
            }
            case NONAME -> {
                // A zombie wearing the warden's measurable stats AND the warden layer-2 profile.
                target = ctx.env.spawn(EntityType.ZOMBIE, spot);
                if (target != null) {
                    target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(500.0);
                    target.setHealth(500.0F);
                    target.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(30.0);
                    target.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.3);
                    target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
                    Layer2Registry.override(target, Layer2Registry.WARDEN);
                }
                targetType = "zombie(layer2=WARDEN)";
            }
            case GENERIC -> {
                // A real warden — everything identical to the WARDEN case EXCEPT armorPiercing.
                target = ctx.env.spawn(EntityType.WARDEN, spot);
                if (target != null) {
                    Layer2Registry.override(target, new Layer2Profile(
                            false, // <-- the ONLY difference
                            Layer2Registry.WARDEN.rangedRangeXZ,
                            Layer2Registry.WARDEN.rangedRangeY,
                            Layer2Registry.WARDEN.fixedDamage,
                            Layer2Registry.WARDEN.chargeDetector));
                }
                targetType = "warden(layer2=pierce:false only)";
            }
        }
        if (target != null) {
            target.setInvulnerable(true);
            if (target instanceof net.minecraft.world.entity.Mob mob) {
                mob.setNoAi(true); // judging the DUMP, not behaviour
            }
        }
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot != null && target != null) {
            for (TargetInfo t : bot.perception().targets) {
                if (t.entity == target) {
                    decision = CombatRules.evaluate(t, CombatStats.of(bot));
                    measuredSpeedBpt = t.observedSpeed;
                    break;
                }
            }
        }
        // 80, not 40: SpeedObserver.MIN_SPAN_TICKS is 60, so judging at 40 asks rule 1 for a verdict
        // before an observation can exist and always reads the conservative cold-start false. The
        // last PASS here (kiting:true, speedBpt:0.0000) dates from when the minimum span was 20; the
        // ch.18 correction raised window/min-span to 60 and left this harness judging too early.
        return ctx.elapsedTicks >= 80;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        if (decision == null) {
            return BotTestResult.fail("decision:null,target:" + targetType, "tactical dump available");
        }
        boolean expectPierce = variant() != Variant.GENERIC;

        boolean kiting = decision.canKite;
        boolean forcedRanged = !decision.allowMelee;
        boolean bandOk = Math.abs(decision.bandMin - 16.0) < 0.01 && Math.abs(decision.bandMax - 20.0) < 0.01;
        boolean shieldOff = !decision.shieldOn;

        // WARDEN and NONAME must both give the documented four values; GENERIC must differ in
        // exactly one (shield on), with the other three unchanged.
        boolean ok = kiting && forcedRanged && bandOk && (expectPierce == shieldOff);

        LOGGER.info("[WARDEN] {} dump={} speedBpt={} botSprint={}",
                targetType, decision, String.format("%.4f", measuredSpeedBpt),
                CombatStats.BOT_SPRINT_SPEED);
        String measured = String.format("target:%s,kiting:%b,forcedRanged:%b,band:%.1f~%.1f,shieldOff:%b,speedBpt:%.4f",
                targetType, kiting, forcedRanged, decision.bandMin, decision.bandMax, shieldOff, measuredSpeedBpt);
        String expected = expectPierce
                ? "kiting=true AND forcedRanged=true AND band=16.0~20.0 AND shieldOff=true"
                : "same three values BUT shieldOff=false (only armorPiercing differs)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    /** {@code bot_warden_tactics} — the design [검증] case. */
    public static class Warden extends BotWardenTacticsTest {
        @Override
        public String name() {
            return "bot_warden_tactics";
        }

        @Override
        protected Variant variant() {
            return Variant.WARDEN;
        }
    }

    /** {@code bot_warden_noname} — (가)① same data on a differently-named mob → same tactics. */
    public static class NoName extends BotWardenTacticsTest {
        @Override
        public String name() {
            return "bot_warden_noname";
        }

        @Override
        protected Variant variant() {
            return Variant.NONAME;
        }
    }

    /** {@code bot_warden_generic} — (가)② a warden with armorPiercing=false → shield flips on. */
    public static class Generic extends BotWardenTacticsTest {
        @Override
        public String name() {
            return "bot_warden_generic";
        }

        @Override
        protected Variant variant() {
            return Variant.GENERIC;
        }
    }
}
