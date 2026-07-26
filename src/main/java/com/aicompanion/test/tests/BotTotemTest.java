package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * T4.2 reflex R0 check: with a strong melee enemy adjacent and targeting the bot, the estimated
 * incoming damage is lethal, so the reflex must move a totem into the OFF-HAND. PASS iff the
 * off-hand slot goes from empty → totem_of_undying (a measured before→after slot change, not
 * "the reflex was called"). Also confirms the fake player's off-hand slot actually reflects
 * server-side (re-read in judge).
 */
public class BotTotemTest implements BotTest {

    private Zombie enemy;
    private String offhandBefore;

    @Override
    public String name() {
        return "bot_totem";
    }

    @Override
    public int timeoutTicks() {
        return 120;
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return new int[]{-3, 4, -3, 3};
    }

    /** P-6 (유형 #9): 이 판정이 측정된 세계의 전제. */
    @Override
    public String scenarioSpec() {
        return "setBaseValue(MAX_HEALTH) 호출값: 20.0, 100.0 (봇/표적 구분은 setup() 참조). "
                + "bot.setInvulnerable(true). 스폰 몹: zombie. 관측 120틱, 트라이얼 1회. 시공 범위 선언: {-3, 4, -3, 3}. 유저 "
                + "없음(TestUser.spawn 미호출) → Perception.java:131이 봇 외 플레이어를 찾지 못해 user==null → "
                + "BotIdle.java:87-89 즉시 반환. 16장 자율 이동 비활성. ";
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);

        for (int dx = -3; dx <= 4; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 2; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        bot.reflex(); // ensure the field exists
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.setInvulnerable(true);
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        bot.setHealth(8.0F); // 40% — ABOVE the survival danger line (30%), so only the reflex reacts
        // Off-hand starts empty; a totem sits in the bag for the reflex to pull.
        bot.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        bot.getInventory().add(new ItemStack(Items.TOTEM_OF_UNDYING, 1));
        offhandBefore = describe(bot.getOffhandItem());

        // Strong melee enemy right next to the bot, targeting it → high expectedDamage.
        enemy = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + 1, o.getY(), o.getZ()));
        if (enemy != null) {
            enemy.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(20.0); // lethal expected damage
            enemy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0);
            enemy.setHealth(100.0F);
            enemy.setNoAi(true); // won't actually swing (no HP confound); target set manually below
            enemy.setTarget(bot);
        }
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot != null && enemy != null) {
            enemy.setTarget(bot); // keep getTarget()==bot so perception counts its melee threat
        }
        return ctx.elapsedTicks >= 40;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        ItemStack off = bot != null ? bot.getOffhandItem() : ItemStack.EMPTY;
        boolean wasEmpty = "empty".equals(offhandBefore);
        boolean nowTotem = off.getItem() == Items.TOTEM_OF_UNDYING;
        float expDmg = bot != null ? bot.perception().expectedDamage : 0;

        boolean ok = wasEmpty && nowTotem;
        String measured = String.format("offhand:%s->%s,expDmg:%.1f,hp:%.1f",
                offhandBefore, describe(off), expDmg, bot != null ? bot.getHealth() : -1);
        String expected = "offhand empty->totem_of_undying (expDmg>=hp+margin)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    private static String describe(ItemStack s) {
        return s.isEmpty() ? "empty" : s.getItem().toString();
    }
}
