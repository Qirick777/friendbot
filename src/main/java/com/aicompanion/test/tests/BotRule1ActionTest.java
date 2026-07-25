package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.combat.CombatRules;
import com.aicompanion.bot.combat.CombatStats;
import com.aicompanion.bot.perception.TargetInfo;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Rule 1 (카이팅 가능성) — BEHAVIOUR.
 *
 * <p>Before wiring, {@code canKite} had zero live consumers: the ranged controller backed away to
 * hold the band for every target, whether or not the bot could actually outrun it. The measured
 * consequence is in the record — a warden closes 19.79 → 6.39 blocks in 30/30 engagements while the
 * bot retreats on foot at 72% of its sprint.</p>
 *
 * <p>Judged on the bot's own radial movement while inside the band's lower bound: retreating on foot
 * (radial-away) is only sensible when rule 1 says the bot is the faster one. When it is not, the
 * controller should not spend the engagement walking backwards.</p>
 *
 * <p>Both arms: a fast target (canKite=false, injected above the hysteresis exit line) and a slow one
 * (canKite=true). Without the slow arm, a controller that never retreats would pass.</p>
 */
public class BotRule1ActionTest implements BotTest {

    private static final int RUN = 260;
    private static final int START_GAP = 4;      // inside bandMin from the start
    private static final Logger LOGGER = LogUtils.getLogger();

    private final boolean fast;
    private Zombie target;
    private double targetX;
    private double radialAwaySum;   // bot displacement projected AWAY from the target
    private double lateralSum;      // ... and perpendicular
    private Vec3 lastBotPos;
    private Boolean canKiteSeen;
    private int ticksInside;
    /**
     * Ticks the controller actually COMMANDED a retreat while inside the band. Displacement alone is
     * the wrong quantity here: an injected pursuer shoves the bot by entity collision, so a bot that
     * issues no movement at all still drifts backwards. First run measured radialAway 3.16 with
     * lateral 0.00 — pure pushback, no input. The decision rule 1 changes is the command.
     */
    private int retreatCommandTicks;

    protected BotRule1ActionTest(boolean fast) {
        this.fast = fast;
    }

    @Override
    public int[] arenaBounds() {
        return new int[]{-40, 20, -20, 20};
    }

    @Override
    public String name() {
        return fast ? "bot_rule1_nokite" : "bot_rule1_kite";
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

        for (int dx = -36; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
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
        bot.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        bot.getInventory().add(new ItemStack(Items.ARROW, 64));
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0);
        bot.setHealth(200.0F);
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        targetX = o.getX() + START_GAP;
        target = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + START_GAP, o.getY(), o.getZ()));
        if (target != null) {
            target.setInvulnerable(true);
            target.setNoAi(true);            // speed is INJECTED so canKite is set by construction
            target.setPersistenceRequired();
        }
        radialAwaySum = 0;
        lateralSum = 0;
        lastBotPos = null;
        canKiteSeen = null;
        ticksInside = 0;
        retreatCommandTicks = 0;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || target == null) {
            return true;
        }
        // Injected pursuit: above the hysteresis exit line for the fast arm, well below for the slow.
        double rate = CombatStats.BOT_SPRINT_SPEED * (fast ? 1.15 : 0.35);
        double dir = Math.signum(bot.getX() - targetX);
        targetX += dir * rate;
        target.setDeltaMovement(Vec3.ZERO);
        target.moveTo(targetX, bot.getY(), bot.getZ(), 0.0F, 0.0F);

        bot.rangedCombat().setTarget(target);

        double dx = target.getX() - bot.getX();
        double dz = target.getZ() - bot.getZ();
        double d = Math.hypot(dx, dz);
        for (TargetInfo ti : bot.perception().targets) {
            if (ti.entity == target) {
                canKiteSeen = CombatRules.canKite(ti, CombatStats.of(bot).sprintSpeed);
                if (lastBotPos != null && d > 1.0E-6) {
                    double ux = dx / d;
                    double uz = dz / d;
                    double mx = bot.getX() - lastBotPos.x;
                    double mz = bot.getZ() - lastBotPos.z;
                    if (d < CombatRules.bandMin(ti)) {
                        ticksInside++;
                        if (bot.zza < -0.5F) {
                            retreatCommandTicks++;
                        }
                        radialAwaySum += -(mx * ux + mz * uz);   // + = moving away from the target
                        lateralSum += Math.abs(mx * (-uz) + mz * ux);
                    }
                }
                break;
            }
        }
        lastBotPos = bot.position();
        return ctx.elapsedTicks >= RUN;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        boolean retreated = retreatCommandTicks > 0;
        boolean ok = fast ? !retreated : retreated;

        LOGGER.info("[RULE1] {} canKite={} ticksInsideBand={} retreatCmd={} radialAway={} lateral={}",
                name(), canKiteSeen, ticksInside, retreatCommandTicks,
                String.format("%.2f", radialAwaySum), String.format("%.2f", lateralSum));
        String measured = String.format(
                "canKite:%s,ticksInsideBand:%d,retreatCommandTicks:%d,radialAwaySum(incl.pushback):%.2f,"
                        + "lateralSum:%.2f,injectedRatio:%.2f",
                String.valueOf(canKiteSeen), ticksInside, retreatCommandTicks, radialAwaySum,
                lateralSum, fast ? 1.15 : 0.35);
        String expected = fast
                ? "canKite=false → the controller issues NO retreat command inside the band"
                : "canKite=true → the controller DOES command a retreat to hold the band";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    /** Faster than the bot: retreating on foot cannot work, so it must not be attempted. */
    public static class NoKite extends BotRule1ActionTest {
        public NoKite() {
            super(true);
        }
    }

    /** Slower than the bot: the control — band keeping must still happen. */
    public static class Kite extends BotRule1ActionTest {
        public Kite() {
            super(false);
        }
    }
}
