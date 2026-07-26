package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.living.BotPickup;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import com.aicompanion.test.TestUser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * T5.5 [검증] verbatim: 「비전투 상태에서 유저가 봇 방향으로 아이템 던짐 → 봇이 다가가 인벤토리에
 * 획득하는지. <b>판정: 던진 아이템이 봇 인벤토리에 추가됨(엔티티 소멸 + 봇 인벤 수량 증가).</b>」
 *
 * <ul>
 *   <li><b>gift</b> — the spec's case. The stack is stamped with the user as its thrower and lands
 *       12 blocks out, beyond {@code AIM_RADIUS}, so acceptance has to come from the aim term
 *       (「대략 봇 방향이면 수락」) rather than from proximity.</li>
 *   <li><b>combat</b> — 17.1's parenthesis 「전투 중엔 받으러 가지 않음」. Identical gift, but a zombie
 *       is engaged: the item must still be on the ground at the end.</li>
 *   <li><b>natural</b> — 17.2 「유저가 그것을 줍지 못하는 경우에만 봇이 주움」. An unthrown drop two
 *       blocks from the user — plainly within their reach — must be left alone.</li>
 * </ul>
 */
public class BotPickupTest implements BotTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int RUN = 200;
    private static final int ITEM_X = 12;    // where the stack lands, relative to the bot
    private static final int USER_X = -6;    // user behind the bot → throw bearing points at it
    private static final int GIFT_COUNT = 3;

    private final Mode mode;
    private ServerPlayer user;
    private ItemEntity item;
    private Zombie zombie;
    private int invBefore;
    private int invAfter;
    private boolean itemGone;
    private int fetchTicks;
    private String reason = "none";
    /** The FIRST acceptance reason — the claim is about why the fetch started, not why it ended. */
    private String firstReason = "none";
    private double startItemDist;

    public enum Mode { GIFT, COMBAT, NATURAL }

    protected BotPickupTest(Mode mode) {
        this.mode = mode;
    }

    @Override
    public boolean itemsAreSubject() {
        return true;   // the stack on the ground IS the subject — see BotTest.itemsAreSubject
    }

    @Override
    public int[] arenaBounds() {
        return new int[]{-20, 24, -20, 20};
    }

    @Override
    public String scenarioSpec() {
        return String.format(
                "user %d blocks behind the bot; a %s stack of %d diamonds at %d blocks (%s the "
                + "%.0f-block 근접 term, so the AIM term is what must carry it); pickup delay 0; "
                + "canary item sweep disabled for this harness (itemsAreSubject)%s",
                Math.abs(USER_X), mode == Mode.NATURAL ? "unthrown" : "user-thrown", GIFT_COUNT,
                mode == Mode.NATURAL ? USER_X + 2 : ITEM_X,
                mode == Mode.NATURAL ? "inside" : "outside", BotPickup.AIM_RADIUS,
                mode == Mode.COMBAT ? "; a 400hp NoAi zombie is held as the melee target" : "");
    }

    @Override
    public String name() {
        return switch (mode) {
            case GIFT -> "bot_pickup_gift";
            case COMBAT -> "bot_pickup_combat";
            case NATURAL -> "bot_pickup_natural";
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
        for (int dx = -16; dx <= 20; dx++) {
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
        bot.living().reset();
        bot.idle().reset();
        bot.pickup().reset();
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

        user = TestUser.spawn(ctx.server, ctx.level, new BlockPos(o.getX() + USER_X, o.getY(), o.getZ()));
        user.setInvulnerable(true);
        user.setDeltaMovement(Vec3.ZERO);

        // The canary's item sweep is disabled for this harness (itemsAreSubject), so the world's
        // own construction debris would otherwise stay on the ground — and 17.2 fetches abandoned
        // drops. A previous run logged "[PICKUP] fetching stick (abandoned) d=21.15", which would
        // have made the natural arm pass or fail for a reason that has nothing to do with it.
        // Clear every item first, then place exactly the one stack this test is about.
        for (ItemEntity stray : ctx.level.getEntitiesOfClass(ItemEntity.class,
                new net.minecraft.world.phys.AABB(o).inflate(64.0))) {
            stray.discard();
        }

        // 17.2's arm keeps the drop next to the user, where the user can plainly reach it.
        int itemX = mode == Mode.NATURAL ? USER_X + 2 : ITEM_X;
        item = new ItemEntity(ctx.level, o.getX() + itemX + 0.5, o.getY() + 0.1, o.getZ() + 0.5,
                new ItemStack(Items.DIAMOND, GIFT_COUNT));
        item.setDeltaMovement(Vec3.ZERO);
        item.setPickUpDelay(0);
        if (mode != Mode.NATURAL) {
            // Vanilla stamps the thrower on a tossed stack; that stamp IS 「유저가 던진 것」.
            item.setThrower(user.getUUID());
        }
        ctx.level.addFreshEntity(item);

        if (mode == Mode.COMBAT) {
            zombie = ctx.env.spawn(EntityType.ZOMBIE, o.offset(0, 0, 4));
            if (zombie != null) {
                zombie.setNoAi(true);
                zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(400.0);
                zombie.setHealth(400.0F);
                zombie.setInvulnerable(true);
                bot.meleeCombat().setTarget(zombie);
            }
        }

        invBefore = count(bot);
        invAfter = invBefore;
        itemGone = false;
        fetchTicks = 0;
        reason = "none";
        firstReason = "none";
        startItemDist = bot.position().distanceTo(item.position());
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            return true;
        }
        if (mode == Mode.COMBAT && zombie != null && zombie.isAlive()) {
            bot.meleeCombat().setTarget(zombie);   // hold the combat premise for the whole window
        }
        if (bot.pickup().isFetching()) {
            fetchTicks++;
            if (bot.pickup().lastReason() != null) {
                reason = bot.pickup().lastReason();
                if ("none".equals(firstReason)) {
                    firstReason = reason;
                }
            }
        }
        if (item != null && !item.isAlive()) {
            itemGone = true;
        }
        invAfter = count(bot);
        return ctx.elapsedTicks >= RUN;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        int gained = invAfter - invBefore;
        boolean ok = switch (mode) {
            case GIFT -> itemGone && gained == GIFT_COUNT && fetchTicks > 0;
            case COMBAT -> !itemGone && gained == 0 && fetchTicks == 0;
            case NATURAL -> !itemGone && gained == 0 && fetchTicks == 0;
        };

        LOGGER.info("[PICKUP-TEST] arm={} gone={} inv={}→{} fetchTicks={} reason={} startDist={}",
                name(), itemGone, invBefore, invAfter, fetchTicks, reason,
                String.format("%.2f", startItemDist));
        String measured = String.format(
                "arm:%s,itemEntityGone:%b,botInv:%d->%d,gained:%d,fetchTicks:%d,reason:%s,"
                        + "firstReason:%s,startItemDist:%.2f,aimRadius:%.0f,userDist:%d",
                mode.name().toLowerCase(), itemGone, invBefore, invAfter, gained, fetchTicks, reason,
                firstReason,
                startItemDist, BotPickup.AIM_RADIUS, Math.abs(USER_X));
        String expected = switch (mode) {
            case GIFT -> "user-thrown stack 12 blocks out (beyond the 6-block 근접 term, so the AIM "
                    + "term must carry it) → bot fetches: entity gone AND inventory +3";
            case COMBAT -> "same gift while engaged → 「전투 중엔 받으러 가지 않음」: entity still on "
                    + "the ground, inventory unchanged, never fetching";
            case NATURAL -> "unthrown drop 2 blocks from the user → 17.2 leaves it: the user can "
                    + "reach it, so the bot must not take it";
        };
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }

    private static int count(AICompanionBot bot) {
        int n = 0;
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            ItemStack st = bot.getInventory().getItem(i);
            if (st.is(Items.DIAMOND)) {
                n += st.getCount();
            }
        }
        return n;
    }

    /** 17.1 — the spec's own case. */
    public static class Gift extends BotPickupTest {
        public Gift() {
            super(Mode.GIFT);
        }
    }

    /** 17.1's parenthesis. */
    public static class Combat extends BotPickupTest {
        public Combat() {
            super(Mode.COMBAT);
        }
    }

    /** 17.2 — 뺏어가는 느낌 방지. */
    public static class Natural extends BotPickupTest {
        public Natural() {
            super(Mode.NATURAL);
        }
    }
}
