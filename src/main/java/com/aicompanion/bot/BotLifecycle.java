package com.aicompanion.bot;

import com.aicompanion.AICompanionMod;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Bot lifecycle hooks on the Forge event bus:
 * <ul>
 *   <li>{@link ServerStartedEvent} → restore the bot on world load (design "복원").</li>
 *   <li>{@link LivingDeathEvent} → on bot death, drop the full inventory at the death spot,
 *       remove the entity, and release the existence record (design "사망").</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AICompanionMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BotLifecycle {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BotLifecycle() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        BotManager.restore(event.getServer());
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof AICompanionBot bot)) {
            return;
        }
        MinecraftServer server = bot.getServer();
        if (server == null) {
            return;
        }

        // Drop the entire inventory at the death spot (and clear it so vanilla death
        // handling does not double-drop), then release the record and remove the entity.
        LOGGER.info("[BOT] death at {} — dropping inventory", bot.blockPosition());
        bot.getInventory().dropAll();

        // Defer entity removal to the next tick so we do not remove mid-death-processing.
        server.execute(() -> {
            if (BotManager.current() == bot) {
                server.getPlayerList().remove(bot);
            } else if (!bot.isRemoved()) {
                bot.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
            }
            BotManager.onDeathRelease(server);
        });
    }
}
