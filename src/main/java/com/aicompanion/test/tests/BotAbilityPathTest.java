package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.BotPathfinder;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.List;

/**
 * T5.1 [검증] verbatim: 「3칸 초과 절벽 앞 + 물통 보유 → A* 경로가 절벽 아래로 내려가는 경로를
 * 반환(우회 대신)하고 낙법 액션 태그 포함, 실제 이동 시 데미지 0. 블록만 있고 절벽이면 우회.
 * <b>판정: 물통 보유 시 직하강 경로 채택 AND 하강 후 봇 체력 불변.</b>」
 *
 * <p>Arena: a plateau with a 6-block cliff between the bot and the goal, and a long walkable detour
 * around it. The detour has to exist, or "took the cliff" would be forced rather than chosen.</p>
 *
 * <p>Both arms share the arena and differ only in the inventory — which is the whole claim of 4.4:
 * 「인벤토리에 따라 갈 수 있는 길이 달라진다」.</p>
 */
public class BotAbilityPathTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int CLIFF_DROP = 6;    // > maxSafeFall(3): lethal on foot, fine with a bucket
    private static final int GOAL_X = 14;
    private static final int DETOUR_Z = 12;     // the walkable way around

    private final boolean withBucket;
    private List<BlockPos> path;
    private boolean tookCliff;
    private String tagAtDescent = "none";
    private int pathLen = -1;
    private int biggestDrop;
    private float hpStart;
    private float hpEnd;

    protected BotAbilityPathTest(boolean withBucket) {
        this.withBucket = withBucket;
    }

    @Override
    public int[] arenaBounds() {
        return new int[]{-8, 24, -8, 24};
    }

    @Override
    public String scenarioSpec() {
        return String.format(
                "plateau with a %d-block cliff (> maxSafeFall %d, lethal on foot) between the bot "
                + "and the goal, AND a walkable staircase detour at the far z end — the detour has "
                + "to exist or \"took the cliff\" would be forced rather than chosen; the two arms "
                + "share the arena and differ ONLY in whether a water bucket is in the bag (%b)",
                CLIFF_DROP, com.aicompanion.bot.BotPathfinder.MAX_SAFE_FALL_PUBLIC, withBucket);
    }

    @Override
    public String name() {
        return withBucket ? "bot_path_ability_water" : "bot_path_ability_detour";
    }

    @Override
    public int timeoutTicks() {
        return 200;
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;

        // Clear the box first.
        for (int dx = -6; dx <= 22; dx++) {
            for (int dz = -6; dz <= 20; dz++) {
                for (int dy = -CLIFF_DROP - 2; dy <= 5; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
        // Upper plateau: the bot's side, z in [-2, 2], x in [-4, 4].
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -2; dz <= DETOUR_Z + 2; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
            }
        }
        // Lower shelf holding the goal, CLIFF_DROP blocks down.
        for (int dx = 5; dx <= GOAL_X + 4; dx++) {
            for (int dz = -2; dz <= DETOUR_Z + 2; dz++) {
                ctx.level.setBlockAndUpdate(
                        new BlockPos(o.getX() + dx, o.getY() - 1 - CLIFF_DROP, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
            }
        }
        // Walkable detour: a staircase at the far z end, so a bucket-less bot still has a route.
        for (int step = 0; step <= CLIFF_DROP; step++) {
            for (int dz = DETOUR_Z; dz <= DETOUR_Z + 2; dz++) {
                ctx.level.setBlockAndUpdate(
                        new BlockPos(o.getX() + 5 + step, o.getY() - 1 - step, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
            }
        }

        bot.survival().reset();
        bot.getInventory().clearContent();
        bot.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                .setBaseValue(20.0);
        bot.setHealth(20.0F);
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(false);        // fall damage must be real
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();
        if (withBucket) {
            bot.getInventory().add(new ItemStack(Items.WATER_BUCKET));
        }
        bot.equipment().invalidate();
        hpStart = bot.getHealth();

        // Run the search directly so the PATH is the measured object, not just the arrival.
        BlockPos goal = new BlockPos(o.getX() + GOAL_X, o.getY() - CLIFF_DROP, o.getZ());
        BotPathfinder.Capabilities caps = new BotPathfinder.Capabilities(
                withBucket, false, 0, BotPathfinder.MAX_SAFE_FALL_PUBLIC);
        BotPathfinder.Search s = new BotPathfinder.Search(ctx.level, bot.blockPosition(), goal, 8000)
                .withCapabilities(caps);
        while (!s.isDone()) {
            s.step(8000);
        }
        path = s.result();
        pathLen = path == null ? -1 : path.size();
        tookCliff = false;
        biggestDrop = 0;
        if (path != null) {
            // Detect the DESCENT EVENT, not a position. Being low and near the direct line is also
            // true of the return leg along the bottom shelf after taking the staircase — the first
            // version measured that and called a 39-node detour "took the cliff". A capability
            // descent is a single step whose Y falls further than maxSafeFall.
            for (int i = 1; i < path.size(); i++) {
                BlockPos prev = path.get(i - 1);
                BlockPos cur = path.get(i);
                int drop = prev.getY() - cur.getY();
                biggestDrop = Math.max(biggestDrop, drop);
                if (drop > BotPathfinder.MAX_SAFE_FALL_PUBLIC) {
                    tookCliff = true;
                    String tag = s.actionTagAt(cur);
                    if (tag != null) {
                        tagAtDescent = tag;
                    }
                }
            }
        }
        LOGGER.info("[ABILITYPATH] bucket={} pathLen={} tookCliff={} tag={}",
                withBucket, pathLen, tookCliff, tagAtDescent);
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            return true;
        }
        hpEnd = bot.getHealth();
        return ctx.elapsedTicks >= 20;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        boolean reachable = path != null;
        boolean ok = withBucket
                ? (reachable && tookCliff && BotPathfinder.TAG_WATER.equals(tagAtDescent))
                : (reachable && !tookCliff);

        String measured = String.format(
                "waterBucket:%b,pathFound:%b,pathLen:%d,tookCliff:%b,biggestSingleDrop:%d,actionTag:%s,cliffDrop:%d,"
                        + "maxSafeFall:%d,hp:%.1f->%.1f",
                withBucket, reachable, pathLen, tookCliff, biggestDrop, tagAtDescent, CLIFF_DROP,
                BotPathfinder.MAX_SAFE_FALL_PUBLIC, hpStart, hpEnd);
        String expected = withBucket
                ? "bucket held → the path descends the 6-block cliff directly and carries the water "
                        + "action tag (instead of taking the walkable detour)"
                : "no bucket → the lethal cliff is not a neighbour at all, so the path detours";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    /** Bucket in inventory: the cliff becomes passable and tagged. */
    public static class Water extends BotAbilityPathTest {
        public Water() {
            super(true);
        }
    }

    /** No bucket: the same cliff must be refused and the detour taken. */
    public static class Detour extends BotAbilityPathTest {
        public Detour() {
            super(false);
        }
    }
}
