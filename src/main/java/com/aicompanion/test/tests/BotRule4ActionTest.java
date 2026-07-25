package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.combat.Layer2Registry;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Rule 4 (방패·넉백 유효성) — BEHAVIOUR, and specifically that the rule was COMBINED rather than
 * substituted.
 *
 * <p>Three arms, because the combination has three ways to be wrong:</p>
 * <ul>
 *   <li><b>pierce</b> — shooter's layer-2 profile pierces armour ⇒ {@code useShield=false}. The
 *       shield must stay down even though a projectile is inbound and a shield is held. This arm
 *       fails if rule 4 was never wired.</li>
 *   <li><b>normal</b> — ordinary shooter ⇒ shield goes up. The control; without it a bot that never
 *       raises its shield passes the pierce arm.</li>
 *   <li><b>axe</b> — ordinary shooter but the enemy holds an axe. The shield must stay down. This
 *       arm fails if rule 4 REPLACED the existing conditions instead of being ANDed with them,
 *       because the axe knowledge exists only in the old condition.</li>
 * </ul>
 */
public class BotRule4ActionTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int RUN = 220;

    private final String arm;
    private Skeleton shooter;
    /**
     * The axe arm needs a SEPARATE holder: {@code nearestEnemyHasAxe} reads the main hand, and the
     * shooter's main hand must hold the bow or no projectile ever arrives and the arm proves nothing.
     * So the axe goes on a nearer melee enemy — which is also the realistic shape of the scenario.
     */
    private Zombie axeHolder;
    private int blockingTicks;
    private int arrowsSeen;
    /** Ticks where the NEAREST perceived target held an axe in its main hand — the exact
     *  predicate BotReflex.nearestEnemyHasAxe evaluates. Separates "axe not detected" from
     *  "axe detected but ignored". */
    private int nearestHasAxeTicks;
    private String nearestSeen = "none";

    protected BotRule4ActionTest(String arm) {
        this.arm = arm;
    }

    @Override
    public int[] arenaBounds() {
        return new int[]{-14, 22, -16, 16};
    }

    @Override
    public String name() {
        return "bot_rule4_" + arm;
    }

    @Override
    public int timeoutTicks() {
        return RUN + 60;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        Layer2Registry.clearAllOverrides();

        for (int dx = -10; dx <= 18; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 3; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        bot.survival().reset();
        bot.getInventory().clearContent();
        bot.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0);
        bot.setHealth(200.0F);
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        shooter = ctx.env.spawn(EntityType.SKELETON, new BlockPos(o.getX() + 14, o.getY(), o.getZ()));
        if (shooter != null) {
            shooter.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
            shooter.setPersistenceRequired();
            shooter.setInvulnerable(true);
            shooter.setTarget(bot);
            if ("pierce".equals(arm)) {
                // Layer-2 says this shooter's damage goes through armour and shields, so rule 4
                // answers useShield=false regardless of the shield in hand.
                Layer2Registry.override(shooter, Layer2Registry.WARDEN);
            }
        }
        if ("axe".equals(arm)) {
            // Vanilla axes disable shields. That knowledge lives in the pre-existing condition,
            // NOT in rule 4 — this arm is what proves the two were combined.
            axeHolder = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + 2, o.getY(), o.getZ()));
            if (axeHolder != null) {
                axeHolder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
                axeHolder.setPersistenceRequired();
                axeHolder.setInvulnerable(true);
                axeHolder.setNoAi(true);   // present as a threat, not as a mover
            }
        }
        blockingTicks = 0;
        arrowsSeen = 0;
        nearestHasAxeTicks = 0;
        nearestSeen = "none";
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || shooter == null) {
            return true;
        }
        shooter.setTarget(bot);
        // Pin the bot. BotReflex.nearestEnemyHasAxe inspects only the NEAREST target, so the axe
        // holder must stay nearest for the arm to test what it claims; letting collision shove the
        // bot backwards handed that slot to the approaching skeleton for 151 of 220 ticks.
        // (The nearest-only predicate itself is registered as debt — an axe wielder that is not the
        // closest enemy is invisible to it.)
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(ctx.origin.getX() + 0.5, ctx.origin.getY(), ctx.origin.getZ() + 0.5,
                bot.getYRot(), bot.getXRot());
        if (bot.isBlocking()) {
            blockingTicks++;
        }
        arrowsSeen = Math.max(arrowsSeen, bot.perception().incoming.size());
        for (com.aicompanion.bot.perception.TargetInfo t : bot.perception().targets) {
            if (t.entity != null && t.entity.isAlive()) {
                nearestSeen = t.entity.getType().toShortString() + "/"
                        + t.entity.getMainHandItem().getItem().toString();
                if (t.entity.getMainHandItem().getItem()
                        instanceof net.minecraft.world.item.AxeItem) {
                    nearestHasAxeTicks++;
                }
                break;
            }
        }
        return ctx.elapsedTicks >= RUN;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        boolean raised = blockingTicks > 0;
        boolean shouldRaise = "normal".equals(arm);
        boolean ok = raised == shouldRaise;

        LOGGER.info("[RULE4] arm={} blockingTicks={} maxIncoming={} raised={} expectRaise={}",
                arm, blockingTicks, arrowsSeen, raised, shouldRaise);
        String measured = String.format("arm:%s,blockingTicks:%d,shieldRaised:%b,maxIncoming:%d,axeHolder:%b,"
                + "nearestHasAxeTicks:%d,nearest:%s",
                arm, blockingTicks, raised, arrowsSeen, axeHolder != null, nearestHasAxeTicks, nearestSeen);
        String expected = shouldRaise
                ? "ordinary shooter + shield held + arrow inbound → shield goes UP"
                : ("pierce".equals(arm)
                        ? "rule 4 says the shield is useless against this shooter → shield stays DOWN"
                        : "axe enemy disables shields → shield stays DOWN (combination preserved)");
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    /** Layer-2 piercing shooter: rule 4 must veto the shield. */
    public static class Pierce extends BotRule4ActionTest {
        public Pierce() {
            super("pierce");
        }
    }

    /** Ordinary shooter: the control. */
    public static class Normal extends BotRule4ActionTest {
        public Normal() {
            super("normal");
        }
    }

    /** Axe enemy: the pre-existing condition must survive the combination. */
    public static class Axe extends BotRule4ActionTest {
        public Axe() {
            super("axe");
        }
    }
}
