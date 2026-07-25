package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.combat.Layer2Registry;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Band ACTIVE-CORRECTION gate (audit item 3b). The original {@code bot_warden_band} started the bot
 * already inside the band with a motionless warden, so "min == max == 18.00" also passes if nothing
 * moves at all. Here the bot starts OUTSIDE the band and must correct back into it under rule 3
 * alone — no charge, so the charge-escape reflex cannot be the cause.
 *
 * <ul>
 *   <li>{@code bot_warden_band_below} — start at 10 (inside the sonic window): must retreat to ≥16.</li>
 *   <li>{@code bot_warden_band_above} — start at 30: must close to ≤20. This is the first test of
 *       the band's UPPER bound at all.</li>
 * </ul>
 */
public abstract class BotWardenBandReturnTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double BAND_MIN = 16.0;
    private static final double BAND_MAX = 20.0;
    private static final double TOL = 1.5;

    private Warden warden;
    private double startDist;
    private double finalDist;
    private double bestDist = -1;
    private boolean reachedBand;
    private boolean chargeSeen;

    /** Starting bot→warden distance (outside the band). */
    protected abstract int startDistance();

    protected abstract boolean fromBelow();

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
        Layer2Registry.clearAllOverrides();

        for (int dx = -50; dx <= 50; dx++) {
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
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40.0);
        bot.setHealth(40.0F);
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        bot.getInventory().add(new ItemStack(Items.ARROW, 64));
        bot.meleeCombat().stop();

        // The warden is immobile and brain-less here on purpose: this harness isolates the BOT's
        // band correction. (Whether the band is holdable against a MOVING warden is a separate
        // question — the audit speed probe shows a warden's top speed ≈ the bot's sprint.)
        warden = ctx.env.spawn(EntityType.WARDEN, new BlockPos(o.getX() + startDistance(), o.getY(), o.getZ()));
        if (warden != null) {
            warden.setInvulnerable(true);
            warden.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0);
            warden.setNoAi(true);
        }
        bot.rangedCombat().setTarget(warden);
        startDist = Math.hypot(bot.getX() - (o.getX() + startDistance() + 0.5), bot.getZ() - (o.getZ() + 0.5));
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || warden == null) {
            return true;
        }
        if (bot.perception().targets.stream()
                .anyMatch(t -> t.entity == warden && t.layer2Profile.isCharging(warden))) {
            chargeSeen = true;
        }
        double d = Math.hypot(bot.getX() - warden.getX(), bot.getZ() - warden.getZ());
        finalDist = d;
        if (fromBelow()) {
            bestDist = Math.max(bestDist, d); // furthest reached
            if (d >= BAND_MIN - 0.01) {
                reachedBand = true;
            }
        } else {
            bestDist = bestDist < 0 ? d : Math.min(bestDist, d); // closest reached
            if (d <= BAND_MAX + 0.01) {
                reachedBand = true;
            }
        }
        if (ctx.elapsedTicks % 60 == 0) {
            LOGGER.info("[WARDEN] bandreturn t={} dist={} zza={} reached={}",
                    ctx.elapsedTicks, String.format("%.2f", d), bot.zza, reachedBand);
        }
        return ctx.elapsedTicks >= 360 || reachedBand;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        boolean inBandNow = finalDist >= BAND_MIN - TOL && finalDist <= BAND_MAX + TOL;
        boolean ok = reachedBand && !chargeSeen;
        String dir = fromBelow() ? "below→retreat to >=16" : "above→approach to <=20";
        LOGGER.info("[WARDEN] bandreturn RESULT {} start={} best={} final={} reached={} charge={}",
                dir, String.format("%.2f", startDist), String.format("%.2f", bestDist),
                String.format("%.2f", finalDist), reachedBand, chargeSeen);
        String measured = String.format("dir:%s,dist:%.2f->%.2f(best %.2f),inBandNow:%b,chargeSeen:%b",
                dir, startDist, finalDist, bestDist, inBandNow, chargeSeen);
        String expected = "rule-3 band correction alone (no charge) brings the bot back into 16~20";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    /** Start too close → must back off to the band's lower bound. */
    public static class Below extends BotWardenBandReturnTest {
        @Override
        public String name() {
            return "bot_warden_band_below";
        }

        @Override
        protected int startDistance() {
            return 10;
        }

        @Override
        protected boolean fromBelow() {
            return true;
        }
    }

    /** Start too far → must close to the band's upper bound (never tested before). */
    public static class Above extends BotWardenBandReturnTest {
        @Override
        public String name() {
            return "bot_warden_band_above";
        }

        @Override
        protected int startDistance() {
            return 30;
        }

        @Override
        protected boolean fromBelow() {
            return false;
        }
    }
}
