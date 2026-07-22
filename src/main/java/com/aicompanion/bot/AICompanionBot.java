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
    private final com.aicompanion.bot.combat.BotMeleeCombat meleeCombat =
            new com.aicompanion.bot.combat.BotMeleeCombat();
    private final com.aicompanion.bot.combat.BotRangedCombat rangedCombat =
            new com.aicompanion.bot.combat.BotRangedCombat();
    private final com.aicompanion.bot.combat.BotSurvival survival =
            new com.aicompanion.bot.combat.BotSurvival();
    private final com.aicompanion.bot.combat.BotReflex reflex =
            new com.aicompanion.bot.combat.BotReflex();

    public AICompanionBot(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
    }

    /** Perception layer (T3.1). */
    public com.aicompanion.bot.perception.Perception perception() {
        return perception;
    }

    /** Critical-hit melee combat (T3.3). */
    public com.aicompanion.bot.combat.BotMeleeCombat meleeCombat() {
        return meleeCombat;
    }

    /** Predictive ranged combat (T3.4). */
    public com.aicompanion.bot.combat.BotRangedCombat rangedCombat() {
        return rangedCombat;
    }

    /** Survival state machine (T4.1). */
    public com.aicompanion.bot.combat.BotSurvival survival() {
        return survival;
    }

    /** Reflex layer (T4.2). */
    public com.aicompanion.bot.combat.BotReflex reflex() {
        return reflex;
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
        // Reflex layer (T4.2) runs first (design ch.7 "판단보다 먼저, 매 틱 최우선"). R0 totem
        // pre-equip never blocks; R1 shield/sidestep owns movement for the tick when it fires.
        reflex.tickR0(this);
        boolean evading = reflex.tickR1(this);

        // Survival (T4.1) has next priority (design 8.1 "위가 이긴다"): if a health-driven survival
        // mode is active, it overrides combat and movement this tick.
        boolean survivalActive = !evading && survival.tick(this);
        if (evading) {
            // reflex.tickR1 already drove movement (shield up / sidestep).
        } else if (survivalActive) {
            // survival.tick already drove movement inputs / item use / pearl throw.
        } else if (meleeCombat.hasTarget()) {
            // Melee combat (T3.3) drives movement inputs directly (no A*/mover).
            meleeCombat.tick(this);
        } else if (rangedCombat.hasTarget()) {
            // Ranged combat (T3.4) owns aim (yaw/pitch) and movement inputs directly, and fires
            // the arrow here (before the physics tick) so shootFromRotation reads the aim it wrote.
            rangedCombat.tick(this);
        } else {
            // Strategic layer: A* planner picks the next node → sets the movement target.
            planner.tick(this);
            // Action layer: set movement inputs before the physics tick consumes them.
            mover.tick(this);
        }

        // ServerPlayer.tick() does only housekeeping; the movement/LivingEntity tick lives in
        // doTick() (normally driven by the network connection). The bot has no connection ticking
        // it, so we drive BOTH here = a full player tick: housekeeping + aiStep/travel physics
        // (gravity, collision, step-up, friction, hunger, regen).
        super.tick();  // ServerPlayer housekeeping (gameMode, containers, criteria)
        this.doTick(); // Player/LivingEntity tick → aiStep → travel

        // Look control runs last so the head yaw/pitch it writes are the tick's final state
        // (vanilla's tickHeadTurn adjusts only yBodyRot, never yHeadRot). Ranged combat owns the
        // aim (xRot/yaw) itself — the look controller must not fight it, so skip it while shooting.
        // Survival and reflex also own rotation (facing/away from the threat) when active.
        if (!rangedCombat.hasTarget() && !survivalActive && !evading) {
            look.tick(this);
        }
    }
}
