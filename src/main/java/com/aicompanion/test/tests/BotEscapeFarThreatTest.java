package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.aicompanion.test.TestUser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * T4.5 escape CONTROL for the threat-proximity gate: the user IS critical (≤15%) with no throwable
 * potion, but the only threat sits OUTSIDE the release line (&gt;18 blocks). The kidnap-escape must
 * NOT trigger — 도주 is "flee a threat", and without a nearby threat there is nothing to flee, which
 * is also what stops the mount↔dismount oscillation. PASS iff getVehicle() stays null.
 *
 * <p>Note: the THREAT(12)/SAFE(18) distances are implementation-chosen tuning values, not design
 * numbers — see AI_Bot_Design.md ch.18 파라미터 총람.</p>
 */
public class BotEscapeFarThreatTest implements BotTest {

    private static final int THREAT_X = 25; // > SAFE_ESCAPE_DIST (18) → outside the release line

    private Zombie enemy;
    private ServerPlayer user;
    private boolean everMounted;
    private double minThreatDist = Double.MAX_VALUE;

    @Override
    public String name() {
        return "bot_escape_farthreat";
    }

    @Override
    public int timeoutTicks() {
        return 140;
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return new int[]{-8, THREAT_X + 4, -4, 4};
    }

    /** P-6 (유형 #9): 이 판정이 측정된 세계의 전제. */
    @Override
    public String scenarioSpec() {
        return "setBaseValue(MAX_HEALTH) 호출값: 20.0 (봇/표적 구분은 setup() 참조). bot.setInvulnerable(true). 스폰 몹: "
                + "zombie. 관측 140틱, 트라이얼 1회. 시공 범위 선언: {-8, THREAT_X + 4, -4, 4}. TestUser 있음 → 16장 자율 이동이 "
                + "마지막 else에서 돌 수 있다. idleCommandedTicks로 값 확인. ";
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);
        for (int dx = -8; dx <= THREAT_X + 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 3; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        user = TestUser.spawn(ctx.server, ctx.level, o);
        user.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        user.setHealth(2.0F);        // 10% — critical, same as bot_escape_ride
        user.setInvulnerable(true);

        bot.survival().reset();
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() - 1.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.getInventory().clearContent(); // no throwable potion — only the distance gate differs
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        // Threat parked far outside the release line; stationary so the distance stays > 18.
        enemy = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + THREAT_X, o.getY(), o.getZ()));
        if (enemy != null) {
            enemy.setNoAi(true);
        }
        everMounted = false;
        minThreatDist = Double.MAX_VALUE;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        if (user != null && user.getVehicle() != null) {
            everMounted = true;
        }
        if (user != null && enemy != null) {
            minThreatDist = Math.min(minThreatDist, enemy.position().distanceTo(user.position()));
        }
        return ctx.elapsedTicks >= 120;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        float hp = user != null ? user.getHealth() : -1;
        float max = user != null ? user.getMaxHealth() : 1;
        boolean threatStayedFar = minThreatDist > 18.0;
        boolean ok = !everMounted && threatStayedFar;
        String measured = String.format("everMounted:%b,userHp:%.0f%%,threatDist:%.1f",
                everMounted, (hp / max) * 100, minThreatDist);
        String expected = "user<=15% but threat outside release line (>18) → no escape mount";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
