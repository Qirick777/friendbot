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

    /** P-1: 이 하니스가 실제로 시공하는 범위. 판정 코어는 이 상자 안으로만 잡힌다. */
    @Override
    public int[] builtBounds() {
        return NO_BUILD;
    }

    /** Q-4(4) + P-6 (유형 #9): 이 판정이 측정된 세계의 전제. */
    @Override
    public String scenarioSpec() {
        return "2부팅 쌍의 **앞쪽**이다. 새 world에서 봇을 스폰하고 saveEverything으로 "
                + "playerdata와 SavedData(botExists/UUID)를 디스크에 내린다. 이 하니스의 PASS는 "
                + "다음 부팅의 bot_persist_load가 성립하기 위한 전제이며, 그 사이에 world 디렉터리가 "
                + "지워지면 쌍이 깨진다(O-3의 실패 원인). 시공 없음(NO_BUILD). 유저 없음.";
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
