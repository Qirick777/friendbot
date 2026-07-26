package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.combat.BotSurvival;
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
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * The two heal paths that had code but no harness.
 *
 * <ul>
 *   <li><b>bot_survival_heal</b> — design ch.8: 「hp &lt; 전투 중 회복 경계선 + 회복 아이템 보유 →
 *       회복」. Bot at 40% with a golden apple and an enemy engaged; its own health must RISE.</li>
 *   <li><b>bot_survival_nopotion</b> — the contrast: identical state, empty bag. Health must not
 *       rise, which is also what proves the rise above came from the item and not from vanilla
 *       regeneration (food is held at 10, below vanilla's regen floor of 18, in both arms).</li>
 *   <li><b>bot_rescue_heal</b> — design 9.2: 「유저 체력 ≤ 15%(치명선) AND 회복 물약 보유 → 유저에게
 *       즉각 회복(발밑에 투척 회복 포션)」. The USER's health must rise and a splash potion must
 *       leave the bot's inventory.</li>
 *   <li><b>bot_rescue_nopotion</b> — the contrast: user equally critical, no potion. 9.2's next
 *       line is 「즉각 도주 모드」, so the user's health must not rise and the bot must not be in
 *       HEAL_USER.</li>
 * </ul>
 */
public class BotHealTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int RUN = 200;
    /** Vanilla regenerates only at food ≥ 18; holding it below that keeps the arms clean. */
    private static final int NO_REGEN_FOOD = 10;

    private final Mode mode;
    private final boolean withItem;
    private ServerPlayer user;
    private Zombie zombie;
    private float hpStart;
    private float hpMax;
    private float userHpStart;
    private float userHpMax;
    private int potionsBefore;
    private int potionsAfter;
    private int applesBefore;
    private int applesAfter;
    private String protMode = "none";
    private String survMode = "NONE";

    public enum Mode { SELF, USER }

    protected BotHealTest(Mode mode, boolean withItem) {
        this.mode = mode;
        this.withItem = withItem;
    }

    @Override
    public int[] arenaBounds() {
        return new int[]{-16, 16, -16, 16};
    }

    @Override
    public String scenarioSpec() {
        return String.format(
                "bot food held at %d — BELOW vanilla's regeneration floor of 18, so any health rise "
                + "must come from an item; bot invulnerable (the arms measure healing, not damage "
                + "exchange); bot hp %s, user hp %s; heal item: %s; a 300hp NoAi zombie targets the "
                + "user so 9.1 intervenes at all",
                NO_REGEN_FOOD, mode == Mode.SELF ? "8/20 = 40%" : "20/20",
                mode == Mode.USER ? "2/20 = 10% (≤15% 치명선)" : "20/20",
                withItem ? (mode == Mode.SELF ? "2 golden apples" : "1 splash potion of healing")
                        : "NONE (the contrast)");
    }

    @Override
    public String name() {
        if (mode == Mode.SELF) {
            return withItem ? "bot_survival_heal" : "bot_survival_nopotion";
        }
        return withItem ? "bot_rescue_heal" : "bot_rescue_nopotion";
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
        bot.getFoodData().setFoodLevel(NO_REGEN_FOOD);
        bot.getFoodData().setSaturation(0.0F);
        bot.setInvulnerable(true);       // the arms measure HEALING, not damage exchange
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        if (mode == Mode.SELF) {
            bot.setHealth(8.0F);         // 40% — below GUARD_LINE(55%), above nothing else
            if (withItem) {
                bot.getInventory().add(new ItemStack(Items.GOLDEN_APPLE, 2));
            }
        } else {
            bot.setHealth(20.0F);        // the bot is fine; the USER is the critical one
            if (withItem) {
                ItemStack splash = new ItemStack(Items.SPLASH_POTION);
                PotionUtils.setPotion(splash, Potions.HEALING);
                bot.getInventory().add(splash);
            }
        }
        bot.equipment().invalidate();

        user = TestUser.spawn(ctx.server, ctx.level, new BlockPos(o.getX() + 2, o.getY(), o.getZ()));
        user.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        user.setInvulnerable(false);     // a splash potion must be able to affect it
        user.setDeltaMovement(Vec3.ZERO);
        user.setHealth(mode == Mode.USER ? 2.0F : 20.0F);   // 10% = below 15% 치명선

        // A threat has to be present or 9.1 never intervenes and 8's combat-heal band never applies.
        zombie = ctx.env.spawn(EntityType.ZOMBIE, o.offset(6, 0, 0));
        if (zombie != null) {
            zombie.setNoAi(true);
            zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(300.0);
            zombie.setHealth(300.0F);
            zombie.setInvulnerable(true);
            zombie.setTarget(user);
        }

        hpStart = bot.getHealth();
        hpMax = hpStart;
        userHpStart = user.getHealth();
        userHpMax = userHpStart;
        potionsBefore = count(bot, Items.SPLASH_POTION);
        applesBefore = count(bot, Items.GOLDEN_APPLE);
        potionsAfter = potionsBefore;
        applesAfter = applesBefore;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null || user == null) {
            return true;
        }
        if (zombie != null && zombie.isAlive()) {
            zombie.setTarget(user);      // NoAi drops the target; hold the premise
        }
        // The fake user is not ticked by anything, so drive it — otherwise a splash potion's effect
        // is applied and then never advanced, and the heal would not show up in getHealth().
        user.doTick();
        hpMax = Math.max(hpMax, bot.getHealth());
        userHpMax = Math.max(userHpMax, user.getHealth());
        potionsAfter = count(bot, Items.SPLASH_POTION);
        applesAfter = count(bot, Items.GOLDEN_APPLE);
        protMode = bot.protection().mode().name();
        survMode = bot.survival().mode().name();
        return ctx.elapsedTicks >= RUN;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        float botGain = hpMax - hpStart;
        float userGain = userHpMax - userHpStart;
        boolean ok;
        if (mode == Mode.SELF) {
            ok = withItem ? (botGain > 0.5F && applesAfter < applesBefore)
                    : (botGain <= 0.01F);
        } else {
            ok = withItem ? (userGain > 0.5F && potionsAfter < potionsBefore)
                    : (userGain <= 0.01F && !"HEAL_USER".equals(protMode));
        }

        LOGGER.info("[HEAL-TEST] arm={} botHp={}→{} userHp={}→{} apples={}→{} potions={}→{} prot={} surv={}",
                name(), hpStart, hpMax, userHpStart, userHpMax, applesBefore, applesAfter,
                potionsBefore, potionsAfter, protMode, survMode);
        String measured = String.format(
                "arm:%s,botHp:%.1f->%.1f,botGain:%.1f,userHp:%.1f->%.1f,userGain:%.1f,"
                        + "apples:%d->%d,potions:%d->%d,protMode:%s,survMode:%s,food:%d,guardLine:%.2f",
                name(), hpStart, hpMax, botGain, userHpStart, userHpMax, userGain,
                applesBefore, applesAfter, potionsBefore, potionsAfter, protMode, survMode,
                NO_REGEN_FOOD, BotSurvival.GUARD_LINE);
        String expected;
        if (mode == Mode.SELF) {
            expected = withItem
                    ? "bot at 40% (<55% 회복 경계선) with a golden apple → it heals: own hp rises AND "
                            + "an apple is consumed (food 10 rules out vanilla regen)"
                    : "same 40% with an empty bag → no heal is possible, so hp must not rise";
        } else {
            expected = withItem
                    ? "user at 10% (≤15% 치명선) + splash heal held → 「발밑에 투척 회복 포션」: user hp "
                            + "rises AND a potion leaves the inventory"
                    : "user equally critical but no potion → 9.2 falls through to 도주; user hp must "
                            + "not rise and the mode must not be HEAL_USER";
        }
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    private static int count(AICompanionBot bot, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            ItemStack st = bot.getInventory().getItem(i);
            if (st.is(item)) {
                n += st.getCount();
            }
        }
        return n;
    }

    /** ch.8 combat heal. */
    public static class SelfHeal extends BotHealTest {
        public SelfHeal() {
            super(Mode.SELF, true);
        }
    }

    /** ch.8 contrast. */
    public static class SelfNone extends BotHealTest {
        public SelfNone() {
            super(Mode.SELF, false);
        }
    }

    /** 9.2 치명선 heal. */
    public static class UserHeal extends BotHealTest {
        public UserHeal() {
            super(Mode.USER, true);
        }
    }

    /** 9.2 contrast. */
    public static class UserNone extends BotHealTest {
        public UserNone() {
            super(Mode.USER, false);
        }
    }
}
