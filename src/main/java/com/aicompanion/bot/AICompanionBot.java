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
    private final BotLookController look = new BotLookController();
    private final BotPathPlanner planner = new BotPathPlanner();
    private final com.aicompanion.bot.perception.Perception perception =
            new com.aicompanion.bot.perception.Perception();

    public AICompanionBot(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
    }

    /** Perception layer (T3.1). */
    public com.aicompanion.bot.perception.Perception perception() {
        return perception;
    }

    /** Tactical movement executor (T2.1). */
    public BotMovementController mover() {
        return mover;
    }

    /** Look control (T2.2). */
    public BotLookController look() {
        return look;
    }

    /** Strategic path planner (T2.3). */
    public BotPathPlanner planner() {
        return planner;
    }

    @Override
    public void tick() {
        // Perception (T3.1): snapshot all facts first, so every layer sees the same tick.
        perception.gather(this);
        // Phase 3+ inserts: reflex → decision here (consume perception).
        // Strategic layer: A* planner picks the next node → sets the movement target.
        planner.tick(this);
        // Action layer: set movement inputs before the physics tick consumes them.
        mover.tick(this);

        // ServerPlayer.tick() does only housekeeping; the movement/LivingEntity tick lives in
        // doTick() (normally driven by the network connection). The bot has no connection ticking
        // it, so we drive BOTH here = a full player tick: housekeeping + aiStep/travel physics
        // (gravity, collision, step-up, friction, hunger, regen).
        super.tick();  // ServerPlayer housekeeping (gameMode, containers, criteria)
        this.doTick(); // Player/LivingEntity tick → aiStep → travel

        // Look control runs last so the head yaw/pitch it writes are the tick's final state
        // (vanilla's tickHeadTurn adjusts only yBodyRot, never yHeadRot).
        look.tick(this);
    }
}
