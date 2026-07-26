package com.aicompanion.test.tests;

import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;

/**
 * Harness self-check (T0.2 [검증]): after ~2 seconds, PASS with measured value 42.
 * Exercises the full setup → tick → judge → log path.
 */
public class DummyTest implements BotTest {

    /** 2 seconds at 20 TPS. */
    private static final int WINDOW_TICKS = 40;

    @Override
    public String name() {
        return "dummy";
    }

    @Override
    public int timeoutTicks() {
        return 200;
    }

    /** P-6 (유형 #9): 이 판정이 측정된 세계의 전제. */
    @Override
    public String scenarioSpec() {
        return "관측 200틱, 트라이얼 1회. 유저 없음(TestUser.spawn 미호출) → Perception.java:131이 봇 외 플레이어를 찾지 못해 "
                + "user==null → BotIdle.java:87-89 즉시 반환. 16장 자율 이동 비활성. ";
    }

    @Override
    public void setup(BotTestContext ctx) {
        // No environment needed for the harness self-check.
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        return ctx.elapsedTicks >= WINDOW_TICKS;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        return BotTestResult.pass("42", "constant_42_after_2s");
    }
}
