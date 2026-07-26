package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.ModItems;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;
import net.minecraft.world.item.ItemStack;

/**
 * T1.2 subtest 1 (single constraint): attempt the spawn egg twice; the second must be
 * refused. PASS iff exactly one bot exists in the world AND only one egg was consumed.
 */
public class BotSingleTest implements BotTest {

    private ItemStack egg;

    @Override
    public String name() {
        return "bot_single";
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return NO_BUILD;
    }

    /** P-6 (유형 #9): 이 판정이 측정된 세계의 전제. */
    @Override
    public String scenarioSpec() {
        return "관측 200틱, 트라이얼 1회. 시공 범위 선언: 없음(NO_BUILD) — 블록 판정 영역이 비어 있고 엔티티/봇 상태만 판정한다. 유저 "
                + "없음(TestUser.spawn 미호출) → Perception.java:131이 봇 외 플레이어를 찾지 못해 user==null → "
                + "BotIdle.java:87-89 즉시 반환. 16장 자율 이동 비활성. ";
    }

    @Override
    public void setup(BotTestContext ctx) {
        // Start clean, then try the egg twice.
        if (BotManager.exists()) {
            BotManager.despawn(ctx.server);
        }
        egg = new ItemStack(ModItems.BOT_SPAWN_EGG.get(), 2);
        boolean first = BotManager.eggSpawn(ctx.level, ctx.origin, egg);   // should spawn + consume
        boolean second = BotManager.eggSpawn(ctx.level, ctx.origin, egg);  // should be refused
        // (results are re-derived in judge from actual world state)
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        return ctx.elapsedTicks >= 10;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        long botCount = ctx.server.getPlayerList().getPlayers().stream()
                .filter(p -> p instanceof AICompanionBot)
                .count();
        int eggCount = egg == null ? -1 : egg.getCount();

        boolean ok = (botCount == 1) && (eggCount == 1);
        String measured = "bots:" + botCount + ",egg:" + eggCount;
        String expected = "bots==1 AND egg==1 (one summon, one consumed, second refused)";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
