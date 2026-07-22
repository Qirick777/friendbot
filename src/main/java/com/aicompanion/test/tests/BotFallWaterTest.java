package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * T4.4 [검증] (a): the bot falls onto a floor with a water bucket. The R2 reflex must drop water in
 * the fall path so the bot lands in water and takes ZERO fall damage. PASS iff (1) the bot's health
 * is unchanged AND (2) real water actually exists at the deploy spot (getFluidState==WATER) AND
 * (3) the bot was observed in water — proving the vanilla water-negation path actually ran on the
 * fake player, not just that a "place water" function was called. The nowater control test proves
 * the water is what prevented the damage.
 */
public class BotFallWaterTest implements BotTest {

    private static final int FALL_HEIGHT = 12;

    private float startHp;
    private float minHp;
    private boolean inWaterSeen;
    private boolean waterConfirmed;

    @Override
    public String name() {
        return "bot_fall_water";
    }

    @Override
    public int timeoutTicks() {
        return 160;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;

        // Stone floor at o.y-1; clear a tall air column above for the fall.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= FALL_HEIGHT + 4; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        bot.survival().reset();
        bot.getInventory().clearContent();
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        bot.setHealth(20.0F);
        bot.setInvulnerable(false); // real fall damage must be possible
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY() + FALL_HEIGHT, o.getZ() + 0.5, 0.0F, 0.0F);
        bot.fallDistance = 0.0F;
        bot.getInventory().add(new ItemStack(Items.WATER_BUCKET));

        startHp = bot.getHealth();
        minHp = startHp;
        inWaterSeen = false;
        waterConfirmed = false;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot != null) {
            minHp = Math.min(minHp, bot.getHealth());
            if (bot.isInWater()) {
                inWaterSeen = true;
            }
            BlockPos wp = bot.environment().lastWaterPos();
            if (wp != null && ctx.level.getFluidState(wp).is(FluidTags.WATER)) {
                waterConfirmed = true;
            }
        }
        return ctx.elapsedTicks >= 140;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        boolean unharmed = minHp >= startHp - 0.01;
        boolean ok = unharmed && waterConfirmed && inWaterSeen;
        String measured = String.format("hp:%.1f->%.1f(min),waterPlaced:%b,inWater:%b",
                startHp, minHp, waterConfirmed, inWaterSeen);
        String expected = "hp unchanged AND water block present AND bot entered water (R2 negated fall)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
