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
 * Rule 2 (승패 게이트) — BEHAVIOUR, not the dump.
 *
 * <p>This is the harness type R.2 was extended for. {@code bot_tactics} verified that the DUMP said
 * {@code allowMelee=false}, and passed for two months while nothing read that value: the rule was
 * computed correctly and decided nothing (defect type #10). The question here is not what the rule
 * says but what the bot does — how close it gets, and whether it swings.</p>
 *
 * <p>Both arms are required. Measuring only the refusal would pass a bot that never approaches
 * anything, so the opposite case (a weak target the bot SHOULD close on) is the control.</p>
 */
public class BotRule2ActionTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int RUN = 200;
    /** Vanilla melee reach is ~3; inside this the bot is unambiguously engaging in melee. */
    private static final double MELEE_RANGE = 3.5;

    private final boolean loseable;
    private Zombie target;
    private ServerPlayer user;
    private double minDist = Double.MAX_VALUE;
    private int ticksInMeleeRange;
    /**
     * The bot's OWN movement toward the target, summed. Raw distance is the wrong quantity: the
     * target walks at the user, so the gap closes to melee range whether or not the bot did
     * anything. First run measured minDist 2.90 with damage 0.0 — the bot stood still and the zombie
     * arrived. "Did the bot approach" has to be the bot's displacement projected on the axis to the
     * target, not the distance between them.
     */
    private double botApproachSum;
    private Vec3 lastBotPos;
    private float targetHpStart;
    private float targetHpEnd;
    private Boolean allowMeleeSeen;

    protected BotRule2ActionTest(boolean loseable) {
        this.loseable = loseable;
    }

    @Override
    public int[] arenaBounds() {
        return new int[]{-20, 20, -20, 20};
    }

    @Override
    public String name() {
        return loseable ? "bot_rule2_deny" : "bot_rule2_allow";
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

        for (int dx = -16; dx <= 16; dx++) {
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
        bot.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        bot.setHealth(20.0F);
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(true);      // the subject is the approach decision, not the fight outcome
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        // The protection layer is inert without a user, and rule 2 is wired inside it.
        user = TestUser.spawn(ctx.server, ctx.level, new BlockPos(o.getX(), o.getY(), o.getZ() + 2));
        user.setInvulnerable(true);
        user.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        user.setHealth(20.0F);          // >40% so the P1 branch (maxDPS) is taken

        target = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + 8, o.getY(), o.getZ()));
        if (target != null) {
            target.setPersistenceRequired();
            if (loseable) {
                // Unwinnable in melee: 400 hp against an iron sword, hitting hard enough that
                // timeToKill > timeToDie. Rule 2 must refuse.
                target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(400.0);
                target.setHealth(400.0F);
                target.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(20.0);
            } else {
                // Winnable: vanilla-ish zombie. Rule 2 must allow, and the bot must actually close.
                target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
                target.setHealth(20.0F);
                target.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(2.0);
            }
            target.setTarget(user);     // aggro on the user → inside the 9.1 intervention filter
            targetHpStart = target.getHealth();
        }
        minDist = Double.MAX_VALUE;
        ticksInMeleeRange = 0;
        allowMeleeSeen = null;
        botApproachSum = 0;
        lastBotPos = null;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || target == null || !target.isAlive()) {
            return true;
        }
        target.setTarget(user);
        double d = Math.hypot(target.getX() - bot.getX(), target.getZ() - bot.getZ());
        minDist = Math.min(minDist, d);
        if (lastBotPos != null && d > 1.0E-6) {
            double ux = (target.getX() - bot.getX()) / d;
            double uz = (target.getZ() - bot.getZ()) / d;
            botApproachSum += (bot.getX() - lastBotPos.x) * ux + (bot.getZ() - lastBotPos.z) * uz;
        }
        lastBotPos = bot.position();
        if (d <= MELEE_RANGE) {
            ticksInMeleeRange++;
        }
        for (TargetInfo ti : bot.perception().targets) {
            if (ti.entity == target) {
                allowMeleeSeen = CombatRules.allowMelee(ti, CombatStats.of(bot),
                        CombatRules.DEFAULT_SAFETY);
                break;
            }
        }
        targetHpEnd = target.getHealth();
        return ctx.elapsedTicks >= RUN;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        double dmgDealt = targetHpStart - targetHpEnd;
        // Judged on what the BOT did: did it move toward the target, and did it land melee damage.
        boolean approached = botApproachSum > 1.0;
        boolean ok = loseable
                ? (!approached && dmgDealt <= 0.01)  // refused: did not close, did not hit
                : (approached && dmgDealt > 0.01);   // allowed: closed AND hit

        LOGGER.info("[RULE2] {} allowMelee={} botApproach={} minDist={} meleeTicks={} dmg={}",
                name(), allowMeleeSeen, String.format("%.2f", botApproachSum),
                String.format("%.2f", minDist), ticksInMeleeRange, String.format("%.1f", dmgDealt));
        String measured = String.format(
                "allowMelee:%s,botApproachSum:%.2f,minDist:%.2f,meleeRange:%.1f,"
                        + "ticksInMeleeRange:%d,damageDealt:%.1f,targetHp:%.0f->%.0f",
                String.valueOf(allowMeleeSeen), botApproachSum, minDist, MELEE_RANGE,
                ticksInMeleeRange, dmgDealt, targetHpStart, targetHpEnd);
        String expected = loseable
                ? "rule 2 denies melee → the BOT does not move toward the target and deals no damage"
                : "rule 2 allows melee → the BOT moves toward the target and deals damage";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    /** Unwinnable target: the bot must refuse melee. */
    public static class Deny extends BotRule2ActionTest {
        public Deny() {
            super(true);
        }
    }

    /** Winnable target: the control, without which "a bot that never approaches" would pass. */
    public static class Allow extends BotRule2ActionTest {
        public Allow() {
            super(false);
        }
    }
}
