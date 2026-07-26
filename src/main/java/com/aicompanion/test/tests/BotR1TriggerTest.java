package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.perception.ObservedRanged;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * R1 trigger correction — design ch.7 R1, verbatim:
 *
 * <pre>
 * 적 공격 모션 or 투사체가 봇 히트박스로 향함:
 *   방패 보유 → 방패 즉시 올림(오프핸드 use)
 *   아니면    → 수직 방향 사이드스텝(궤적 법선으로 1~2틱)
 * </pre>
 *
 * <p>The implementation's condition had been 「투사체 접근 OR 원거리적(RangedAttackMob)이 근처」.
 * Neither half of that is the spec's second term: it fired on <em>proximity to a class</em> and
 * never on an attack motion. Three arms measure the corrected predicate by behaviour:</p>
 *
 * <ul>
 *   <li><b>swing</b> — a zombie in reach, mid-swing, and a shield in the off-hand. R1 must fire and
 *       the shield must actually go up ({@code isBlocking}). This case produced NOTHING before.</li>
 *   <li><b>idle</b> — the CONTRAST: the same zombie at the same distance, never swinging. R1 must
 *       not fire at all.</li>
 *   <li><b>proximity</b> — the REGRESSION the fix targets: a skeleton 10 blocks away that has never
 *       fired a shot. Under the old class test R1 owned every tick; under 6.2's observation
 *       predicate 「투사체 발사 관측」 it must not fire, and {@code observedRanged} must be false.</li>
 * </ul>
 */
public class BotR1TriggerTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int RUN = 120;

    private final Mode mode;
    private net.minecraft.world.entity.Mob mob;
    private int r1Ticks;
    private int blockingTicks;
    private int attackMotionTicks;
    private boolean observedRangedSeen;
    private int swingsIssued;

    public enum Mode { SWING, PROJECTILE, IDLE, PROXIMITY }

    protected BotR1TriggerTest(Mode mode) {
        this.mode = mode;
    }

    @Override
    public int[] arenaBounds() {
        return new int[]{-16, 16, -16, 16};
    }

    @Override
    public String scenarioSpec() {
        return String.format(
                "%s at %d blocks with NoAi (it cannot move, attack for real, or shoot), shield in "
                + "the bot's off-hand, ObservedRanged cleared so nothing has been seen firing; the "
                + "ONLY difference between the swing and idle arms is the swing animation the "
                + "harness drives",
                mode == Mode.PROXIMITY ? "skeleton" : "zombie", mode == Mode.PROXIMITY ? 10 : 2);
    }

    @Override
    public String name() {
        return switch (mode) {
            case SWING -> "bot_r1_swing";
            case PROJECTILE -> "bot_r1_projectile";
            case IDLE -> "bot_r1_idle";
            case PROXIMITY -> "bot_r1_proximity";
        };
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
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 3; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
        // A fresh world has observed nothing (6.2(c) 「보수적으로 가정하되, 관측되면 갱신한다」).
        ObservedRanged.clear();

        bot.survival().reset();
        bot.living().reset();
        bot.idle().reset();
        bot.planner().stop();
        bot.mover().stop();
        bot.getInventory().clearContent();
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        bot.setHealth(20.0F);
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();
        bot.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        bot.equipment().invalidate();

        int offset = (mode == Mode.PROXIMITY || mode == Mode.PROJECTILE) ? 10 : 2;
        mob = (mode == Mode.PROXIMITY || mode == Mode.PROJECTILE)
                ? ctx.env.spawn(EntityType.SKELETON, o.offset(offset, 0, 0))
                : ctx.env.spawn(EntityType.ZOMBIE, o.offset(offset, 0, 0));
        if (mob != null) {
            // No AI: the mob must not move, attack for real, or shoot. The only stimulus in the
            // swing arm is the swing animation the harness drives — nothing else differs between
            // the swing and idle arms.
            mob.setNoAi(true);
            mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(300.0);
            mob.setHealth(300.0F);
            mob.setInvulnerable(true);
        }

        r1Ticks = 0;
        blockingTicks = 0;
        attackMotionTicks = 0;
        observedRangedSeen = false;
        swingsIssued = 0;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            return true;
        }
        if (mode == Mode.SWING && mob != null && mob.isAlive() && !mob.swinging) {
            mob.swing(InteractionHand.MAIN_HAND);
            swingsIssued++;
        }
        // 7장의 두 번째 항 — 「투사체가 봇 히트박스로 향함」. The skeleton has NoAi and never shoots on
        // its own, so the harness fires FOR it: the arrow's owner is the skeleton, which is also what
        // 6.2's 「투사체 발사 관측」 reads. Re-fired periodically so the window is not one lucky tick.
        if (mode == Mode.PROJECTILE && mob != null && mob.isAlive() && ctx.elapsedTicks % 20 == 0) {
            net.minecraft.world.entity.projectile.Arrow arrow =
                    new net.minecraft.world.entity.projectile.Arrow(ctx.level,
                            mob.getX(), mob.getY() + 1.0, mob.getZ());
            arrow.setOwner(mob);
            double dx = bot.getX() - mob.getX();
            double dy = (bot.getY() + 1.0) - (mob.getY() + 1.0);
            double dz = bot.getZ() - mob.getZ();
            arrow.shoot(dx, dy, dz, 1.6F, 0.0F);
            ctx.level.addFreshEntity(arrow);
            swingsIssued++;   // reused as "stimuli issued"
        }
        if (bot.reflex().r1Fired()) {
            r1Ticks++;
        }
        if (bot.reflex().r1HadAttackMotion()) {
            attackMotionTicks++;
        }
        if (bot.isBlocking()) {
            blockingTicks++;
        }
        for (var t : bot.perception().targets) {
            if (t.observedRanged) {
                observedRangedSeen = true;
            }
        }
        return ctx.elapsedTicks >= RUN;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        boolean ok = switch (mode) {
            case SWING -> r1Ticks > 0 && attackMotionTicks > 0 && blockingTicks > 0;
            // ①의 두 번째 절반: 투사체가 실제로 봇을 향할 때의 발화.
            case PROJECTILE -> r1Ticks > 0 && observedRangedSeen;
            case IDLE -> r1Ticks == 0 && attackMotionTicks == 0 && blockingTicks == 0;
            case PROXIMITY -> r1Ticks == 0 && !observedRangedSeen;
        };

        LOGGER.info("[R1TEST] arm={} r1Ticks={} motionTicks={} blocking={} swings={} obsRanged={}",
                name(), r1Ticks, attackMotionTicks, blockingTicks, swingsIssued, observedRangedSeen);
        String measured = String.format(
                "arm:%s,mob:%s,dist:%d,r1FiredTicks:%d,attackMotionTicks:%d,blockingTicks:%d,"
                        + "stimuliIssued:%d,observedRanged:%b,runTicks:%d,r1FireRate:%.3f",
                mode.name().toLowerCase(),
                mob == null ? "none" : EntityType.getKey(mob.getType()).getPath(),
                (mode == Mode.PROXIMITY || mode == Mode.PROJECTILE) ? 10 : 2,
                r1Ticks, attackMotionTicks, blockingTicks, swingsIssued, observedRangedSeen, RUN,
                RUN == 0 ? 0.0 : (double) r1Ticks / RUN);
        String expected = switch (mode) {
            case SWING -> "적 공격 모션 in reach + shield held → R1 fires and the shield actually goes "
                    + "up (isBlocking observed)";
            case PROJECTILE -> "투사체가 봇 히트박스로 향함 → R1 fires, and 6.2's observation predicate "
                    + "flips observedRanged to true";
            case IDLE -> "same mob, same distance, no swing → R1 must not fire at all";
            case PROXIMITY -> "a skeleton 10 blocks away that has never fired → under 6.2's "
                    + "observation predicate R1 must not fire (the old class test fired here)";
        };
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    /** 적 공격 모션 — the case the old condition could never see. */
    public static class Swing extends BotR1TriggerTest {
        public Swing() {
            super(Mode.SWING);
        }
    }

    /** 투사체 자극 — 7장 조건의 두 번째 절반. */
    public static class Projectile extends BotR1TriggerTest {
        public Projectile() {
            super(Mode.PROJECTILE);
        }
    }

    /** Contrast: presence without a motion. */
    public static class Idle extends BotR1TriggerTest {
        public Idle() {
            super(Mode.IDLE);
        }
    }

    /** Regression: proximity to a shooter that has not shot. */
    public static class Proximity extends BotR1TriggerTest {
        public Proximity() {
            super(Mode.PROXIMITY);
        }
    }
}
