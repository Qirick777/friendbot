package com.aicompanion.test;

/**
 * A verification test (design R.0.3 / R.2). Lifecycle enforced by {@link BotTestManager}:
 *
 * <pre>
 *   setup(ctx)         // once: create the situation (spawn / place / give). NO judging.
 *   tick(ctx)          // every server tick: observe; return true when the window is done.
 *   judge(ctx)         // once: measure the actual value change → PASS/FAIL + measured value.
 * </pre>
 *
 * Discipline: {@code judge} must decide from a measured before→after change or state value,
 * never from "the action was invoked".
 */
public interface BotTest {

    /** Unique test name (used by {@code /bottest <name>} and in the log line). */
    String name();

    /** Max ticks to observe before the manager declares a timeout FAIL. */
    default int timeoutTicks() {
        return 200;
    }

    /** Create the environment. Called once, on the server thread, before observation. */
    void setup(BotTestContext ctx);

    /**
     * Poll observation. Called every server tick after setup.
     * @return true when the observation window is complete and {@link #judge} should run.
     */
    boolean tick(BotTestContext ctx);

    /** Measure the result and decide PASS/FAIL. Called once when {@link #tick} returns true. */
    BotTestResult judge(BotTestContext ctx);
}
