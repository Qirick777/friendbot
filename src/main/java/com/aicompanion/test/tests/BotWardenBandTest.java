package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.combat.Layer2Registry;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * T4.6 (2a) — NON-charging band keeping. The bot ranged-fights a warden that never charges (its
 * sonic cooldown is kept alive), and must hold the rule-3 band 16~20. This is the counterpart that
 * makes (2b)'s "distance increased" meaningful: without it, any retreat would look like the reflex.
 *
 * <p>PASS iff, over the observation window, the bot–warden distance stays inside the band (with a
 * small settling tolerance) AND no charge was ever observed during it.</p>
 */
public class BotWardenBandTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double BAND_MIN = 16.0;
    private static final double BAND_MAX = 20.0;
    private static final double TOL = 1.5;      // settling tolerance around the band
    private static final int SETTLE = 60;       // ticks allowed to reach the band first

    private Warden warden;
    private double minDist = Double.MAX_VALUE;
    private double maxDist = -1;
    private boolean chargeSeenDuringWindow;

    @Override
    public String name() {
        return "bot_warden_band";
    }

    @Override
    public int timeoutTicks() {
        return 300;
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return new int[]{-40, 30, -6, 6};
    }

    /** P-6 (유형 #9): 이 판정이 측정된 세계의 전제. */
    @Override
    public String scenarioSpec() {
        return "setBaseValue(MAX_HEALTH) 호출값: 20.0 (봇/표적 구분은 setup() 참조). bot.setInvulnerable(true). 스폰 몹: "
                + "warden. 관측 300틱, 트라이얼 1회. 시공 범위 선언: {-40, 30, -6, 6}. 유저 없음(TestUser.spawn 미호출) → "
                + "Perception.java:131이 봇 외 플레이어를 찾지 못해 user==null → BotIdle.java:87-89 즉시 반환. 16장 자율 이동 비활성. ";
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L);
        Layer2Registry.clearAllOverrides();

        for (int dx = -40; dx <= 30; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 4; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        bot.survival().reset();
        bot.getInventory().clearContent();
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        bot.setHealth(20.0F);
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        bot.getInventory().add(new ItemStack(Items.ARROW, 64));
        bot.meleeCombat().stop();

        // Warden parked at 18 (inside the band). Immobile so the distance reflects the BOT's
        // band-keeping only, and never charging (cooldown refreshed every tick in tick()).
        warden = ctx.env.spawn(EntityType.WARDEN, new BlockPos(o.getX() + 18, o.getY(), o.getZ()));
        if (warden != null) {
            warden.setInvulnerable(true);
            warden.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0);
            warden.setNoAi(true); // no brain ticking → no sonic boom at all
        }
        bot.rangedCombat().setTarget(warden);
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || warden == null) {
            return true;
        }
        if (bot.perception().targets.stream()
                .anyMatch(t -> t.entity == warden && t.layer2Profile.isCharging(warden))) {
            chargeSeenDuringWindow = true;
        }
        if (ctx.elapsedTicks % 40 == 0) {
            LOGGER.info("[WARDEN] banddiag t={} bot=({},{}) warden=({},{}) dist={} zza={} rangedTarget={}",
                    ctx.elapsedTicks, String.format("%.2f", bot.getX()), String.format("%.2f", bot.getZ()),
                    String.format("%.2f", warden.getX()), String.format("%.2f", warden.getZ()),
                    String.format("%.2f", Math.hypot(bot.getX() - warden.getX(), bot.getZ() - warden.getZ())),
                    bot.zza, bot.rangedCombat().target() != null);
        }
        if (ctx.elapsedTicks > SETTLE) {
            double d = Math.hypot(bot.getX() - warden.getX(), bot.getZ() - warden.getZ());
            minDist = Math.min(minDist, d);
            maxDist = Math.max(maxDist, d);
        }
        return ctx.elapsedTicks >= 280;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        boolean held = minDist >= BAND_MIN - TOL && maxDist <= BAND_MAX + TOL;
        boolean ok = held && !chargeSeenDuringWindow;
        LOGGER.info("[WARDEN] band keeping min={} max={} chargeSeen={}",
                String.format("%.2f", minDist), String.format("%.2f", maxDist), chargeSeenDuringWindow);
        String measured = String.format("dist min:%.2f max:%.2f,band:%.1f~%.1f,tol:%.1f,chargeSeen:%b",
                minDist, maxDist, BAND_MIN, BAND_MAX, TOL, chargeSeenDuringWindow);
        String expected = "non-charging: distance stays within 16~20 (±1.5) AND no charge observed";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
