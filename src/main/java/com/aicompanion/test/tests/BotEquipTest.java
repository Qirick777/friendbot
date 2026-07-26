package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * T5.2 [검증] verbatim: 「봇에 철갑옷·다이아갑옷 동시 지급 → 봇이 다이아 착용(더 높은 방어도)하는지.
 * 나무검·다이아검 지급 → 근접 시 다이아검 사용하는지. <b>판정: 착용 갑옷==다이아 AND 메인핸드==다이아검.</b>」
 *
 * <p>Both tiers are handed over together and in the WORSE-first order, so passing cannot come from
 * "equipped whatever arrived last". The manager must choose by score.</p>
 */
public class BotEquipTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int RUN = 40;

    private String head = "?";
    private String chest = "?";
    private String legs = "?";
    private String feet = "?";
    private String main = "?";
    private int blocks;
    private int arrows;

    @Override
    public int[] arenaBounds() {
        return new int[]{-12, 12, -12, 12};
    }

    @Override
    public String name() {
        return "bot_equip";
    }

    @Override
    public int timeoutTicks() {
        return RUN + 40;
    }

    /** P-6 (유형 #9): 이 판정이 측정된 세계의 전제. */
    @Override
    public String scenarioSpec() {
        return "bot.setInvulnerable(true). 관측 창과 트라이얼 수는 리터럴이 아니라 상수 계산식이다(timeoutTicks()/repeats() 참조). 유저 없음(TestUser.spawn 미호출) → "
                + "Perception.java:131이 봇 외 플레이어를 찾지 못해 user==null → BotIdle.java:87-89 즉시 반환. 16장 자율 이동 비활성. ";
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 3; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
        bot.survival().reset();
        bot.getInventory().clearContent();
        for (EquipmentSlot s : EquipmentSlot.values()) {
            bot.setItemSlot(s, ItemStack.EMPTY);
        }
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        // Worse tier first, better tier second — and both present at once.
        bot.getInventory().add(new ItemStack(Items.IRON_HELMET));
        bot.getInventory().add(new ItemStack(Items.IRON_CHESTPLATE));
        bot.getInventory().add(new ItemStack(Items.IRON_LEGGINGS));
        bot.getInventory().add(new ItemStack(Items.IRON_BOOTS));
        bot.getInventory().add(new ItemStack(Items.WOODEN_SWORD));
        bot.getInventory().add(new ItemStack(Items.DIAMOND_HELMET));
        bot.getInventory().add(new ItemStack(Items.DIAMOND_CHESTPLATE));
        bot.getInventory().add(new ItemStack(Items.DIAMOND_LEGGINGS));
        bot.getInventory().add(new ItemStack(Items.DIAMOND_BOOTS));
        bot.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
        bot.getInventory().add(new ItemStack(Items.COBBLESTONE, 32));
        bot.getInventory().add(new ItemStack(Items.ARROW, 12));
        bot.equipment().invalidate();
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            return true;
        }
        bot.setDeltaMovement(Vec3.ZERO);
        head = bot.getItemBySlot(EquipmentSlot.HEAD).getItem().toString();
        chest = bot.getItemBySlot(EquipmentSlot.CHEST).getItem().toString();
        legs = bot.getItemBySlot(EquipmentSlot.LEGS).getItem().toString();
        feet = bot.getItemBySlot(EquipmentSlot.FEET).getItem().toString();
        main = bot.getMainHandItem().getItem().toString();
        blocks = bot.equipment().blockStock();
        arrows = bot.equipment().arrowStock();
        return ctx.elapsedTicks >= RUN;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        boolean armourOk = head.contains("diamond") && chest.contains("diamond")
                && legs.contains("diamond") && feet.contains("diamond");
        boolean weaponOk = main.contains("diamond_sword");
        boolean ok = armourOk && weaponOk;

        LOGGER.info("[EQUIP] RESULT head={} chest={} legs={} feet={} main={} blocks={} arrows={}",
                head, chest, legs, feet, main, blocks, arrows);
        String measured = String.format(
                "head:%s,chest:%s,legs:%s,feet:%s,mainHand:%s,blockStock:%d,arrowStock:%d",
                head, chest, legs, feet, main, blocks, arrows);
        String expected = "worn armour == diamond (all 4 slots) AND main hand == diamond_sword, "
                + "chosen by score with the iron/wooden alternatives simultaneously in inventory";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
