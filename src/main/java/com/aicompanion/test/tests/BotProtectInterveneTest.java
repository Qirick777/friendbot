package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.aicompanion.test.TestUser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * T4.3 [검증] (a) + the U1 contrast: with a user present, a zombie aggroed on/beside the user must
 * be pre-emptively engaged (its health starts dropping), while a NEUTRAL mob far from the user and
 * not aggroed must be left alone (health unchanged). Both directions of design 9.1 are measured —
 * "engage when it's a threat to the user" AND "no preemptive attack otherwise". Also logs whether a
 * mob naturally acquires the fake-player user as its target (fake-player targetability check).
 */
public class BotProtectInterveneTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * K-2 negative control. Everything identical except the aggro zombie is placed OUTSIDE 9.4's
     * 10-block invade radius from the user and out of the bot's reach, so 9장's own admission test
     * ({@code engageable}) rejects it. If {@code aggroDrop} still lands near 97 here, the value is a
     * residual (유형 #3) or a side effect (유형 #10) rather than the intervention.
     */
    private final boolean reachable;

    protected BotProtectInterveneTest(boolean reachable) {
        this.reachable = reachable;
    }

    public BotProtectInterveneTest() {
        this(true);
    }

    private Zombie aggro;    // beside the user, AI-on → naturally targets the (fake) user
    private Zombie neutral;  // far from the user, no aggro → must NOT be attacked
    private double aggroHp0;
    private double neutralHp0;
    private double aggroMinHp;
    private double neutralMinHp;
    private boolean naturalTargetSeen;
    // K-2(a): mode was read ONCE in judge(), i.e. the last tick — a field named "mode" that actually
    // measured "mode at the instant the window closed". Sampled across the window instead.
    private int modeEngageTicks;
    private int modeNoneTicks;
    private String modeAtEnd = "none";
    // K-2(b): did 9장's intervention path actually run? Counted inside BotProtection.assignTarget.
    private int engagePathTicks;
    private int aggroDistOkTicks;
    private double aggroUserDistMin = Double.MAX_VALUE;

    @Override
    public int repeats() {
        return 3;
    }

    @Override
    public double successThreshold() {
        return 1.00;
    }

    @Override
    public String name() {
        return reachable ? "bot_protect_intervene" : "bot_protect_intervene_none";
    }

    @Override
    public String scenarioSpec() {
        return String.format(
                "user invulnerable at full hp (>40%% → 9.2 P1); bot iron sword only, no bow; a 100hp "
                + "AI-on zombie with knockback resistance 1.0 %s; a 100hp NoAi zombie 14 blocks away "
                + "from the user as the within-run U1 control; NORMAL difficulty, night; 240-tick window",
                reachable ? "2 blocks from the user (inside 9.4's 10-block invade radius)"
                        : "22 blocks from the user (OUTSIDE the invade radius — 개입 불가 대조)");
    }

    @Override
    public int timeoutTicks() {
        return 260;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);

        for (int dx = -18; dx <= 26; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 2; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        // User stand-in: invulnerable + full health so it stays in the ">40%" band all test.
        ServerPlayer user = TestUser.spawn(ctx.server, ctx.level, o);
        user.setInvulnerable(true);
        user.setHealth(user.getMaxHealth());

        // Bot near the user, iron sword only (no bow → melee protection).
        bot.survival().reset();
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() - 2.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.setInvulnerable(true);
        bot.setHealth(bot.getMaxHealth());
        bot.getInventory().clearContent();
        bot.getInventory().add(new ItemStack(Items.IRON_SWORD));
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        // Aggroed threat BESIDE the user (within 10). AI on with normal speed → it should naturally
        // target the (fake) user; difficulty must be non-peaceful for hostile targeting.
        ctx.server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        aggro = ctx.env.spawn(EntityType.ZOMBIE,
                new BlockPos(o.getX() + (reachable ? 2 : 22), o.getY(), o.getZ()));
        if (aggro != null) {
            aggro.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0);
            aggro.setHealth(100.0F);
            aggro.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
        }

        // Neutral mob FAR from the user (>10) and not aggroed → must be ignored (U1).
        neutral = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() - 14, o.getY(), o.getZ()));
        if (neutral != null) {
            neutral.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0);
            neutral.setHealth(100.0F);
            neutral.setNoAi(true); // stationary, never aggroes
        }

        aggroHp0 = aggro != null ? aggro.getHealth() : 0;
        neutralHp0 = neutral != null ? neutral.getHealth() : 0;
        aggroMinHp = aggroHp0;
        neutralMinHp = neutralHp0;
        naturalTargetSeen = false;
        modeEngageTicks = 0;
        modeNoneTicks = 0;
        modeAtEnd = "none";
        engagePathTicks = 0;
        aggroDistOkTicks = 0;
        aggroUserDistMin = Double.MAX_VALUE;
        bot.protection().resetCounters();
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        if (aggro != null && aggro.isAlive()) {
            aggroMinHp = Math.min(aggroMinHp, aggro.getHealth());
            if (aggro.getTarget() instanceof ServerPlayer) {
                naturalTargetSeen = true; // a mob naturally targeted the fake-player user/bot
            }
        }
        if (neutral != null) {
            neutralMinHp = Math.min(neutralMinHp, neutral.getHealth());
        }
        AICompanionBot bot = BotManager.current();
        if (bot != null) {
            String m = bot.protection().mode().name();
            modeAtEnd = m;
            if (m.startsWith("ENGAGE")) {
                modeEngageTicks++;
            } else if ("NONE".equals(m)) {
                modeNoneTicks++;
            }
            engagePathTicks = bot.protection().engageAssignTicks();
            ServerPlayer u = bot.perception().user;
            if (u != null && aggro != null && aggro.isAlive()) {
                double d = aggro.position().distanceTo(u.position());
                aggroUserDistMin = Math.min(aggroUserDistMin, d);
                if (d <= 10.0) {
                    aggroDistOkTicks++;   // 9.4 침범 판정 반경 안에 있던 틱
                }
            }
        }
        return ctx.elapsedTicks >= 240;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        double aggroDrop = aggroHp0 - aggroMinHp;
        double neutralDrop = neutralHp0 - neutralMinHp;
        AICompanionBot bot = BotManager.current();
        String protMode = bot != null ? bot.protection().mode().name() : "null";

        // K-2(b)/(c): the control arm asserts the OPPOSITE — no intervention path, no damage.
        boolean engaged = reachable
                ? (aggroDrop > 0.5 && engagePathTicks > 0)
                : (aggroDrop <= 0.5 && engagePathTicks == 0);
        boolean leftNeutral = neutralDrop < 0.01; // U1: no preemptive attack on the neutral mob
        boolean ok = engaged && leftNeutral;

        LOGGER.info("[PROTECT] intervene judge: aggroDrop={} neutralDrop={} naturalTarget={} mode={}",
                aggroDrop, neutralDrop, naturalTargetSeen, protMode);
        String measured = String.format(
                "aggroDrop:%.1f,neutralDrop:%.1f,naturalTarget:%b,modeAtEnd:%s,modeEngageTicks:%d,"
                        + "modeNoneTicks:%d,engagePathTicks:%d,aggroInInvadeRadiusTicks:%d,"
                        + "aggroUserDistMin:%.2f,arm:%s",
                aggroDrop, neutralDrop, naturalTargetSeen, modeAtEnd, modeEngageTicks, modeNoneTicks,
                engagePathTicks, aggroDistOkTicks, aggroUserDistMin,
                reachable ? "reachable" : "unreachable-control");
        String expected = reachable
                ? "aggroDrop>0 AND the 9장 intervention path actually ran (engagePathTicks>0) "
                        + "AND neutralDrop==0 (U1 no preemptive)"
                : "aggro zombie outside 9.4's invade radius → 9장 never assigns it "
                        + "(engagePathTicks==0) and it takes no damage (aggroDrop≈0)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    /** The spec's case. */
    public static class Reachable extends BotProtectInterveneTest {
        public Reachable() {
            super(true);
        }
    }

    /** K-2 negative control: 개입 불가 조건. */
    public static class Unreachable extends BotProtectInterveneTest {
        public Unreachable() {
            super(false);
        }
    }
}
