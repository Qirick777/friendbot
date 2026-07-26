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
    /**
     * True when item entities on the ground are this test's SUBJECT (ch.17 자원 조달), so the trial
     * canary must neither sweep them after setup nor count them as drift. Default false: for every
     * other harness a stray item in the arena is debris and the strict contract stands.
     */
    /**
     * The scenario's PREMISES, in the harness's own words: the numbers {@code setup()} chose and
     * why they are the right ones for the claim being tested. Logged at START and carried in the
     * verdict line.
     *
     * <p>Why it exists: 결함 유형 #9 (시나리오 전제 미기록). A verdict is only as good as the world it
     * was measured in, and several premises in this suite are visible only by reading setup() —
     * a 2000 hp dummy, a 4000 hp bot, a 400 hp bot. Those numbers make a measurement mean something
     * different from what its name says, and nothing in the output showed them.</p>
     *
     * <p>Default empty: the manager then logs only what the canary can observe for itself. An
     * override should state what the canary cannot see — the intent behind the numbers.</p>
     */
    default String scenarioSpec() {
        return "";
    }

    default boolean itemsAreSubject() {
        return false;
    }

    default int[] arenaBounds() {
        return new int[]{-24, 24, -24, 24};
    }

    /**
     * P-1: the footprint this harness actually CONSTRUCTS, as {minDx, maxDx, minDz, maxDz}.
     *
     * <p>Why this is separate from {@link #arenaBounds()}. §4 격리 계약 says the judged region is
     * 「하니스가 실제로 지은 땅만 판정」, and the two boxes serve different jobs:</p>
     * <ul>
     *   <li>{@code arenaBounds} = what gets SCANNED and RESTORED. It must stay wide — bot_catch_fall's
     *       water sat at dx −24 and restoring that far is what took it from 1/3 to 3/3. Shrinking it
     *       to the construction box would re-open that defect.</li>
     *   <li>{@code builtBounds} = what gets JUDGED. Measured cause (O-2): with the two conflated,
     *       bot_creeper_wall judged dx −7 and dz ±5..7 — world-gen oak leaves it never touches —
     *       and those leaves' {@code distance} is a neighbour-derived property whose source logs
     *       may lie outside any restore box. 21 cells drifted by exactly one {@code distance} step
     *       every trial. The isolation device was judging ground it could not restore.</li>
     * </ul>
     *
     * <p>Default = {@code arenaBounds()}, i.e. today's behaviour for anything that has not declared.
     * Return {@link #NO_BUILD} when the harness constructs nothing: its judged block region is then
     * empty and only entity/bot/user state is judged. Drift outside the judged core is not silenced
     * — the ring-1 log reports it every trial (§4 「판정 안 하고 로깅만」).</p>
     */
    default int[] builtBounds() {
        return arenaBounds();
    }

    /** A deliberately empty box: min &gt; max on both axes, so no block cell is judged. */
    int[] NO_BUILD = new int[]{0, -1, 0, -1};

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

    /**
     * O-1(3): an extra aggregate the harness accumulated ACROSS trials, appended to the aggregate
     * verdict line. The trial verdict is binary, and a binary verdict throws away information when
     * a trial contains several independent sub-events: bot_dodge judges 「4발 전부 빗나감」, so 50
     * trials carry 200 arrow outcomes and the pass count reports only 50 of them. Default empty.
     *
     * <p>Diagnostic only — the manager appends this to {@code measured} and never reads it for
     * PASS/FAIL. Whether a threshold applies per arrow or per trial is a spec question, not the
     * harness's to settle.</p>
     */
    default String aggregateExtra() {
        return "";
    }

    /**
     * P-2: the sample the verdict is judged on, as {successes, n}, when the trial is NOT the right
     * unit. Return null (default) to judge on trials.
     *
     * <p>사용자 판정 (P-2): 설계서 1496행의 측정 예시 「회피: 공격 후 봇 체력 불변」은 **공격 단위**
     * 서술이다. 트라이얼 단위는 하니스가 만든 인공 분모이고 트라이얼당 화살 수를 바꾸면 판정이
     * 바뀐다. 분모가 스펙에서 나와야 임계도 스펙에서 나온다.</p>
     *
     * <p>{@link #successThreshold()}와 스크리닝 슬랙은 그대로 적용된다 — 바뀌는 것은 분모뿐이다.</p>
     */
    default int[] aggregateSample() {
        return null;
    }

    /**
     * Clear whatever {@link #aggregateExtra} accumulates. Called once at START, before trial 1 —
     * the manager builds a FRESH instance for every trial, so cross-trial accumulation has to live
     * in static state and static state has to be reset explicitly.
     */
    default void resetAggregate() {
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
