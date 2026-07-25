package com.aicompanion.test;

import com.aicompanion.test.tests.BotAliveTest;
import com.aicompanion.test.tests.BotCatchFallTest;
import com.aicompanion.test.tests.BotCatchNoneTest;
import com.aicompanion.test.tests.BotCreeperLowFuseTest;
import com.aicompanion.test.tests.BotCreeperWallTest;
import com.aicompanion.test.tests.BotEscapeFarThreatTest;
import com.aicompanion.test.tests.BotEscapeNoneTest;
import com.aicompanion.test.tests.BotEscapeRideTest;
import com.aicompanion.test.tests.BotDeathTest;
import com.aicompanion.test.tests.BotDodgeTest;
import com.aicompanion.test.tests.BotFallNoWaterTest;
import com.aicompanion.test.tests.BotFallWaterTest;
import com.aicompanion.test.tests.BotLookTest;
import com.aicompanion.test.tests.BotMeleeTest;
import com.aicompanion.test.tests.BotMoveFlatTest;
import com.aicompanion.test.tests.BotMoveStairTest;
import com.aicompanion.test.tests.BotPathBlockedTest;
import com.aicompanion.test.tests.BotPathReachTest;
import com.aicompanion.test.tests.BotPerceptionTest;
import com.aicompanion.test.tests.BotPearlTest;
import com.aicompanion.test.tests.BotPhase2ComboTest;
import com.aicompanion.test.tests.BotProtectInterveneTest;
import com.aicompanion.test.tests.BotProtectPriorityTest;
import com.aicompanion.test.tests.BotRangedTest;
import com.aicompanion.test.tests.BotRule1FastTest;
import com.aicompanion.test.tests.BotSurvivalTest;
import com.aicompanion.test.tests.BotTacticsTest;
import com.aicompanion.test.tests.BotPersistLoadTest;
import com.aicompanion.test.tests.BotPersistSaveTest;
import com.aicompanion.test.tests.BotShieldTest;
import com.aicompanion.test.tests.BotSpeedProbeTest;
import com.aicompanion.test.tests.BotSingleTest;
import com.aicompanion.test.tests.BotTotemTest;
import com.aicompanion.test.tests.BotWardenBandReturnTest;
import com.aicompanion.test.tests.BotWardenBandTest;
import com.aicompanion.test.tests.BotWardenChargeTest;
import com.aicompanion.test.tests.BotKiteBandLatchTest;
import com.aicompanion.test.tests.BotKiteExecLatencyTest;
import com.aicompanion.test.tests.BotKiteExecMonitorTest;
import com.aicompanion.test.tests.BotKiteFlipTest;
import com.aicompanion.test.tests.BotKiteRecoverTest;
import com.aicompanion.test.tests.BotWardenLiveSpeedTest;
import com.aicompanion.test.tests.BotWardenProbeTest;
import com.aicompanion.test.tests.BotWindowVarianceTest;
import com.aicompanion.test.tests.BotWardenTacticsTest;
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
        // Phase 2 integration/regression.
        register("bot_phase2_combo", BotPhase2ComboTest::new);
        // T3.1 perception.
        register("bot_perception", BotPerceptionTest::new);
        // T3.2 tactical rule engine.
        register("bot_tactics", BotTacticsTest::new);
        // T3.3 melee combat.
        register("bot_melee", BotMeleeTest::new);
        // T3.4 ranged combat.
        register("bot_ranged", BotRangedTest::new);
        // T4.1 survival state machine.
        register("bot_survival", BotSurvivalTest::new);
        register("bot_pearl", BotPearlTest::new);
        // T4.2 reflex layer.
        register("bot_totem", BotTotemTest::new);
        register("bot_dodge", BotDodgeTest::new);
        register("bot_shield", BotShieldTest::new);
        // T4.3 user protection.
        register("bot_protect_intervene", BotProtectInterveneTest::new);
        register("bot_protect_priority", BotProtectPriorityTest::new);
        // T4.4 environment manipulation (fall survival + creeper wall).
        register("bot_fall_water", BotFallWaterTest::new);
        register("bot_fall_nowater", BotFallNoWaterTest::new);
        register("bot_creeper_wall", BotCreeperWallTest::new);
        register("bot_creeper_lowfuse", BotCreeperLowFuseTest::new);
        // T4.5 kidnap-escape + fall catch (mounting).
        register("bot_escape_ride", BotEscapeRideTest::new);
        register("bot_escape_none", BotEscapeNoneTest::new);
        register("bot_escape_farthreat", BotEscapeFarThreatTest::new);
        register("bot_catch_fall", BotCatchFallTest::new);
        register("bot_catch_none", BotCatchNoneTest::new);
        // T4.6 warden layer-2 data (step-1 probe).
        register("bot_warden_probe", BotWardenProbeTest::new);
        register("bot_speed_probe", BotSpeedProbeTest::new);
        register("bot_rule1_fast", BotRule1FastTest::new);
        register("bot_kite_flip", BotKiteFlipTest::new);
        register("bot_kite_band_latch", BotKiteBandLatchTest::new);
        register("bot_kite_recover", BotKiteRecoverTest::new);
        register("bot_kite_execmon", BotKiteExecMonitorTest::new);
        register("bot_kite_execmon_lat", BotKiteExecLatencyTest::new);
        register("bot_window_variance", BotWindowVarianceTest::new);
        register("bot_warden_live_speed", BotWardenLiveSpeedTest::new);
        register("bot_warden_tactics", BotWardenTacticsTest.Warden::new);
        register("bot_warden_noname", BotWardenTacticsTest.NoName::new);
        register("bot_warden_generic", BotWardenTacticsTest.Generic::new);
        register("bot_warden_band", BotWardenBandTest::new);
        register("bot_warden_band_below", BotWardenBandReturnTest.Below::new);
        register("bot_warden_band_above", BotWardenBandReturnTest.Above::new);
        register("bot_warden_charge", BotWardenChargeTest.Escape::new);
        register("bot_warden_charge_none", BotWardenChargeTest.None::new);
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
