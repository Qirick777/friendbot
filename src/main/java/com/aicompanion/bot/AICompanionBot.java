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

    private final BotMovementController mover = new BotMovementController();

    public AICompanionBot(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
    }

    /** Tactical movement executor (T2.1). */
    public BotMovementController mover() {
        return mover;
    }

    @Override
    public void tick() {
        // Phase 3+ inserts: perception.gather → reflex → decision here.
        // Action layer: set movement inputs before the physics tick consumes them.
        mover.tick(this);

        // ServerPlayer.tick() does only housekeeping; the movement/LivingEntity tick lives in
        // doTick() (normally driven by the network connection). The bot has no connection ticking
        // it, so we drive BOTH here = a full player tick: housekeeping + aiStep/travel physics
        // (gravity, collision, step-up, friction, hunger, regen).
        super.tick();  // ServerPlayer housekeeping (gameMode, containers, criteria)
        this.doTick(); // Player/LivingEntity tick → aiStep → travel
    }
}
