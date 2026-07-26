package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.aicompanion.test.TestUser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * 9.2 타겟 우선순위 — the band the existing protection harnesses never exercised, verbatim:
 *
 * <pre>
 * 유저 체력 &gt; 40%:
 *    → 가장 위험한 적 우선 타격
 * 유저 체력 ≤ 40% AND 봇 위급 아님:
 *    → 유저를 노리는 적부터 처치
 * </pre>
 *
 * <p>Both arms place the SAME two mobs: a high-DPS zombie that is not after the user, and a weak
 * zombie that is. Only the user's health differs, so the measured value — which mob the bot picked
 * — isolates the band switch and nothing else.</p>
 *
 * <p>Judged by the assigned combat target, not by the rule string: the rule label is the bot's own
 * account of itself, and 유형 #10 is precisely the case where that account is right and the action
 * is not. The rule string is logged alongside for diagnosis only.</p>
 */
public class BotProtectLowUserTest implements BotTest {

    private static final int RUN = 80;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final boolean userLow;
    private ServerPlayer user;
    private Zombie bigDps;      // dangerous, ignores the user
    private Zombie userHunter;  // weak, targets the user
    private int pickedHunter;
    private int pickedBig;
    private String rule = "none";

    protected BotProtectLowUserTest(boolean userLow) {
        this.userLow = userLow;
    }

    @Override
    public int[] arenaBounds() {
        return new int[]{-16, 16, -16, 16};
    }

    @Override
    public String scenarioSpec() {
        return String.format(
                "user hp %s; bot 20hp (NOT critical, which is the band's second condition); two "
                + "200hp NoAi zombies both inside 9.4's 10-block invade radius — one with 18 attack "
                + "that ignores the user, one with 2 attack that targets the user, both re-asserted "
                + "every tick; iron sword only (no bow), so 9.3 cannot reroute the choice",
                userLow ? "6/20 = 30% (≤40 band)" : "20/20 = 100% (>40 band)");
    }

    @Override
    public String name() {
        return userLow ? "bot_protect_lowuser" : "bot_protect_highuser";
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

        bot.survival().reset();
        bot.living().reset();
        bot.idle().reset();
        bot.pickup().reset();
        bot.getInventory().clearContent();
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        bot.setHealth(20.0F);                       // 봇 위급 아님 — the band's second condition
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();
        bot.getInventory().add(new ItemStack(Items.IRON_SWORD));
        bot.equipment().invalidate();

        user = TestUser.spawn(ctx.server, ctx.level, new BlockPos(o.getX(), o.getY(), o.getZ() + 3));
        user.setInvulnerable(true);
        user.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        user.setHealth(userLow ? 6.0F : 20.0F);     // 30% vs 100% — the only difference
        user.setDeltaMovement(Vec3.ZERO);

        // Both inside 9.4's 10-block invade radius so both are engageable in both arms.
        bigDps = ctx.env.spawn(EntityType.ZOMBIE, o.offset(5, 0, 0));
        if (bigDps != null) {
            bigDps.setNoAi(true);
            bigDps.setCustomName(net.minecraft.network.chat.Component.literal("bigdps"));
            bigDps.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0);
            bigDps.setHealth(200.0F);
            bigDps.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(18.0);
            bigDps.setTarget(null);                 // not after the user
        }
        userHunter = ctx.env.spawn(EntityType.ZOMBIE, o.offset(0, 0, 6));
        if (userHunter != null) {
            userHunter.setNoAi(true);
            userHunter.setCustomName(net.minecraft.network.chat.Component.literal("hunter"));
            userHunter.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0);
            userHunter.setHealth(200.0F);
            userHunter.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(2.0);
            userHunter.setTarget(user);             // 유저를 노리는 적
        }

        pickedHunter = 0;
        pickedBig = 0;
        rule = "none";
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            return true;
        }
        // NoAi mobs drop their target; re-assert the premise every tick so the scenario holds.
        if (userHunter != null && userHunter.isAlive()) {
            userHunter.setTarget(user);
        }
        if (bigDps != null && bigDps.isAlive()) {
            bigDps.setTarget(null);
        }
        if (user != null) {
            user.setHealth(userLow ? 6.0F : 20.0F);
        }
        var chosen = bot.protection().lastChosen();
        if (chosen != null) {
            if (chosen == userHunter) {
                pickedHunter++;
            } else if (chosen == bigDps) {
                pickedBig++;
            }
        }
        rule = bot.protection().lastRule();
        return ctx.elapsedTicks >= RUN;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        boolean ok = userLow ? (pickedHunter > pickedBig && pickedHunter > 0)
                : (pickedBig > pickedHunter && pickedBig > 0);

        LOGGER.info("[PROTECT-BAND] arm={} hunter={} big={} rule={}",
                name(), pickedHunter, pickedBig, rule);
        String measured = String.format(
                "arm:%s,userHpPct:%d,pickedUserHunter:%d,pickedMaxDps:%d,rule:%s,"
                        + "hunterAtk:2.0,bigDpsAtk:18.0,runTicks:%d",
                userLow ? "userLow" : "userHigh", userLow ? 30 : 100, pickedHunter, pickedBig,
                rule, RUN);
        String expected = userLow
                ? "user at 30% (≤40) and bot not critical → 「유저를 노리는 적부터 처치」: the weak "
                        + "hunter is engaged even though the other zombie has 9× its attack"
                : "user at 100% (>40) → 「가장 위험한 적 우선 타격」: the 18-damage zombie is engaged "
                        + "even though the other one is after the user";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    /** 유저 체력 ≤ 40%. */
    public static class Low extends BotProtectLowUserTest {
        public Low() {
            super(true);
        }
    }

    /** 유저 체력 > 40% — the contrast. */
    public static class High extends BotProtectLowUserTest {
        public High() {
            super(false);
        }
    }
}
