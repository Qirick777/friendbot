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
