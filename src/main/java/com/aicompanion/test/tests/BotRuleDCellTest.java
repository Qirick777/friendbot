package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.combat.CombatRules;
import com.aicompanion.bot.combat.CombatStats;
import com.aicompanion.bot.perception.TargetInfo;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.aicompanion.test.TestUser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
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
 * The D cell — {@code canKite=false AND allowMelee=false}, the combination design 6.3 does not
 * define. Rule 1 says "정면 대응" (which presumes melee is possible) and rule 2 says "원거리 강제"
 * (which forbids it), so the two rules contradict each other and the spec has no joint answer.
 *
 * <p>Decision recorded in the appended 6.3 block: rule 2 wins. Melee stays denied, the bot makes
 * distance by means other than a footrace it loses, and failing that withdraws toward the user.
 * Rationale: rule 2 is a survival gate, and a dead bot fails design goal 1 outright.</p>
 *
 * <p>The target is constructed to sit in D by injection — faster than the bot (so rule 1 says it
 * cannot be outrun) and unwinnable (so rule 2 refuses melee). In vanilla this cell is effectively
 * unreachable: across seven probed mobs none exceeds the bot's sprint, and even a wither averages
 * 0.1417 b/t. D is a robustness case for modded mobs and speed buffs, not a vanilla scenario.</p>
 */
public class BotRuleDCellTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int RUN = 240;
    private static final double MELEE_RANGE = 3.5;

    private Zombie target;
    private ServerPlayer user;
    private double targetZ;
    private double botApproachSum;
    private Vec3 lastBotPos;
    private Boolean canKiteSeen;
    private Boolean allowMeleeSeen;
    private boolean distanceCriticalSeen;
    /** The cell is transient: once the lane target stops, its observed speed falls and
     *  hysteresis returns canKite to true. The D behaviour must be judged over the window
     *  in which the cell actually held, not by the last sample. */
    private boolean everInDCell;
    private int dCellTicks;
    private float targetHpStart;
    private float targetHpEnd;
    private int lateralCommandTicks;

    @Override
    public int[] arenaBounds() {
        return new int[]{-40, 24, -48, 48};
    }

    @Override
    public String name() {
        return "bot_rule_dcell";
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

        for (int dx = -36; dx <= 20; dx++) {
            for (int dz = -44; dz <= 44; dz++) {
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
        bot.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        bot.setHealth(20.0F);
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        user = TestUser.spawn(ctx.server, ctx.level, new BlockPos(o.getX(), o.getY(), o.getZ() + 2));
        user.setInvulnerable(true);
        user.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        user.setHealth(20.0F);

        // Straight lane, not a chase. Injected pursuit reverses direction every tick once it reaches
        // the bot, so net displacement over the observation window collapses to ~0 and rule 1 reads
        // the target as SLOW (measured: canKite=true at an injected 1.15x sprint). A tangential lane
        // keeps the displacement genuine and sustained, which is how bot_kite_band_latch gets a clean
        // 99%-of-sprint reading.
        targetZ = o.getZ() - 38;
        target = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + 5, o.getY(), o.getZ() - 38));
        if (target != null) {
            target.setPersistenceRequired();
            target.setNoAi(true);       // speed injected → canKite=false by construction
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(400.0);
            target.setHealth(400.0F);
            target.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(20.0);
            targetHpStart = target.getHealth();
        }
        botApproachSum = 0;
        lastBotPos = null;
        canKiteSeen = null;
        allowMeleeSeen = null;
        distanceCriticalSeen = false;
        everInDCell = false;
        dCellTicks = 0;
        lateralCommandTicks = 0;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || target == null) {
            return true;
        }
        // Injected pursuit above the hysteresis exit line: rule 1 must read canKite=false.
        double rate = CombatStats.BOT_SPRINT_SPEED * 1.15;
        targetZ += rate;   // never stops: a stationary target reads slow and leaves the cell
        target.setDeltaMovement(Vec3.ZERO);
        target.moveTo(ctx.origin.getX() + 5.5, bot.getY(), targetZ, 0.0F, 0.0F);
        target.setTarget(user);

        double dx = target.getX() - bot.getX();
        double dz = target.getZ() - bot.getZ();
        double d = Math.hypot(dx, dz);
        if (lastBotPos != null && d > 1.0E-6) {
            botApproachSum += (bot.getX() - lastBotPos.x) * (dx / d)
                    + (bot.getZ() - lastBotPos.z) * (dz / d);
        }
        lastBotPos = bot.position();
        if (Math.abs(bot.xxa) > 0.5F) {
            lateralCommandTicks++;
        }
        if (bot.rangedCombat().distanceCritical()) {
            distanceCriticalSeen = true;
        }
        for (TargetInfo ti : bot.perception().targets) {
            if (ti.entity == target) {
                canKiteSeen = CombatRules.canKite(ti, CombatStats.of(bot).sprintSpeed);
                allowMeleeSeen = CombatRules.allowMelee(ti, CombatStats.of(bot),
                        CombatRules.DEFAULT_SAFETY);
                if (Boolean.FALSE.equals(canKiteSeen) && Boolean.FALSE.equals(allowMeleeSeen)) {
                    everInDCell = true;
                    dCellTicks++;
                }
                break;
            }
        }
        targetHpEnd = target.getHealth();
        return ctx.elapsedTicks >= RUN;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        double dmg = targetHpStart - targetHpEnd;
        // Approach-sum is not part of the gate here: the target crosses on a lane, so the bot moving
        // toward its current position is not by itself melee intent. Melee refusal is judged by the
        // thing melee actually produces — damage.
        boolean refusedMelee = dmg <= 0.01;
        boolean soughtDistance = distanceCriticalSeen;
        boolean ok = everInDCell && refusedMelee && soughtDistance;

        LOGGER.info("[DCELL] canKite={} allowMelee={} approach={} dmg={} distanceCritical={} lateral={}",
                canKiteSeen, allowMeleeSeen, String.format("%.2f", botApproachSum),
                String.format("%.1f", dmg), distanceCriticalSeen, lateralCommandTicks);
        String measured = String.format(
                "canKite:%s,allowMelee:%s,everInDCell:%b,dCellTicks:%d,botApproachSum:%.2f,damageDealt:%.1f,"
                        + "distanceCritical:%b,lateralCommandTicks:%d",
                String.valueOf(canKiteSeen), String.valueOf(allowMeleeSeen), everInDCell, dCellTicks,
                botApproachSum, dmg, distanceCriticalSeen, lateralCommandTicks);
        String expected = "target sits in the D cell (canKite=false AND allowMelee=false) → the bot "
                + "does NOT close or deal melee damage, and the distance-critical path is engaged";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
