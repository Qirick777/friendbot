package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.living.BotLiving;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * T5.3 [검증] first half, verbatim: 「봇 배고픔 강제로 낮춤 + 식량 지급 → 봇이 먹어 배고픔 회복하는지.
 * <b>판정: 식량 섭취 후 배고픔 값 증가</b>」 — plus the two selection clauses of 15.1 that the
 * verification sentence does not name but the 구현 스펙 does:
 *
 * <pre>
 *   식량 선택: 포만감·포화도 높은 것 우선, 위험 식량(썩은 고기·복어·독감자) 회피
 *   전투 중 억제: … 위급 회복(황금사과) 아니면 안전할 때
 * </pre>
 *
 * <p><b>eat</b> — hungry, safe, three foods in the bag: rotten flesh (dangerous), a golden apple
 * (score 13.6) and cooked beef (score 20.8). The bot must eat the beef, the food bar must rise, and
 * the rotten flesh count must be unchanged.<br>
 * <b>combat</b> — the CONTRAST: same hunger, same food, but a zombie engaged. Nothing may be eaten,
 * so the food bar must not move. Without this arm "the bot ate" would not distinguish 15.1's
 * suppression clause from an unconditional eat.</p>
 */
public class BotLivingEatTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int START_FOOD = 6;
    private static final int RUN = 120;

    private final boolean fighting;
    private int foodBefore;
    private int foodAfter;
    private int rottenBefore;
    private int rottenAfter;
    private int beefBefore;
    private int beefAfter;
    private String eaten = "none";
    private boolean sprintClamped;
    private Zombie zombie;

    protected BotLivingEatTest(boolean fighting) {
        this.fighting = fighting;
    }

    @Override
    public int[] arenaBounds() {
        return new int[]{-12, 12, -12, 12};
    }

    @Override
    public String name() {
        return fighting ? "bot_live_eat_combat" : "bot_live_eat";
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
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
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
        bot.getInventory().clearContent();
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        bot.setHealth(20.0F);
        bot.setInvulnerable(true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0.0F, 0.0F);
        bot.meleeCombat().stop();
        bot.rangedCombat().stop();

        // Three foods with known vanilla scores (nutrition + nutrition × satModifier × 2):
        //   rotten flesh  4 + 4×0.1×2 =  4.8  but DANGEROUS → must never be chosen
        //   golden apple  4 + 4×1.2×2 = 13.6
        //   cooked beef   8 + 8×0.8×2 = 20.8  ← the expected pick
        bot.getInventory().add(new ItemStack(Items.ROTTEN_FLESH, 3));
        bot.getInventory().add(new ItemStack(Items.GOLDEN_APPLE, 1));
        bot.getInventory().add(new ItemStack(Items.COOKED_BEEF, 2));
        bot.equipment().invalidate();
        bot.getFoodData().setFoodLevel(START_FOOD);
        bot.getFoodData().setSaturation(0.0F);
        bot.setSprinting(true);   // 15.1 sprint clamp: must be off again by the first judge read

        if (fighting) {
            zombie = ctx.env.spawn(EntityType.ZOMBIE, o.offset(4, 0, 0));
            if (zombie != null) {
                zombie.setNoAi(true);           // engaged, but must not damage the bot's food bar
                zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0);
                zombie.setHealth(200.0F);
                bot.meleeCombat().setTarget(zombie);
            }
        }

        foodBefore = bot.getFoodData().getFoodLevel();
        rottenBefore = count(bot, Items.ROTTEN_FLESH);
        beefBefore = count(bot, Items.COOKED_BEEF);
        foodAfter = foodBefore;
        rottenAfter = rottenBefore;
        beefAfter = beefBefore;
        sprintClamped = false;
        eaten = "none";
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            return true;
        }
        if (fighting && zombie != null && zombie.isAlive()) {
            bot.meleeCombat().setTarget(zombie);   // keep the combat premise true all window
        }
        if (!bot.isSprinting()) {
            sprintClamped = true;
        }
        foodAfter = bot.getFoodData().getFoodLevel();
        rottenAfter = count(bot, Items.ROTTEN_FLESH);
        beefAfter = count(bot, Items.COOKED_BEEF);
        if (bot.living().lastEaten() != null) {
            eaten = bot.living().lastEaten().toString();
        }
        return ctx.elapsedTicks >= RUN;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        int gain = foodAfter - foodBefore;
        boolean rottenUntouched = rottenAfter == rottenBefore;
        boolean ok = fighting
                ? (gain == 0 && "none".equals(eaten) && rottenUntouched)
                : (gain > 0 && beefAfter < beefBefore && rottenUntouched && sprintClamped);

        LOGGER.info("[LIVING-TEST] arm={} food={}->{} eaten={} beef={}->{} rotten={}->{} sprintClamped={}",
                name(), foodBefore, foodAfter, eaten, beefBefore, beefAfter,
                rottenBefore, rottenAfter, sprintClamped);
        String measured = String.format(
                "arm:%s,food:%d->%d,gain:%d,eaten:%s,beef:%d->%d,rotten:%d->%d,"
                        + "sprintClampedAt<=%d:%b,threshold:%d",
                fighting ? "combat" : "safe", foodBefore, foodAfter, gain, eaten,
                beefBefore, beefAfter, rottenBefore, rottenAfter,
                BotLiving.SPRINT_MIN_FOOD, sprintClamped, BotLiving.HUNGER_THRESHOLD);
        String expected = fighting
                ? "engaged in melee → 15.1 suppression: no eat at all (food bar unchanged, rotten "
                        + "flesh untouched)"
                : "hungry and safe → eats the highest-scoring SAFE food (cooked beef 20.8, not the "
                        + "golden apple 13.6, never the rotten flesh) → food level rises AND the "
                        + "≤6 sprint clamp is observed";
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

    /** Hungry and safe: the bot must eat. */
    public static class Safe extends BotLivingEatTest {
        public Safe() {
            super(false);
        }
    }

    /** Equally hungry but fighting: 15.1 says it must not. */
    public static class Combat extends BotLivingEatTest {
        public Combat() {
            super(true);
        }
    }
}
