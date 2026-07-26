package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * T4.1 survival check (PEARL_ESCAPE path): the bot is below the danger line but with enough absolute
 * HP to survive the pearl's 5-damage landing, and holds ender pearls. PASS iff the bot actually
 * TELEPORTS away — its position jumps by more than the teleport threshold AND the survival machine
 * confirms the mechanism. This exercises the {@code connection.isAcceptingMessages()} gate for a
 * connection-less fake player, with a {@code teleportTo} fallback if the vanilla path is blocked.
 */
public class BotPearlTest implements BotTest {

    private static final int ENEMY_DX = 3; // enemy east; the bot pearls west (away)

    private Zombie enemy;
    private Vec3 startPos;
    private double maxDisplacement;

    @Override
    public String name() {
        return "bot_pearl";
    }

    @Override
    public int timeoutTicks() {
        return 300;
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return new int[]{-45, ENEMY_DX + 3, -6, 6};
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);

        // Wide floor extending far WEST (pearl travel direction) + a back wall so a long throw still
        // lands on solid ground and triggers the teleport.
        for (int dx = -45; dx <= ENEMY_DX + 3; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 4; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (int dz = -6; dz <= 6; dz++) {
            for (int dy = 0; dy <= 5; dy++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() - 45, o.getY() + dy, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
            }
        }

        bot.survival().reset();
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.setInvulnerable(true);
        // maxHealth 40 so the danger line (30% = 12) sits ABOVE the pearl-safe floor (>8). health 10:
        // below danger (12) → escape, and above the self-damage precheck (>8) → pearl is chosen.
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40.0);
        bot.setHealth(10.0F);
        bot.getFoodData().setFoodLevel(6);
        bot.getInventory().add(new ItemStack(Items.ENDER_PEARL, 8));

        enemy = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + ENEMY_DX, o.getY(), o.getZ()));
        if (enemy != null) {
            enemy.setNoAi(true);
        }

        startPos = bot.position();
        maxDisplacement = 0;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot != null && startPos != null) {
            double disp = bot.position().distanceTo(startPos);
            if (disp > maxDisplacement) {
                maxDisplacement = disp;
            }
            // End early once teleport is confirmed (keeps the log tight).
            if (bot.survival().pearlTeleportConfirmed() && ctx.elapsedTicks > 5) {
                return true;
            }
        }
        return ctx.elapsedTicks >= 280;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        boolean confirmed = bot != null && bot.survival().pearlTeleportConfirmed();
        String mechanism = bot != null ? bot.survival().pearlMechanism() : "none";
        boolean moved = maxDisplacement > 4.0; // teleport threshold

        boolean ok = confirmed && moved;
        String measured = String.format("displacement:%.2f,teleportConfirmed:%b,mechanism:%s",
                maxDisplacement, confirmed, mechanism);
        String expected = "displacement>4.0 AND pearl teleport confirmed (vanilla or fallback)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
