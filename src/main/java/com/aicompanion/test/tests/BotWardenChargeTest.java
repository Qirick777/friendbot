package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.combat.Layer2Registry;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * T4.6 (2b)/(2c) — the sonic-charge escape reflex, with its control.
 *
 * <p>The bot is parked INSIDE the sonic window (12 blocks) so the warden genuinely starts a sonic
 * boom; the charge memory is NEVER injected — it arises from vanilla's {@code SonicBoom} behaviour
 * and is read server-side via the layer-2 profile. On detection the bot must sprint beyond the
 * 15-block horizontal window within the 34-tick charge, and take NO sonic damage.</p>
 *
 * <p>{@code bot_warden_charge_none} is the control: identical setup, but the bot is pinned in place,
 * so the same boom lands and costs it health. Without that contrast, "distance increased" would not
 * show the reflex prevented anything.</p>
 */
public abstract class BotWardenChargeTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int START_DIST = 12;   // inside SonicBoom's 15-block horizontal window
    private static final double SONIC_XZ = 15.0;

    private Warden warden;
    private boolean chargeSeen;
    private int chargeTick = -1;
    private double preChargeDist = -1;
    private double postChargeDist = -1;
    private int ticksToEscape = -1;
    private boolean escapedBeyond15;
    private float hpAtCharge = -1;
    private float hpMin = Float.MAX_VALUE;
    private boolean prevCharging;
    private boolean boomFired;

    /** true = the escape reflex may run; false = the bot is pinned (control). */
    protected abstract boolean allowEscape();

    @Override
    public int timeoutTicks() {
        return 900;
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return new int[]{-50, 20, -6, 6};
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
        // NORMAL difficulty: on EASY vanilla halves player damage (10 → 6), which would muddy the
        // documented "fixed 10" sonic reading in the control.
        ctx.server.setDifficulty(Difficulty.NORMAL, true);

        for (int dx = -50; dx <= 20; dx++) {
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
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40.0); // survive a 10-damage boom twice
        bot.setHealth(40.0F);
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(false); // Warden.canTargetEntity rejects invulnerable targets
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        warden = ctx.env.spawn(EntityType.WARDEN, new BlockPos(o.getX() + START_DIST, o.getY(), o.getZ()));
        if (warden != null) {
            warden.setInvulnerable(true);
            warden.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0); // stay put: sonic, not melee
            warden.increaseAngerAt(bot);
            warden.setAttackTarget(bot);
        }
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || warden == null) {
            return true;
        }
        int t = ctx.elapsedTicks;
        if (t % 40 == 0) {
            warden.increaseAngerAt(bot); // keep the FIGHT activity alive
        }
        // Warden is immobilised, so keep it anchored at the start spot.
        warden.moveTo(ctx.origin.getX() + START_DIST + 0.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5,
                -90.0F, 0.0F);

        if (!allowEscape()) {
            // CONTROL: pin the bot so the reflex cannot move it out of the window.
            bot.setDeltaMovement(Vec3.ZERO);
            bot.moveTo(ctx.origin.getX() + 0.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5, 90.0F, 0.0F);
        }

        double dist = Math.hypot(bot.getX() - warden.getX(), bot.getZ() - warden.getZ());
        boolean charging = bot.perception().targets.stream()
                .anyMatch(x -> x.entity == warden && x.layer2Profile.isCharging(warden));

        if (charging && !chargeSeen) {
            chargeSeen = true;
            chargeTick = t;
            preChargeDist = dist;
            hpAtCharge = bot.getHealth();
            LOGGER.info("[WARDEN] charge detected at t={} dist={} hp={} (source=SONIC_BOOM_SOUND_DELAY)",
                    t, String.format("%.2f", dist), hpAtCharge);
        }
        if (chargeSeen) {
            hpMin = Math.min(hpMin, bot.getHealth());
            if (!escapedBeyond15 && dist > SONIC_XZ) {
                escapedBeyond15 = true;
                ticksToEscape = t - chargeTick;
                LOGGER.info("[WARDEN] escaped beyond sonic range at t={} (+{} ticks) dist={}",
                        t, ticksToEscape, String.format("%.2f", dist));
            }
        }
        if (prevCharging && !charging) {
            boomFired = true;
            postChargeDist = dist;
            LOGGER.info("[WARDEN] charge window ended at t={} dist={} hp={}",
                    t, String.format("%.2f", dist), bot.getHealth());
        }
        prevCharging = charging;

        return (boomFired && t > chargeTick + 40) || t >= 880;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        double damage = hpAtCharge >= 0 ? hpAtCharge - hpMin : -1;
        boolean ok;
        String expected;
        if (allowEscape()) {
            ok = chargeSeen && boomFired && escapedBeyond15 && damage < 0.01;
            expected = "charge detected → escaped beyond 15 within the charge AND zero sonic damage";
        } else {
            ok = chargeSeen && boomFired && !escapedBeyond15 && damage >= 9.99;
            expected = "control: pinned inside 15 → takes the documented fixed 10 sonic damage";
        }
        String measured = String.format(
                "charge:%b(t=%d),dist:%.2f->%.2f,escapedBeyond15:%b,ticksToEscape:%d,hp:%.1f->%.1f,damage:%.1f,"
                        + "chargeSource:SONIC_BOOM_SOUND_DELAY(natural)",
                chargeSeen, chargeTick, preChargeDist, postChargeDist, escapedBeyond15, ticksToEscape,
                hpAtCharge, hpMin == Float.MAX_VALUE ? -1 : hpMin, damage);
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    /** (2b) the reflex is free to run. */
    public static class Escape extends BotWardenChargeTest {
        @Override
        public String name() {
            return "bot_warden_charge";
        }

        @Override
        protected boolean allowEscape() {
            return true;
        }
    }

    /** (2c) control — the bot is pinned, so the same boom lands. */
    public static class None extends BotWardenChargeTest {
        @Override
        public String name() {
            return "bot_warden_charge_none";
        }

        @Override
        protected boolean allowEscape() {
            return false;
        }
    }
}
