package com.aicompanion.test;

/**
 * Outcome of a {@link BotTest}. The harness prints this as:
 * {@code [BOTTEST] <name> PASS/FAIL measured=<measured> expected=<expected>}.
 *
 * <p>Per R.2: the {@code measured} value is the actual observed change/state
 * (before→after), never merely "the command ran".</p>
 */
public record BotTestResult(boolean pass, String measured, String expected) {

    public static BotTestResult pass(String measured, String expected) {
        return new BotTestResult(true, measured, expected);
    }

    public static BotTestResult fail(String measured, String expected) {
        return new BotTestResult(false, measured, expected);
    }
}
