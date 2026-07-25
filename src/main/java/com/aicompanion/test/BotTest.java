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

    /**
     * How many independent trials the manager runs before judging (statistical harnesses).
     * 1 = deterministic single-shot. Override for anything whose outcome depends on mob AI,
     * firing timing or pathfinding success, so a lucky single run cannot be mistaken for a PASS.
     */
    /**
     * Axis-aligned footprint this harness writes to, as {minDx, maxDx, minDz, maxDz} relative to the
     * origin. The isolation contract scans, restores and sweeps over exactly this box, so a harness
     * that builds a 110-block corridor must say so — otherwise the part outside the default is
     * neither verified nor restored, which is the hole the +/-24 default was hiding (measured: seven
     * harnesses exceeded it, worst -90).
     *
     * <p>Declared per axis, NOT as one radius: a corridor is -90..+20 by +/-10, and scanning that as
     * a symmetric +/-90 cube would cost 17x for nothing. Asymmetric, the same corridor is CHEAPER
     * than the old default.</p>
     *
     * <p>The declaration is checked with values, not trusted: the canary also scans a margin beyond
     * it and logs anything that changed there, so an under-declared box shows up the same way the
     * under-sized default did.</p>
     */
    default int[] arenaBounds() {
        return new int[]{-24, 24, -24, 24};
    }

    default int repeats() {
        return 1;
    }

    /**
     * Fraction of trials that must pass when {@link #repeats()} &gt; 1.
     *
     * <p>DISCIPLINE: derive this from what the spec demands, never from the current measured
     * success rate. Lowering it to fit today's numbers converts a functional defect into a
     * green light.</p>
     */
    default double successThreshold() {
        return 1.0;
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
