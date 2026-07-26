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
 * A'3 — makes "forced ranged with no ranged weapon" a measured value instead of a silent state.
 *
 * <p>The target is deliberately unwinnable, so rule 2 says {@code allowMelee=false} in both arms.
 * What differs is whether the bot owns a bow:</p>
 * <ul>
 *   <li><b>armed</b> — bow present ⇒ rule 2 downgrades to ranged, and the bot still damages the
 *       threat. Rule 2 chose the tactic.</li>
 *   <li><b>unarmed</b> — no bow ⇒ "forced ranged" would mean "do nothing", so intervention must NOT
 *       be gated on rule 2 and the bot engages in melee anyway. Measured before this decision:
 *       {@code aggroDrop 97.4 -> 0.0} with {@code mode:ENGAGE_RANGED} — the bot stood still while
 *       the user was hit.</li>
 * </ul>
 *
 * <p>Judged on the threat's health loss, i.e. the bot actually did something to the thing attacking
 * the user, in both arms.</p>
 */
public class BotProtectArmedTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int RUN = 200;

    private final boolean armed;
    private Zombie threat;
    private ServerPlayer user;
    private float threatHpStart;
    private float threatHpEnd;
    private String modeSeen = "?";
    private double minDist = Double.MAX_VALUE;

    protected BotProtectArmedTest(boolean armed) {
        this.armed = armed;
    }

    @Override
    public int[] arenaBounds() {
        return new int[]{-20, 20, -20, 20};
    }

    @Override
    public String name() {
        return armed ? "bot_protect_armed" : "bot_protect_unarmed";
    }

    @Override
    public int timeoutTicks() {
        return RUN + 60;
    }

    /** Q-7: P-6 자동 생성이 이름을 삼항연산으로 만드는 클래스를 통째로 건너뛰었다. 그 구멍을 메운다. */
    @Override
    public String scenarioSpec() {
        return "9장 유저 보호. 봇 최대체력 20, bot.setInvulnerable(true) — 판정 대상은 교전 결과가 아니라 "
                + "대상 선택이다. 유저는 invulnerable·최대체력 20, 위협 좀비는 최대체력 400(창 안에 죽지 않도록). "
                + "TestUser 있음 → 16장 자율 이동이 마지막 else에서 돌 수 있다. idleCommandedTicks로 값 확인.";
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
        if (armed) {
            bot.getInventory().add(new ItemStack(Items.BOW));
            bot.getInventory().add(new ItemStack(Items.ARROW, 64));
        }
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        bot.setHealth(20.0F);
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();
        bot.equipment().invalidate();

        user = TestUser.spawn(ctx.server, ctx.level, new BlockPos(o.getX(), o.getY(), o.getZ() + 3));
        user.setInvulnerable(true);
        user.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        user.setHealth(20.0F);

        // Unwinnable by rule 2's arithmetic in BOTH arms: 400 hp, hits hard.
        threat = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + 6, o.getY(), o.getZ() + 3));
        if (threat != null) {
            threat.setPersistenceRequired();
            threat.getAttribute(Attributes.MAX_HEALTH).setBaseValue(400.0);
            threat.setHealth(400.0F);
            threat.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(20.0);
            threat.setTarget(user);
            threatHpStart = threat.getHealth();
        }
        modeSeen = "?";
        minDist = Double.MAX_VALUE;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || threat == null || !threat.isAlive()) {
            return true;
        }
        threat.setTarget(user);
        BotProtection.Mode m = bot.protection().mode();
        if (m != null) {
            modeSeen = m.name();
        }
        minDist = Math.min(minDist,
                Math.hypot(threat.getX() - bot.getX(), threat.getZ() - bot.getZ()));
        threatHpEnd = threat.getHealth();
        return ctx.elapsedTicks >= RUN;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        double drop = threatHpStart - threatHpEnd;
        boolean acted = drop > 0.01;
        boolean ok = acted;   // both arms: the bot must do SOMETHING to the user's attacker

        LOGGER.info("[PROTECT-ARMED] armed={} mode={} threatDrop={} minDist={}",
                armed, modeSeen, String.format("%.1f", drop), String.format("%.2f", minDist));
        String measured = String.format("armed:%b,mode:%s,threatHpDrop:%.1f,minDist:%.2f",
                armed, modeSeen, drop, minDist);
        String expected = armed
                ? "rule 2 denies melee but a bow exists → engage at range; threat takes damage"
                : "rule 2 denies melee and there is NO ranged option → intervene anyway; "
                        + "threat takes damage (intervention is not gated on winning)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    public static class Armed extends BotProtectArmedTest {
        public Armed() {
            super(true);
        }
    }

    public static class Unarmed extends BotProtectArmedTest {
        public Unarmed() {
            super(false);
        }
    }
}
