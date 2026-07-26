package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * T4.2 reflex R1 confirmation (shield raise): with a shield in the off-hand and an incoming arrow,
 * the reflex raises the shield. This is NOT one of the two design [검증] gates — it is the "the
 * shield actually went UP on the server" value check the fake-player path could plausibly break
 * (off-hand item-use). PASS iff {@code isBlocking()} becomes true (shield held ≥5 ticks). The full
 * attack/counter cycle (X5) and axe-disable handling (X6) are NOT verified here — recovered later
 * in a dedicated {@code bot_shield_cycle} harness integrated with melee.
 */
public class BotShieldTest implements BotTest {

    private boolean blockingSeen;
    private boolean usingSeen;

    @Override
    public String name() {
        return "bot_shield";
    }

    @Override
    public int timeoutTicks() {
        return 120;
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return new int[]{-3, 8, -3, 3};
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);

        for (int dx = -3; dx <= 8; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 2; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        bot.survival().reset();
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90.0F, 0.0F);
        bot.setInvulnerable(true); // only measuring isBlocking, not evasion
        bot.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        spawnIncomingArrow(ctx, bot); // seed one; more are added in tick() to keep 'incoming' populated
        blockingSeen = false;
        usingSeen = false;
    }

    private void spawnIncomingArrow(BotTestContext ctx, AICompanionBot bot) {
        BlockPos o = ctx.origin;
        double ax = o.getX() + 8.0, ay = o.getY() + 1.4, az = o.getZ() + 0.5;
        Arrow arrow = new Arrow(ctx.level, ax, ay, az);
        Vec3 toBot = new Vec3(bot.getX() - ax, (bot.getY() + 1.4) - ay, bot.getZ() - az)
                .normalize().scale(0.25); // slow → stays "incoming" for many ticks
        arrow.setDeltaMovement(toBot);
        ctx.level.addFreshEntity(arrow);
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot != null) {
            // Keep an incoming arrow present through the shield warmup (isBlocking needs ≥5 held ticks).
            if (ctx.elapsedTicks % 8 == 0 && ctx.elapsedTicks <= 40) {
                spawnIncomingArrow(ctx, bot);
            }
            if (bot.isUsingItem()) {
                usingSeen = true;
            }
            if (bot.isBlocking()) {
                blockingSeen = true;
            }
        }
        return ctx.elapsedTicks >= 60 || blockingSeen;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        boolean ok = blockingSeen;
        String measured = String.format("usingItemSeen:%b,isBlockingSeen:%b", usingSeen, blockingSeen);
        String expected = "isBlocking()==true (off-hand shield raised, held >=5 ticks)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
