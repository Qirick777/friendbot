package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.aicompanion.test.TestUser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * T4.5 [검증] (a): the user is at ≤15% health with no throwable potion and a threat nearby → the bot
 * must mount the user and flee. PASS iff (1) {@code user.getVehicle()==bot} AND the bot's passenger
 * list contains the user (a real ride, not a flag) AND (2) once the bot sprints away, the user's
 * position moves WITH the bot (a real ride carries them — a relationship that never moves is only
 * half the mechanic). The escape_none control proves this only triggers at ≤15%.
 */
public class BotEscapeRideTest implements BotTest {

    private Zombie enemy;
    private ServerPlayer user;
    private boolean vehicleWasBot;
    private boolean passengerHeld;
    private double userXAtMount = Double.NaN;
    private double maxUserDisplacement;

    @Override
    public String name() {
        return "bot_escape_ride";
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
        ctx.level.setDayTime(18000L);
        for (int dx = -40; dx <= 8; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 3; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        user = TestUser.spawn(ctx.server, ctx.level, o);
        user.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        user.setHealth(2.0F);          // 10% ≤ 15% (치명선)
        user.setInvulnerable(true);    // keep it pinned in the critical band

        bot.survival().reset();
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() - 1.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.getInventory().clearContent(); // NO throwable potion → escape (not heal)
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        // A threat to the east → the bot flees west (platform extends west).
        enemy = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + 5, o.getY(), o.getZ()));
        if (enemy != null) {
            enemy.setNoAi(true);
        }
        vehicleWasBot = false;
        passengerHeld = false;
        maxUserDisplacement = 0;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot != null && user != null) {
            if (user.getVehicle() == bot) {
                vehicleWasBot = true;
                if (bot.getPassengers().contains(user)) {
                    passengerHeld = true;
                }
                if (Double.isNaN(userXAtMount)) {
                    userXAtMount = user.getX();
                }
                maxUserDisplacement = Math.max(maxUserDisplacement, Math.abs(user.getX() - userXAtMount));
            }
        }
        return ctx.elapsedTicks >= 140;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        boolean rode = vehicleWasBot && passengerHeld;
        boolean movedWithBot = maxUserDisplacement > 3.0;
        boolean ok = rode && movedWithBot;
        String measured = String.format("vehicle==bot:%b,passengerHeld:%b,userMoved:%.2f",
                vehicleWasBot, passengerHeld, maxUserDisplacement);
        String expected = "user.getVehicle()==bot AND passenger held AND user carried >3 blocks with bot";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
