package com.aicompanion.bot;

import com.aicompanion.AICompanionMod;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Item registration. Holds the consumable bot spawn egg (design 3.3 "소환").
 */
@Mod.EventBusSubscriber(modid = AICompanionMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, AICompanionMod.MOD_ID);

    public static final RegistryObject<Item> BOT_SPAWN_EGG = ITEMS.register(
            "bot_spawn_egg",
            () -> new BotSpawnEggItem(new Item.Properties().stacksTo(16)));

    private ModItems() {
    }

    /** Expose the egg in a creative tab so it is obtainable in dev. */
    @SubscribeEvent
    public static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS
                || event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(BOT_SPAWN_EGG);
        }
    }
}
