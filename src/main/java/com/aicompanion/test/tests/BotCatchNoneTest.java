package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.combat.BotRescue;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.aicompanion.test.TestUser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * T4.5 [검증] (b) CONTROL: the same driven fall but the bot is far away (cannot catch). The user
 * must land and take REAL fall damage — proving that in bot_catch_fall it was the bot's catch that
 * prevented the damage (same contrast pattern as T4.4 bot_fall_nowater).
 *
 * <p><b>E4 재판정.</b> Until 13.2's C4/C5 rungs existed, this control held for a reason that was not
 * stated: the bot outside {@code CATCH_XZ} simply had no catch path at all, so ANY distance was
 * "unreachable". C4 (마중 나가 받기) and C5 (물/블록 폴백) changed that — a bot 28 blocks away with a
 * bucket now DOES intervene. The control condition therefore has to be named rather than inherited:
 * the distance must exceed {@link BotRescue#MEET_RANGE} <em>and</em> the bot must hold no water, so
 * that all three rungs (C2/C3, C4, C5) are refused by their own stated conditions. The premise is
 * asserted in {@link #judge}: if it ever stops holding, this test FAILs as an invalid control
 * instead of silently passing for the wrong reason.</p>
 */
public class BotCatchNoneTest implements BotTest {

    private static final int DROP = 12;

    private ServerPlayer user;
    private float hp0;
    private float minHp;
    private boolean everMounted;
    private double botUserHoriz;   // control-condition distance (must be unreachable, see judge log)
    private float maxFallDistance; // peak accumulated fallDistance, for the damage-formula check
    private boolean botHasWater;   // C5 premise: no bucket, so the water fallback is refused too
    private boolean waterLaid;     // observed: did C5 fire anyway?

    @Override
    public String scenarioSpec() {
        return String.format(
                "driven %d-block fall, user vulnerable; bot 28 blocks away horizontally AND holding "
                + "no water — so C2/C3 (CATCH_XZ 2.5), C4 (MEET_RANGE %.0f) and C5 (needs a bucket) "
                + "are each refused by their own condition, not by a broken path",
                DROP, com.aicompanion.bot.combat.BotRescue.MEET_RANGE);
    }

    @Override
    public String name() {
        return "bot_catch_none";
    }

    @Override
    public int timeoutTicks() {
        return 200;
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return new int[]{-4, 30, -4, 4};
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);
        for (int dx = -4; dx <= 30; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= DROP + 4; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        // Bot far away (out of catch range and out of approach time).
        bot.survival().reset();
        bot.setInvulnerable(true);
        bot.getInventory().clearContent();
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 28.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        user = TestUser.spawn(ctx.server, ctx.level, o);
        user.stopRiding();
        user.setInvulnerable(false);
        user.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        user.setHealth(20.0F);
        user.setDeltaMovement(Vec3.ZERO);
        user.fallDistance = 0.0F;
        user.moveTo(o.getX() + 0.5, o.getY() + DROP, o.getZ() + 0.5, 0.0F, 0.0F);

        hp0 = user.getHealth();
        minHp = hp0;
        everMounted = false;
        maxFallDistance = 0.0F;
        waterLaid = false;
        // Record the control condition: the bot must be far enough that NO catch path could reach —
        // so this control never depends on a catch path being broken. Post-C4/C5 that means BOTH
        // horiz > MEET_RANGE (C4 refused) and no water in the inventory (C5 refused).
        botUserHoriz = Math.hypot(bot.getX() - user.getX(), bot.getZ() - user.getZ());
        botHasWater = false;
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            if (bot.getInventory().getItem(i).is(Items.WATER_BUCKET)) {
                botHasWater = true;
            }
        }
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        if (user == null) {
            return true;
        }
        if (user.getVehicle() == null) {
            double px = user.getX();
            double py = user.getY();
            double pz = user.getZ();
            user.doTick();
            // fallDistance is read BEFORE doCheckFallDamage consumes/resets it on landing, so the
            // peak here is exactly the value vanilla feeds into calculateFallDamage.
            maxFallDistance = Math.max(maxFallDistance, user.fallDistance);
            user.doCheckFallDamage(user.getX() - px, user.getY() - py, user.getZ() - pz, user.onGround());
        } else {
            everMounted = true;
        }
        AICompanionBot bot = BotManager.current();
        if (bot != null && bot.rescue().waterFallbackPos() != null) {
            waterLaid = true;
        }
        minHp = Math.min(minHp, user.getHealth());
        return ctx.elapsedTicks >= 120;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        double damage = hp0 - minHp;
        // E4: the control is only a control while every rung of 13.2 is refused by its own stated
        // condition. Assert the premise instead of assuming it.
        boolean premiseOk = botUserHoriz > BotRescue.MEET_RANGE && !botHasWater;
        boolean ok = premiseOk && !everMounted && !waterLaid && damage > 0.5; // uncaught fall MUST hurt
        // Vanilla: calculateFallDamage = ceil(fallDistance - 3). Logging the peak fallDistance
        // pins whether an unexpected damage value comes from spawn/landing geometry (fall shorter
        // than the nominal drop) or from fallDistance under-accumulating (the fake-player hazard).
        double expectedDamage = Math.ceil(maxFallDistance - 3.0F);
        String measured = String.format(
                "mounted:%b,waterLaid:%b,hp:%.1f->%.1f,damage:%.1f,fallDistance:%.2f,formulaDmg:%.0f,"
                        + "botUserHoriz:%.1f,meetRange:%.1f,botHasWater:%b,premiseOk:%b",
                everMounted, waterLaid, hp0, minHp, damage, maxFallDistance, expectedDamage,
                botUserHoriz, BotRescue.MEET_RANGE, botHasWater, premiseOk);
        String expected = "control premise: botUserHoriz>MEET_RANGE(24) AND no water → C2/C3, C4 and "
                + "C5 are each refused by their own condition → fall damage>0 "
                + "(proves the catch was the cause in bot_catch_fall)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
