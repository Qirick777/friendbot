package com.aicompanion.test;

import com.aicompanion.test.tests.BotAliveTest;
import com.aicompanion.test.tests.BotDeathTest;
import com.aicompanion.test.tests.BotLookTest;
import com.aicompanion.test.tests.BotMoveFlatTest;
import com.aicompanion.test.tests.BotMoveStairTest;
import com.aicompanion.test.tests.BotPathBlockedTest;
import com.aicompanion.test.tests.BotPathReachTest;
import com.aicompanion.test.tests.BotPersistLoadTest;
import com.aicompanion.test.tests.BotPersistSaveTest;
import com.aicompanion.test.tests.BotSingleTest;
import com.aicompanion.test.tests.DummyTest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Registry of available tests: {@code name → factory}. Later tasks register their
 * own {@link BotTest} here, so every verification rides on the same harness.
 */
public final class BotTestRegistry {

    private static final Map<String, Supplier<BotTest>> TESTS = new LinkedHashMap<>();

    static {
        // T0.2 harness self-check.
        register("dummy", DummyTest::new);
        // T1.1 resident fake-player.
        register("bot_alive", BotAliveTest::new);
        // T1.2 lifecycle.
        register("bot_single", BotSingleTest::new);
        register("bot_death", BotDeathTest::new);
        register("bot_persist_save", BotPersistSaveTest::new);
        register("bot_persist_load", BotPersistLoadTest::new);
        // T2.1 movement executor.
        register("bot_move_flat", BotMoveFlatTest::new);
        register("bot_move_stair", BotMoveStairTest::new);
        // T2.2 look system.
        register("bot_look", BotLookTest::new);
        // T2.3 A* pathfinding.
        register("bot_path_reach", BotPathReachTest::new);
        register("bot_path_blocked", BotPathBlockedTest::new);
    }

    private BotTestRegistry() {
    }

    public static void register(String name, Supplier<BotTest> factory) {
        TESTS.put(name, factory);
    }

    /** Create a fresh test instance, or null if the name is unknown. */
    public static BotTest create(String name) {
        Supplier<BotTest> factory = TESTS.get(name);
        return factory == null ? null : factory.get();
    }

    public static Set<String> names() {
        return TESTS.keySet();
    }
}
