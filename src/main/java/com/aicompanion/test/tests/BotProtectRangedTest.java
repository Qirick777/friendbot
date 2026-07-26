package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.combat.BotProtection;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.aicompanion.test.TestUser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * 9.3 원거리 몹 대응 (유저 타격 시), verbatim:
 *
 * <pre>
 * 활 보유 시: 유저 곁을 지키며 활로 원거리 처치.
 *            유저에게 접근 중인 근접 위협이 있으면 그것부터 처치.
 * </pre>
 *
 * <ul>
 *   <li><b>guard</b> — bow held, a skeleton shooting the user from 14 blocks and nothing else. The
 *       bot must engage at RANGE and stay 「유저 곁」 — its distance to the user must not grow into
 *       a chase.</li>
 *   <li><b>meleefirst</b> — the same skeleton PLUS a zombie closing on the user. 9.3's second line
 *       says the melee threat is handled first, so the assigned target must be the zombie. The
 *       skeleton is still there and still shooting, so choosing the zombie can only come from the
 *       priority clause.</li>
 * </ul>
 */
public class BotProtectRangedTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int RUN = 100;
    private static final double SKELETON_DIST = 14.0;

    private final boolean withMeleeThreat;
    private ServerPlayer user;
    private Mob skeleton;
    private Mob zombie;
    private int pickedSkeleton;
    private int pickedZombie;
    private int rangedModeTicks;
    private double maxBotUserDist;
    private String rule = "none";

    protected BotProtectRangedTest(boolean withMeleeThreat) {
        this.withMeleeThreat = withMeleeThreat;
    }

    @Override
    public int[] arenaBounds() {
        return new int[]{-22, 22, -22, 22};
    }

    @Override
    public String scenarioSpec() {
        return String.format(
                "bow + 64 arrows + iron sword; user at full hp (>40%%, so 9.2 P1 and the clause "
                + "under test is 9.3); a 300hp NoAi skeleton targeting the user at %.0f blocks%s; "
                + "every mob target re-asserted each tick",
                SKELETON_DIST,
                withMeleeThreat ? ", plus a 300hp NoAi zombie 7 blocks from the user" : " and nothing else");
    }

    @Override
    public String name() {
        return withMeleeThreat ? "bot_protect_ranged_melee" : "bot_protect_ranged_guard";
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
        for (int dx = -18; dx <= 18; dx++) {
            for (int dz = -18; dz <= 18; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 3; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        bot.survival().reset();
        bot.living().reset();
        bot.idle().reset();
        bot.pickup().reset();
        bot.getInventory().clearContent();
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        bot.setHealth(20.0F);
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();
        bot.getInventory().add(new ItemStack(Items.BOW));
        bot.getInventory().add(new ItemStack(Items.ARROW, 64));
        bot.getInventory().add(new ItemStack(Items.IRON_SWORD));
        bot.equipment().invalidate();

        user = TestUser.spawn(ctx.server, ctx.level, new BlockPos(o.getX(), o.getY(), o.getZ() + 2));
        user.setInvulnerable(true);
        user.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        user.setHealth(20.0F);       // >40% → 9.2 P1, so 9.3 is the clause under test
        user.setDeltaMovement(Vec3.ZERO);

        skeleton = ctx.env.spawn(EntityType.SKELETON,
                o.offset((int) SKELETON_DIST, 0, 0));
        if (skeleton != null) {
            skeleton.setNoAi(true);
            skeleton.getAttribute(Attributes.MAX_HEALTH).setBaseValue(300.0);
            skeleton.setHealth(300.0F);
            skeleton.setTarget(user);
        }
        if (withMeleeThreat) {
            zombie = ctx.env.spawn(EntityType.ZOMBIE, o.offset(0, 0, 7));
            if (zombie != null) {
                zombie.setNoAi(true);
                zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(300.0);
                zombie.setHealth(300.0F);
                zombie.setTarget(user);
            }
        } else {
            zombie = null;
        }

        pickedSkeleton = 0;
        pickedZombie = 0;
        rangedModeTicks = 0;
        maxBotUserDist = 0;
        rule = "none";
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            return true;
        }
        if (skeleton != null && skeleton.isAlive()) {
            skeleton.setTarget(user);
        }
        if (zombie != null && zombie.isAlive()) {
            zombie.setTarget(user);
        }
        LivingEntity chosen = bot.protection().lastChosen();
        if (chosen == skeleton && chosen != null) {
            pickedSkeleton++;
        } else if (chosen == zombie && chosen != null) {
            pickedZombie++;
        }
        if (bot.protection().mode() == BotProtection.Mode.ENGAGE_RANGED) {
            rangedModeTicks++;
        }
        if (user != null) {
            maxBotUserDist = Math.max(maxBotUserDist, bot.position().distanceTo(user.position()));
        }
        rule = bot.protection().lastRule();
        return ctx.elapsedTicks >= RUN;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        boolean ok = withMeleeThreat
                ? (pickedZombie > 0 && pickedZombie > pickedSkeleton)
                // 「유저 곁을 지키며」: the bot must not walk out to the shooter 14 blocks away.
                : (pickedSkeleton > 0 && rangedModeTicks > 0 && maxBotUserDist < SKELETON_DIST - 4);

        LOGGER.info("[PROTECT-9.3] arm={} skel={} zomb={} rangedTicks={} maxUserDist={} rule={}",
                name(), pickedSkeleton, pickedZombie, rangedModeTicks,
                String.format("%.2f", maxBotUserDist), rule);
        String measured = String.format(
                "arm:%s,pickedSkeleton:%d,pickedZombie:%d,engageRangedTicks:%d,maxBotUserDist:%.2f,"
                        + "skeletonDist:%.0f,rule:%s,runTicks:%d",
                withMeleeThreat ? "meleeFirst" : "guard", pickedSkeleton, pickedZombie,
                rangedModeTicks, maxBotUserDist, SKELETON_DIST, rule, RUN);
        String expected = withMeleeThreat
                ? "bow held + a zombie closing on the user → 「유저에게 접근 중인 근접 위협이 있으면 "
                        + "그것부터」: the assigned target is the zombie, not the shooter"
                : "bow held + only a shooter at 14 → 「유저 곁을 지키며 활로 원거리 처치」: mode is "
                        + "ENGAGE_RANGED and the bot never leaves the user to chase";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    /** Only a shooter: guard the user and shoot back. */
    public static class Guard extends BotProtectRangedTest {
        public Guard() {
            super(false);
        }
    }

    /** A melee threat as well: it goes first. */
    public static class MeleeFirst extends BotProtectRangedTest {
        public MeleeFirst() {
            super(true);
        }
    }
}
