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
    /** Signal B (kiting execution monitor). Evaluated above EVERY branch — see KiteMonitor. */
    private final com.aicompanion.bot.combat.KiteMonitor kiteMonitor =
            new com.aicompanion.bot.combat.KiteMonitor();
    /** T5.2 equipment manager — ch.14 「전투 로직과 분리된 상시 백그라운드」. */
    private final com.aicompanion.bot.equip.BotEquipment equipment =
            new com.aicompanion.bot.equip.BotEquipment();
    /** T5.3 생활 기능 — ch.15 배고픔·수면. Background only; never owns the tick. */
    private final com.aicompanion.bot.living.BotLiving living =
            new com.aicompanion.bot.living.BotLiving();
    /** T5.4 기본 상태 — ch.16 배회·추종. Chooses the idle goal; the planner/mover execute it. */
    private final com.aicompanion.bot.living.BotIdle idle =
            new com.aicompanion.bot.living.BotIdle();
    /** T5.5 자원 조달 — ch.17 던져준 것 수락·드롭 줍기. Sets a fetch goal; the planner executes it. */
    private final com.aicompanion.bot.living.BotPickup pickup =
            new com.aicompanion.bot.living.BotPickup();

    /**
     * 유형 #12 / L-4 instrumentation: which layer owned MOVEMENT this tick.
     *
     * <p>Every branch of the chain below is mutually exclusive, so exactly one owner is recorded per
     * tick. This turns "does 16장 이동 run in this harness?" from an argument into a measured value —
     * which is the whole point of 유형 #12: the premise 「봇은 가만히 있다」 used to be implicit, and
     * when T5.4 made the bot move on its own it silently changed what several harnesses measured.</p>
     */
    public enum MoveOwner { REFLEX, CREEPER, RESCUE, SURVIVAL, MELEE, RANGED, PICKUP, IDLE }

    private final int[] moveOwnerTicks = new int[MoveOwner.values().length];
    private MoveOwner lastMoveOwner = MoveOwner.IDLE;

    public int moveOwnerTicks(MoveOwner o) {
        return moveOwnerTicks[o.ordinal()];
    }

    public MoveOwner lastMoveOwner() {
        return lastMoveOwner;
    }

    public void resetMoveOwnerCounters() {
        java.util.Arrays.fill(moveOwnerTicks, 0);
    }

    /** Compact histogram for the verdict line, e.g. "IDLE:180,MELEE:60". Zero buckets omitted. */
    public String moveOwnerHistogram() {
        StringBuilder sb = new StringBuilder();
        for (MoveOwner o : MoveOwner.values()) {
            int n = moveOwnerTicks[o.ordinal()];
            if (n > 0) {
                if (sb.length() > 0) {
                    sb.append('/');
                }
                sb.append(o.name()).append(':').append(n);
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private void noteMoveOwner(MoveOwner o) {
        lastMoveOwner = o;
        moveOwnerTicks[o.ordinal()]++;
    }
    private final com.aicompanion.bot.combat.BotProtection protection =
            new com.aicompanion.bot.combat.BotProtection();
    private final com.aicompanion.bot.combat.BotEnvironment environment =
            new com.aicompanion.bot.combat.BotEnvironment();
    private final com.aicompanion.bot.combat.BotRescue rescue =
            new com.aicompanion.bot.combat.BotRescue();

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
    public com.aicompanion.bot.equip.BotEquipment equipment() {
        return equipment;
    }

    public com.aicompanion.bot.combat.KiteMonitor kiteMonitor() {
        return kiteMonitor;
    }

    /** 생활 기능 (T5.3 / ch.15). */
    public com.aicompanion.bot.living.BotLiving living() {
        return living;
    }

    /** 기본 상태 (T5.4 / ch.16). */
    public com.aicompanion.bot.living.BotIdle idle() {
        return idle;
    }

    /** 자원 조달 (T5.5 / ch.17). */
    public com.aicompanion.bot.living.BotPickup pickup() {
        return pickup;
    }

    public com.aicompanion.bot.combat.BotReflex reflex() {
        return reflex;
    }

    /** User-protection protocol (T4.3). */
    public com.aicompanion.bot.combat.BotProtection protection() {
        return protection;
    }

    /** Environment manipulation reflex — block placement + fall survival (T4.4). */
    public com.aicompanion.bot.combat.BotEnvironment environment() {
        return environment;
    }

    /** Kidnap-escape + user fall-catch via mounting (T4.5). */
    public com.aicompanion.bot.combat.BotRescue rescue() {
        return rescue;
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
        // T4.6: a charged-up special attack (layer-2 signal) outranks the ordinary R1 evade —
        // getting outside its range is the only thing that helps against a piercing hit.
        boolean chargeEscaping = reflex.tickChargeEscape(this);
        boolean evading = chargeEscaping || reflex.tickR1(this);

        // Signal B (design 6.6 실행 감시): evaluated here, above every branch. It used to live in
        // BotRangedCombat.tick, so it only ran when the ranged branch owned the tick — measured
        // consequence in bot_kite_execmon: a fast mob glued to the bot goes to the melee branch and
        // B was never evaluated at all while the bot lost 31.7 HP.
        kiteMonitor.tick(this);

        // 생활 기능 (T5.3 / ch.15): hunger + sleep. Runs BEFORE the equipment manager so that an
        // eat in progress can veto a weapon swap — vanilla's updatingUsingItem cancels the use the
        // moment the held stack stops matching useItem, so a swap mid-bite silently eats nothing.
        living.tick(this);

        // Equipment manager (T5.2 / ch.14): background, re-evaluates only on inventory change. Runs
        // above the combat branches so a swap is in hand before any controller uses it this tick.
        equipment.tick(this);

        // Environment reflex (T4.4): fall survival (R2) drops water/blocks under a fatal fall
        // (no movement ownership); creeper defense places a blast wall or shields+flees.
        environment.tickFallSurvival(this);
        boolean creeperActing = environment.tickCreeperDefense(this);

        // Rescue (T4.5): kidnap-escape (user critical) + user fall-catch, both via mounting. Runs in
        // the reflex layer; owns the tick when catching/carrying.
        boolean rescuing = !evading && !creeperActing && rescue.tick(this);

        // Survival (T4.1) has next priority (design 8.1 "위가 이긴다"): if a health-driven survival
        // mode is active, it overrides combat and movement this tick.
        boolean survivalActive = !evading && !creeperActing && !rescuing && survival.tick(this);

        // User protection (T4.3): top-level coordinator below reflex/survival, above combat. It
        // selects which enemy to engage (or follow/heal/flee) and hands it to the combat controllers,
        // which run in the branches below. Inert when there is no user.
        if (!evading && !creeperActing && !rescuing && !survivalActive) {
            protection.tick(this);
        }

        if (evading) {
            // reflex.tickR1 already drove movement (shield up / sidestep).
            noteMoveOwner(MoveOwner.REFLEX);
        } else if (creeperActing) {
            // environment.tickCreeperDefense already drove movement (wall + step / shield + flee).
            noteMoveOwner(MoveOwner.CREEPER);
        } else if (rescuing) {
            // rescue.tick already drove movement (approach / mount / sprint-away).
            noteMoveOwner(MoveOwner.RESCUE);
        } else if (survivalActive) {
            // survival.tick already drove movement inputs / item use / pearl throw.
            noteMoveOwner(MoveOwner.SURVIVAL);
        } else if (meleeCombat.hasTarget()) {
            // Melee combat (T3.3) drives movement inputs directly (no A*/mover).
            noteMoveOwner(MoveOwner.MELEE);
            meleeCombat.tick(this);
        } else if (rangedCombat.hasTarget()) {
            // Ranged combat (T3.4) owns aim (yaw/pitch) and movement inputs directly, and fires
            // the arrow here (before the physics tick) so shootFromRotation reads the aim it wrote.
            noteMoveOwner(MoveOwner.RANGED);
            rangedCombat.tick(this);
        } else {
            // 평상시 (T5.4 / ch.16): nothing above claimed the tick, so this is the idle state.
            // It only chooses the goal — the planner and mover below carry it out, which is why
            // 「걸어서 따라옴 … 텔레포트 안 함」 holds without any special case.
            // 17장 자원 조달 first: a gift the user threw outranks 배회 (and only 배회 — combat,
            // survival and rescue all claimed the tick before this branch was reached).
            if (pickup.tick(this)) {
                noteMoveOwner(MoveOwner.PICKUP);
            } else {
                idle.tick(this);
                noteMoveOwner(MoveOwner.IDLE);
            }
            // Strategic layer: A* planner picks the next node → sets the movement target.
            planner.tick(this);
            // Action layer: set movement inputs before the physics tick consumes them.
            mover.tick(this);
        }

        // 15.1 「포만도 6 이하에서 스프린트 불가」: applied after every branch has set its movement
        // inputs, so it overrides whichever controller asked to sprint this tick.
        com.aicompanion.bot.living.BotLiving.applySprintClamp(this);

        // ServerPlayer.tick() does only housekeeping; the movement/LivingEntity tick lives in
        // doTick() (normally driven by the network connection). The bot has no connection ticking
        // it, so we drive BOTH here = a full player tick: housekeeping + aiStep/travel physics
        // (gravity, collision, step-up, friction, hunger, regen).
        double preX = this.getX();
        double preY = this.getY();
        double preZ = this.getZ();
        super.tick();  // ServerPlayer housekeeping (gameMode, containers, criteria)
        this.doTick(); // Player/LivingEntity tick → aiStep → travel

        // ServerPlayer.checkFallDamage is an empty no-op; vanilla runs fall damage via
        // doCheckFallDamage from the CLIENT move packet — which the connection-less bot never
        // receives. So we drive it ourselves (like doTick) with this tick's actual displacement,
        // giving the bot genuine fallDistance accumulation and fall damage (and a real R2 trigger).
        this.doCheckFallDamage(this.getX() - preX, this.getY() - preY, this.getZ() - preZ, this.onGround());

        // Drive passenger positioning (T4.5): the fake-player passenger's own rideTick may not run,
        // so glue it to the bot's head here every tick — after the bot has moved.
        rescue.positionPassengers(this);

        // Look control runs last so the head yaw/pitch it writes are the tick's final state
        // (vanilla's tickHeadTurn adjusts only yBodyRot, never yHeadRot). Ranged combat owns the
        // aim (xRot/yaw) itself — the look controller must not fight it, so skip it while shooting.
        // Survival and reflex also own rotation (facing/away from the threat) when active.
        if (!rangedCombat.hasTarget() && !survivalActive && !evading && !creeperActing && !rescuing) {
            look.tick(this);
        }
    }
}
