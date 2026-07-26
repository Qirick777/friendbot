package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.combat.CombatRules;
import com.aicompanion.bot.combat.CombatStats;
import com.aicompanion.bot.combat.TacticalDecision;
import com.aicompanion.bot.perception.TargetInfo;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * T3.2 tactical-engine check: a zombie (weak) and an iron golem (100 hp, knockback-immune)
 * are measured, then run through the rule engine. PASS iff the win/loss gate allows melee on
 * the zombie but forces ranged on the golem — from measured stats alone, no mob-name logic.
 */
public class BotTacticsTest implements BotTest {

    private LivingEntity zombie;
    private LivingEntity golem;

    @Override
    public String name() {
        return "bot_tactics";
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return new int[]{-4, 8, -4, 4};
    }

    /** P-6 (유형 #9): 이 판정이 측정된 세계의 전제. */
    @Override
    public String scenarioSpec() {
        return "스폰 몹: iron_golem, zombie. 관측 200틱, 트라이얼 1회. 시공 범위 선언: {-4, 8, -4, 4}. 유저 없음(TestUser.spawn "
                + "미호출) → Perception.java:131이 봇 외 플레이어를 찾지 못해 user==null → BotIdle.java:87-89 즉시 반환. 16장 자율 "
                + "이동 비활성. ";
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        for (int dx = -4; dx <= 8; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 2; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);
        // Give a real weapon so the bot's DPS is meaningful (attribute folds in the sword).
        bot.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));

        zombie = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + 3, o.getY(), o.getZ() + 2));
        golem = ctx.env.spawn(EntityType.IRON_GOLEM, new BlockPos(o.getX() + 3, o.getY(), o.getZ() - 2));
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        // Let equipment attribute modifiers + perception settle.
        return ctx.elapsedTicks >= 10;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            return BotTestResult.fail("bot:null", "zombie melee, golem ranged");
        }
        TargetInfo zi = find(bot, zombie);
        TargetInfo gi = find(bot, golem);
        if (zi == null || gi == null) {
            return BotTestResult.fail("zombieInfo:" + (zi != null) + ",golemInfo:" + (gi != null),
                    "both targets perceived");
        }

        CombatStats me = CombatStats.of(bot);
        TacticalDecision zd = CombatRules.evaluate(zi, me);
        TacticalDecision gd = CombatRules.evaluate(gi, me);

        boolean ok = zd.allowMelee && !gd.allowMelee;
        String measured = String.format(
                "botDps:%.1f | zombie{hp:%.0f,dps:%.1f,allowMelee:%b} | golem{hp:%.0f,dps:%.1f,kbImmune:%b,allowMelee:%b,mode:%s,keepDist:%b}",
                me.dps,
                zi.maxHealth, zi.dpsEstimate(), zd.allowMelee,
                gi.maxHealth, gi.dpsEstimate(), CombatRules.keepDistance(gi), gd.allowMelee, gd.mode, gd.keepDistance);
        String expected = "zombie allowMelee==true AND golem allowMelee==false";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    private static TargetInfo find(AICompanionBot bot, LivingEntity e) {
        for (TargetInfo t : bot.perception().targets) {
            if (t.entity == e) {
                return t;
            }
        }
        return null;
    }
}
