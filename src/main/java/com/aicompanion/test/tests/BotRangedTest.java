package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * T3.4 ranged check: the bot bow-fights a high-HP dummy that moves in a straight line at a
 * constant speed PERPENDICULAR to the line of sight — fast enough (0.15/tick) that a naive
 * fire-at-current-position shot misses, so hits require the predictive lead. PASS iff, over the
 * window, the hit rate (health-drop events / arrows fired) meets the design threshold (≥ 80%),
 * judged purely by the target's measured health drops vs the bot's fired-arrow count.
 */
public class BotRangedTest implements BotTest {

    private static final double SPEED = 0.15;   // dummy speed (blocks/tick) — needs real lead
    private static final int LANE = 10;         // dummy lane half-length along Z (bounces at ±LANE)
    private static final int DIST = 14;         // dummy stand-off distance along +X

    private Zombie dummy;
    private double baseZ;
    private double zPos;
    private int zDir = 1;
    private double prevHp;
    private double totalDrop;
    private int hits;

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
        return "bot_ranged";
    }

    @Override
    public int timeoutTicks() {
        return 700;
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return NO_BUILD;
    }

    /** P-6 (유형 #9): 이 판정이 측정된 세계의 전제. */
    @Override
    public String scenarioSpec() {
        return "setBaseValue(MAX_HEALTH) 호출값: 4000.0 (봇/표적 구분은 setup() 참조). bot.setInvulnerable(true). 스폰 "
                + "몹: zombie. 관측 700틱, 트라이얼 3회. 시공 범위 선언: 없음(NO_BUILD) — 블록 판정 영역이 비어 있고 엔티티/봇 상태만 판정한다. 유저 "
                + "없음(TestUser.spawn 미호출) → Perception.java:131이 봇 외 플레이어를 찾지 못해 user==null → "
                + "BotIdle.java:87-89 즉시 반환. 16장 자율 이동 비활성. ";
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        // Night, so the undead dummy does not burn in daylight (sunlight fire would drop its HP and
        // be miscounted as arrow hits).
        ctx.level.setDayTime(18000L);

        // Clear a wide flat arena covering the bot and the dummy's whole lane.
        for (int dx = -3; dx <= DIST + 3; dx++) {
            for (int dz = -LANE - 3; dz <= LANE + 3; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 3; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.setInvulnerable(true);
        bot.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        bot.getInventory().add(new ItemStack(Items.ARROW, 64)); // plenty for the whole window

        // High-HP, knockback-immune, no-AI dummy. We drive its position every tick, so its own
        // physics / knockback never matter; it just serves as a constant-velocity linear target.
        baseZ = o.getZ() + 0.5;
        zPos = baseZ;
        zDir = 1;
        dummy = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + DIST, o.getY(), o.getZ()));
        if (dummy != null) {
            dummy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(4000.0);
            dummy.setHealth(4000.0F);
            dummy.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
            dummy.getAttribute(Attributes.ARMOR).setBaseValue(0.0);
            dummy.setNoAi(true);
            dummy.setNoGravity(true);
            dummy.moveTo(o.getX() + DIST + 0.5, o.getY(), zPos, 0.0F, 0.0F);
        }

        prevHp = dummy != null ? dummy.getHealth() : 0;
        totalDrop = 0;
        hits = 0;

        bot.rangedCombat().setTarget(dummy);
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        // Move the dummy in a straight line at constant speed, bouncing at the lane ends.
        if (dummy != null) {
            zPos += SPEED * zDir;
            if (zPos > baseZ + LANE) {
                zPos = baseZ + LANE;
                zDir = -1;
            } else if (zPos < baseZ - LANE) {
                zPos = baseZ - LANE;
                zDir = 1;
            }
            dummy.moveTo(ctx.origin.getX() + DIST + 0.5, ctx.origin.getY(), zPos, 0.0F, 0.0F);
            dummy.setDeltaMovement(Vec3.ZERO);

            double hp = dummy.getHealth();
            double drop = prevHp - hp;
            if (drop > 0.01) {
                hits++;
                totalDrop += drop;
            }
            prevHp = hp;
        }
        return ctx.elapsedTicks >= 680;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        int shots = bot != null ? bot.rangedCombat().shotsFired() : 0;
        double hitRate = shots > 0 ? (double) hits / shots : 0.0;

        boolean enoughShots = shots >= 5;             // meaningful sample
        boolean accurate = hitRate >= 0.80;           // design threshold
        boolean ok = enoughShots && accurate;

        String measured = String.format("shots:%d,hits:%d,hitRate:%.2f,hpDrop:%.1f",
                shots, hits, hitRate, totalDrop);
        String expected = "shots>=5 AND hits/shots>=0.80 (predictive lead on a 0.15/tick moving target)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
