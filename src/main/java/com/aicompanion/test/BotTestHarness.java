package com.aicompanion.test;

import com.aicompanion.AICompanionMod;
import com.aicompanion.bot.BotCommand;
import com.mojang.logging.LogUtils;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Wires the harness onto the Forge event bus:
 * <ul>
 *   <li>{@link RegisterCommandsEvent} → register {@code /bottest}.</li>
 *   <li>{@link TickEvent.ServerTickEvent} (END) → drive the active/queued test.</li>
 *   <li>{@link ServerStartedEvent} → headless auto-run from {@code -Dbottest.auto=<names>}.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AICompanionMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BotTestHarness {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BotTestHarness() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        BotTestCommand.register(event.getDispatcher());
        BotCommand.register(event.getDispatcher());
        LOGGER.info("[BOTTEST] /bottest and /bot commands registered");
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            BotTestManager.INSTANCE.serverTick(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        String auto = System.getProperty("bottest.auto");
        if (auto == null || auto.isBlank()) {
            return;
        }
        for (String raw : auto.split(",")) {
            String name = raw.trim();
            if (!name.isEmpty()) {
                BotTestManager.INSTANCE.enqueue(name);
                LOGGER.info("[BOTTEST] auto-run queued '{}'", name);
            }
        }
    }
}
