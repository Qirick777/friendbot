package com.aicompanion.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The AI companion bot: a {@link ServerPlayer} we drive ourselves.
 *
 * <p>By subclassing {@code ServerPlayer} we inherit inventory, hunger, item-use
 * animations, combat resolution, and player rendering. This task (T1.1) establishes
 * only the tick plumbing — the AI layers (perception → reflex → decision → action,
 * sketch A.2) arrive in later phases. For now the bot simply lives: every tick runs
 * the vanilla player tick via {@code super.tick()}.</p>
 */
public class AICompanionBot extends ServerPlayer {

    public AICompanionBot(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
    }

    @Override
    public void tick() {
        // Phase 3+ inserts: perception.gather → reflex → decision → action here.
        super.tick(); // vanilla player tick: hunger, regen, item-use progress, effects.
    }
}
