package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.aicompanion.test.TestUser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * T4.4 (b) FUSE-LOW branch: a creeper is ignited far away (out of the bot's awareness) and only
 * brought beside the user once its fuse is nearly spent. The bot must then NOT waste time placing a
 * wall (too late) — it must shield + step away instead. PASS iff NO wall was placed AND the bot took
 * a defensive action (raised its shield OR moved away from the creeper). Even though the bot HOLDS
 * blocks, the short fuse must route it to the escape branch.
 */
public class BotCreeperLowFuseTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TELEPORT_TICK = 25; // by now swell≈25 → remaining≈5 ticks (<PLACE_MIN 6)

    private Creeper creeper;
    private ServerPlayer user;
    private Vec3 botStart;
    private boolean wallPlacedSeen;
    private boolean blockingSeen;
    private double maxCreeperDist;
    private double distAtTeleport = -1;

    @Override
    public int repeats() {
        return 3;
    }

    @Override
    public double successThreshold() {
        return 1.00;
    }

    @Override
    public String name() {
        return "bot_creeper_lowfuse";
    }

    @Override
    public int timeoutTicks() {
        return 80;
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return new int[]{-6, 40, -4, 4};
    }

    /** P-6 (유형 #9): 이 판정이 측정된 세계의 전제. */
    @Override
    public String scenarioSpec() {
        return "bot.setInvulnerable(true). 스폰 몹: creeper. 관측 80틱, 트라이얼 3회. 시공 범위 선언: {-6, 40, -4, 4}. "
                + "TestUser 있음 → 16장 자율 이동이 마지막 else에서 돌 수 있다. idleCommandedTicks로 값 확인. ";
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);
        for (int dx = -6; dx <= 40; dx++) {
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
        user.setInvulnerable(true);
        user.setHealth(user.getMaxHealth());

        bot.survival().reset();
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() - 2.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.getInventory().clearContent();
        bot.getInventory().add(new ItemStack(Items.COBBLESTONE, 16)); // HAS blocks — short fuse must still skip placing
        bot.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();
        botStart = bot.position();

        // Ignited FAR (>detect range 12) so the bot ignores it while the fuse burns down.
        creeper = ctx.env.spawn(EntityType.CREEPER, new BlockPos(o.getX() + 34, o.getY(), o.getZ()));
        if (creeper != null) {
            creeper.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0);
            creeper.ignite();
        }
        wallPlacedSeen = false;
        blockingSeen = false;
        maxCreeperDist = 0;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || creeper == null) {
            return true;
        }
        // Bring the (nearly-spent) creeper beside the user once its fuse is short.
        if (ctx.elapsedTicks == TELEPORT_TICK) {
            creeper.moveTo(ctx.origin.getX() + 2.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5, 0.0F, 0.0F);
            distAtTeleport = bot.position().distanceTo(creeper.position());
            LOGGER.info("[ENV] lowfuse: teleported creeper in at tick {} swelling={}",
                    TELEPORT_TICK, creeper.getSwelling(1.0F));
        }
        if (ctx.elapsedTicks >= TELEPORT_TICK) {
            if (bot.environment().lastWallPos() != null) {
                wallPlacedSeen = true;
            }
            if (bot.isBlocking()) {
                blockingSeen = true;
            }
            double d = bot.position().distanceTo(creeper.position());
            maxCreeperDist = Math.max(maxCreeperDist, d);
        }
        // End a few ticks after the creeper arrives (before/around its explosion).
        return ctx.elapsedTicks >= TELEPORT_TICK + 8 || !creeper.isAlive();
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        boolean movedAway = distAtTeleport > 0 && maxCreeperDist > distAtTeleport + 0.3;
        boolean defensive = blockingSeen || movedAway;
        boolean ok = !wallPlacedSeen && defensive;
        String measured = String.format("wallPlaced:%b,isBlocking:%b,creeperDist:%.2f->%.2f",
                wallPlacedSeen, blockingSeen, distAtTeleport, maxCreeperDist);
        String expected = "NO wall placed (fuse<PLACE_MIN despite blocks) AND defensive (shield or moved away)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
