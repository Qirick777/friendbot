package com.aicompanion.test.tests;

import com.aicompanion.bot.AICompanionBot;
import com.aicompanion.bot.BotManager;
import com.aicompanion.bot.BotWorldData;
import com.aicompanion.test.BotTest;
import com.aicompanion.test.BotTestContext;
import com.aicompanion.test.BotTestResult;

/**
 * T1.2 subtest 3-A (persist / save): spawn the bot (fresh world) and flush everything to disk.
 * PASS iff the bot exists and the world save succeeded, leaving {@code botExists == true} on disk
 * for the following boot ({@link BotPersistLoadTest}) to restore.
 */
public class BotPersistSaveTest implements BotTest {

    @Override
    public String name() {
        return "bot_persist_save";
    }

    @Override
    public void setup(BotTestContext ctx) {
        if (BotManager.current() == null) {
            BotManager.spawn(ctx.server, ctx.level, ctx.origin);
        }
    }

    @Override
    public boolean tick(BotTestContext ctx) {
        return ctx.elapsedTicks >= 5;
    }

    @Override
    public BotTestResult judge(BotTestContext ctx) {
        AICompanionBot bot = BotManager.current();
        boolean exists = bot != null && !bot.isRemoved();
        // Flush playerdata (position/inventory/equipment) + SavedData (botExists/UUID) to disk.
        boolean saved = ctx.server.saveEverything(true, true, true);
        boolean recorded = BotWorldData.get(ctx.server).botExists();

        boolean ok = exists && saved && recorded;
        String uuid = exists ? bot.getUUID().toString() : "none";
        String measured = "exists:" + exists + ",saved:" + saved + ",botExists:" + recorded + ",uuid:" + uuid;
        String expected = "exists AND saveEverything==true AND botExists==true";
        return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
    }
}
