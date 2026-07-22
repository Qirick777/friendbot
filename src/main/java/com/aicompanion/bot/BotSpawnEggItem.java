package com.aicompanion.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;

/**
 * Consumable spawn egg (design 3.3 "소환" / T1.2 L1): right-click summons the single bot
 * and consumes one egg. If a bot already exists, the summon is refused with a chat message
 * (L2 single constraint); the egg is not consumed.
 */
public class BotSpawnEggItem extends Item {

    public BotSpawnEggItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }

        ServerLevel serverLevel = (ServerLevel) level;
        BlockPos pos = player.blockPosition();
        boolean spawned = BotManager.eggSpawn(serverLevel, pos, stack);
        if (!spawned) {
            player.sendSystemMessage(Component.literal("[BOT] 봇이 이미 존재합니다. 소환할 수 없습니다."));
            return InteractionResultHolder.fail(stack);
        }
        player.sendSystemMessage(Component.literal("[BOT] 봇을 소환했습니다."));
        return InteractionResultHolder.consume(stack);
    }
}
