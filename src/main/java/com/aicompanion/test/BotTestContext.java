package com.aicompanion.test;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * Shared state passed to every phase of a {@link BotTest} ({@code setup → tick → judge}).
 *
 * <ul>
 *   <li>{@link #server} / {@link #level} — where the test runs.</li>
 *   <li>{@link #origin} — base coordinate for spawns/placement.</li>
 *   <li>{@link #source} — the command source (nullable for headless auto-run).</li>
 *   <li>{@link #env} — environment utilities (spawn / give / clear).</li>
 *   <li>{@link #elapsedTicks} — ticks observed since {@code setup()} completed.</li>
 * </ul>
 */
public class BotTestContext {

    public final MinecraftServer server;
    public final ServerLevel level;
    public final BlockPos origin;
    @Nullable
    public final CommandSourceStack source;
    public final TestEnv env;

    /** Observation-window counter; reset to 0 after setup, incremented each server tick. */
    public int elapsedTicks = 0;

    public BotTestContext(MinecraftServer server, ServerLevel level, BlockPos origin,
                          @Nullable CommandSourceStack source) {
        this.server = server;
        this.level = level;
        this.origin = origin;
        this.source = source;
        this.env = new TestEnv(level);
    }
}
