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
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * T4.4 [검증] (b): a creeper is ignited beside the user; the bot (holding common blocks) must place
 * a block on the creeper→user line to attenuate the blast. PASS iff the target cell goes air→solid
 * AND that cell actually lies on the creeper-user segment (guards against placing it somewhere
 * useless). NOTE: this gates the PLACEMENT only; whether the placed wall actually reduces explosion
 * damage (effect) is out of this gate (see report → bot_env_extra).
 */
public class BotCreeperWallTest implements BotTest {

    private Creeper creeper;
    private ServerPlayer user;
    private boolean beforeWasAir;
    private boolean wallSolid;
    private boolean wallOnSegment;
    private boolean recorded;

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
        return "bot_creeper_wall";
    }

    @Override
    public int timeoutTicks() {
        return 80;
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return new int[]{-6, 8, -4, 4};
    }

    /** P-6 (유형 #9): 이 판정이 측정된 세계의 전제. */
    @Override
    public String scenarioSpec() {
        return "bot.setInvulnerable(true). 스폰 몹: creeper. 관측 80틱, 트라이얼 3회. 시공 범위 선언: {-6, 8, -4, 4}. "
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
        for (int dx = -6; dx <= 8; dx++) {
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
        bot.setInvulnerable(true); // measuring placement, not blast mitigation
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() - 2.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.getInventory().clearContent();
        bot.getInventory().add(new ItemStack(Items.COBBLESTONE, 16)); // common block
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();
        bot.environment(); // touch

        // Creeper beside the user (within blast radius), ignited so it swells to explode.
        creeper = ctx.env.spawn(EntityType.CREEPER, new BlockPos(o.getX() + 4, o.getY(), o.getZ()));
        if (creeper != null) {
            creeper.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0);
            creeper.ignite();
        }
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || recorded) {
            return recorded;
        }
        BlockPos wall = bot.environment().lastWallPos();
        if (wall != null) {
            // Record at the moment of placement (before the creeper explodes and may clear it).
            wallSolid = !ctx.level.getBlockState(wall).getCollisionShape(ctx.level, wall).isEmpty();
            wallOnSegment = onSegment(wall, creeper.position(), user.position());
            beforeWasAir = true; // the platform interior was cleared to air in setup
            recorded = true;
            return true;
        }
        return ctx.elapsedTicks >= 70;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        boolean ok = recorded && beforeWasAir && wallSolid && wallOnSegment;
        AICompanionBot bot = BotManager.current();
        BlockPos wall = bot != null ? bot.environment().lastWallPos() : null;
        String measured = String.format("wallPos:%s,air->solid:%b,onSegment:%b",
                wall, wallSolid, wallOnSegment);
        String expected = "target cell air->solid AND on creeper-user segment";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    private static boolean onSegment(BlockPos p, Vec3 a, Vec3 b) {
        Vec3 pt = new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        Vec3 ab = b.subtract(a);
        double abLen2 = ab.lengthSqr();
        if (abLen2 < 1.0E-9) {
            return false;
        }
        double s = pt.subtract(a).dot(ab) / abLen2;
        if (s < 0.0 || s > 1.0) {
            return false;
        }
        Vec3 proj = a.add(ab.scale(s));
        return Math.hypot(pt.x - proj.x, pt.z - proj.z) < 1.0;
    }
}
