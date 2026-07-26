package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * T4.4 [검증] (a) CONTROL: the same fall but with NO water bucket. The bot must take fall damage
 * (health drops) — proving that in the water test it was the water, not something else, that
 * negated the damage. PASS = damage actually occurred (and the bot survived to measure it).
 */
public class BotFallNoWaterTest implements BotTest {

    private static final int FALL_HEIGHT = 12;

    private float startHp;
    private float minHp;

    @Override
    public String name() {
        return "bot_fall_nowater";
    }

    @Override
    public int timeoutTicks() {
        return 160;
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return new int[]{-3, 3, -3, 3};
    }

    /** P-6 (유형 #9): 이 판정이 측정된 세계의 전제. */
    @Override
    public String scenarioSpec() {
        return "setBaseValue(MAX_HEALTH) 호출값: 20.0 (봇/표적 구분은 setup() 참조). bot.setInvulnerable(false). 관측 "
                + "160틱, 트라이얼 1회. 시공 범위 선언: {-3, 3, -3, 3}. 유저 없음(TestUser.spawn 미호출) → Perception.java:131이 "
                + "봇 외 플레이어를 찾지 못해 user==null → BotIdle.java:87-89 즉시 반환. 16장 자율 이동 비활성. ";
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
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
        bot.getInventory().clearContent(); // NO water bucket
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        bot.setHealth(20.0F);
        bot.setInvulnerable(false);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY() + FALL_HEIGHT, o.getZ() + 0.5, 0.0F, 0.0F);
        bot.fallDistance = 0.0F;

        startHp = bot.getHealth();
        minHp = startHp;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot != null) {
            minHp = Math.min(minHp, bot.getHealth());
        }
        return ctx.elapsedTicks >= 120;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        double damage = startHp - minHp;
        boolean tookDamage = damage > 0.5; // control MUST take fall damage without water
        String measured = String.format("hp:%.1f->%.1f(min),damage:%.1f", startHp, minHp, damage);
        String expected = "damage>0 (no water → fall damage occurs; proves water was the cause in bot_fall_water)";
        return tookDamage ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
