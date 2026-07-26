package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.BotWorldData;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * T1.2 subtest 2 (death): put a uniquely-named marker in the bot's inventory, then kill it.
 * PASS iff the marker item is found as a dropped {@link ItemEntity} near the death spot AND
 * the existence record was released ({@code botExists == false}).
 */
public class BotDeathTest implements BotTest {

    private static final String MARKER = "BOTTEST_DEATH_MARKER";

    private BlockPos deathPos;

    @Override
    public String name() {
        return "bot_death";
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return NO_BUILD;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        // Unique marker item into the bot inventory.
        ItemStack marker = new ItemStack(Items.DIAMOND, 1);
        marker.setHoverName(Component.literal(MARKER));
        bot.getInventory().add(marker);

        deathPos = bot.blockPosition();
        // Kill: genericKill bypasses armor/invulnerability. Triggers LivingDeathEvent →
        // BotLifecycle drops the inventory + releases the record.
        bot.hurt(bot.damageSources().genericKill(), Float.MAX_VALUE);
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        return ctx.elapsedTicks >= 20; // allow deferred removal + item settling
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        AABB box = new AABB(deathPos).inflate(8.0);
        List<ItemEntity> drops = ctx.level.getEntitiesOfClass(ItemEntity.class, box);
        boolean markerDropped = drops.stream().anyMatch(
                ie -> ie.getItem().getHoverName().getString().contains(MARKER));

        boolean botExists = BotWorldData.get(ctx.server).botExists();

        boolean ok = markerDropped && !botExists;
        String measured = "markerDropped:" + markerDropped + ",botExists:" + botExists
                + ",drops:" + drops.size();
        String expected = "markerDropped==true AND botExists==false";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
