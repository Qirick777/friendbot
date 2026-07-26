package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;

/**
 * T1.2 subtest 3-B (persist / load): runs on the boot AFTER {@link BotPersistSaveTest}.
 * The {@code ServerStartedEvent} restore hook should have re-spawned the bot from disk.
 * PASS iff the bot exists again with the same fixed UUID (world reload → same identity).
 */
public class BotPersistLoadTest implements BotTest {

    @Override
    public String name() {
        return "bot_persist_load";
    }

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return NO_BUILD;
    }

    @Override
    public void setup(BotTestContext ctx) {
        // Nothing: restoration is driven by BotLifecycle.onServerStarted before this runs.
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        return ctx.elapsedTicks >= 5;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        boolean restored = bot != null && !bot.isRemoved();
        boolean uuidMatch = restored && bot.getUUID().equals(BotManager.BOT_UUID);

        boolean ok = restored && uuidMatch;
        String uuid = restored ? bot.getUUID().toString() : "none";
        String measured = "restored:" + restored + ",uuidMatch:" + uuidMatch + ",uuid:" + uuid;
        String expected = "restored AND uuid==" + BotManager.BOT_UUID;
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
