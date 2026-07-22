package com.aicompanion;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * AI Companion Bot — mod entry point.
 *
 * <p>T0.1 scope: an empty mod that registers and prints a load line to the log,
 * proving the Forge 1.20.1 skeleton builds and loads.</p>
 */
@Mod(AICompanionMod.MOD_ID)
public class AICompanionMod {

    public static final String MOD_ID = "aicompanion";
    private static final Logger LOGGER = LogUtils.getLogger();

    public AICompanionMod() {
        // Emitted at construction so it shows up regardless of physical side.
        LOGGER.info("[BOTLOAD] {} mod loaded", MOD_ID);

        FMLJavaModLoadingContext.get().getModEventBus()
                .addListener(this::commonSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[BOTLOAD] {} common setup complete", MOD_ID);
    }
}
