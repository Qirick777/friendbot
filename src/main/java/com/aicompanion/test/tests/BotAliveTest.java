package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;

/**
 * T1.1 [검증]: spawn the bot, observe N ticks, and confirm it is alive and ticking.
 *
 * <p>PASS iff the bot entity exists in the world (non-null, not removed) AND its
 * {@code tickCount} increased over the observation window (measured before→after).
 * Food level is logged as an observation that the vanilla player tick is running.</p>
 */
public class BotAliveTest implements BotTest {

    private static final int WINDOW_TICKS = 40; // 2s @ 20 TPS

    private int tickStart = -1;

    @Override
    public String name() {
        return "bot_alive";
    }

    @Override
    public int timeoutTicks() {
        return 200;
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
        AICompanionBot bot = BotManager.current();
        if (bot == null) {
            bot = BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
        tickStart = (bot != null) ? bot.tickCount : -1;
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        return ctx.elapsedTicks >= WINDOW_TICKS;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        boolean exists = bot != null && !bot.isRemoved();
        int tickEnd = exists ? bot.tickCount : -1;
        int food = exists ? bot.getFoodData().getFoodLevel() : -1;
        boolean increased = exists && tickEnd > tickStart;

        String measured = "exists:" + exists + ",tick:" + tickStart + "→" + tickEnd + ",food:" + food;
        String expected = "exists AND tickCount_increased";

        return (exists && increased)
                ? BotTestResult.pass(measured, expected)
                : BotTestResult.fail(measured, expected);
    }
}
