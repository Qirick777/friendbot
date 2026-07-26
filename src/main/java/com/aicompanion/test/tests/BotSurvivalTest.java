package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * T4.1 survival check (RETREAT_HEAL path): the bot is forced below the danger line while it has a
 * melee target (i.e. it is "engaging"), given a golden apple but NO ender pearl. PASS iff the
 * survival machine overrides combat — the bot↔enemy distance INCREASES (disengage + backpedal,
 * design 우선순위 0) AND the bot's health INCREASES (heal item used, design 우선순위 0 "회복").
 * Both are measured value changes, not "the mode was entered".
 */
public class BotSurvivalTest implements BotTest {

    private static final int ENEMY_DX = 4; // stationary enemy 4 blocks east; bot must retreat west

    private Zombie enemy;
    private double d0;
    private double maxDist;
    private float h0;
    private float maxHp;

    @Override
    public String name() {
        return "bot_survival";
    }

    @Override
    public int timeoutTicks() {
        return 400;
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return new int[]{-12, ENEMY_DX + 3, -4, 4};
    }

    @Override
    public void setup(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        BlockPos o = ctx.origin;
        ctx.level.setDayTime(18000L); // night: undead enemy doesn't burn (would confound HP readings)

        for (int dx = -12; dx <= ENEMY_DX + 3; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() - 1, o.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 2; dy++) {
                    ctx.level.setBlockAndUpdate(new BlockPos(o.getX() + dx, o.getY() + dy, o.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        bot.survival().reset();
        bot.setDeltaMovement(Vec3.ZERO);
        bot.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90.0F, 0.0F);
        bot.setInvulnerable(true); // no incidental damage: health only moves via the heal item
        // Force below the danger line (30% of 20 = 6). health 4 (<6) → RETREAT_HEAL (pearl needs >8 HP,
        // so at this absolute HP a pearl escape is (correctly) unavailable → retreat).
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20.0);
        bot.setHealth(4.0F);
        // Disable natural regen (needs food>=18) so the health increase is attributable ONLY to the apple.
        bot.getFoodData().setFoodLevel(6);
        // Heal item: strong Regeneration so the HP rise is fast and unambiguous.
        bot.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        bot.getInventory().add(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1));
        // Give the bot a sword and a MELEE target — proves override: without survival, melee would
        // close the distance; with survival, the bot retreats instead.
        bot.getInventory().add(new ItemStack(Items.IRON_SWORD, 1));

        enemy = ctx.env.spawn(EntityType.ZOMBIE, new BlockPos(o.getX() + ENEMY_DX, o.getY(), o.getZ()));
        if (enemy != null) {
            enemy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0);
            enemy.setHealth(200.0F);
            enemy.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
            enemy.setNoAi(true); // stationary → distance change reflects ONLY the bot's retreat
        }
        bot.meleeCombat().setTarget(enemy);

        d0 = enemy != null ? bot.position().distanceTo(enemy.position()) : 0;
        maxDist = d0;
        h0 = bot.getHealth();
        maxHp = h0;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        if (bot != null && enemy != null) {
            double dist = bot.position().distanceTo(enemy.position());
            if (dist > maxDist) {
                maxDist = dist;
            }
            float hp = bot.getHealth();
            if (hp > maxHp) {
                maxHp = hp;
            }
        }
        return ctx.elapsedTicks >= 360;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        String modeSeen = bot != null ? bot.survival().mode().name() : "null";

        boolean retreated = maxDist > d0 + 1.5;   // disengaged + backed away
        boolean healed = maxHp > h0 + 0.5;         // heal item raised health
        boolean ok = retreated && healed;

        String measured = String.format("dist:%.2f->%.2f,hp:%.1f->%.1f,mode=%s",
                d0, maxDist, h0, maxHp, modeSeen);
        String expected = "maxDist>d0+1.5 (retreat) AND maxHp>h0+0.5 (heal item used)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
