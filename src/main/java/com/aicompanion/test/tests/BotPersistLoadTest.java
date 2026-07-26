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

    /** Q-4(4) + P-6 (유형 #9): 이 판정이 측정된 세계의 전제. */
    @Override
    public String scenarioSpec() {
        return "2부팅 쌍의 **뒤쪽**이다. 앞쪽 bot_persist_save가 같은 world 디렉터리에 저장한 뒤 "
                + "서버를 내렸다 올린 부팅에서만 성립한다. 월드가 지워진 단독 실행에서는 "
                + "botExists=false이므로 BotManager.java:136-138이 「restore skipped — no bot recorded」를 "
                + "찍고 스펙대로 아무것도 하지 않는다 — 그 경우의 판정은 이 하니스가 아니라 "
                + "bot_persist_none이 맡는다(설계서:1260 뒷절). 시공 없음(NO_BUILD). 유저 없음.";
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

    /**
     * Q-4(2) 뒷절: 설계서:1260 「false면 스폰 안 함」. 저장이 없는 부팅에서 봇이 없어야 PASS다.
     * 앞절({@link BotPersistLoadTest})과 판정 방향이 정반대이므로 별개 하니스여야 한다 — 하나가
     * 두 절을 반대 방향으로 물으면 배치 조건에 따라 어느 한쪽이 반드시 FAIL로 찍힌다(O-3(2)).
     */
    public static class NoRecord implements BotTest {

        @Override
        public String name() {
            return "bot_persist_none";
        }

        @Override
        public int[] builtBounds() {
            return NO_BUILD;
        }

        @Override
        public String scenarioSpec() {
            return "2부팅 쌍의 **바깥**이다. 저장이 없는 새 월드에서 단독 부팅한다 — "
                    + "botExists=false가 전제이며 봇이 스폰되지 않아야 PASS다. 시공 없음(NO_BUILD). 유저 없음.";
        }

        @Override
        public void setup(BotTestContext ctx) {
            // Nothing. The whole point is that no bot was ever saved.
        }

        @Override
        public boolean tick(BotTestContext ctx) {
            return ctx.elapsedTicks >= 5;
        }

        @Override
        public BotTestResult judge(BotTestContext ctx) {
            AICompanionBot bot = BotManager.current();
            boolean recorded = com.aicompanion.bot.BotWorldData.get(ctx.server).botExists();
            boolean present = bot != null && !bot.isRemoved();
            boolean ok = !recorded && !present;
            String measured = "botExists:" + recorded + ",botPresent:" + present;
            String expected = "botExists==false AND no bot spawned (설계서:1260 뒷절)";
            return ok ? BotTestResult.pass(measured, expected) : BotTestResult.fail(measured, expected);
        }
    }
}
