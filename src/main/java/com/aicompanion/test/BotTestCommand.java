package com.aicompanion.test;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /bottest <name>} — start a verification test.
 * {@code /bottest list}   — list registered tests.
 * Requires permission level 2 (server console is level 4, so it always qualifies).
 */
public final class BotTestCommand {

    private BotTestCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bottest")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("list").executes(ctx -> {
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("[BOTTEST] tests: " + String.join(", ", BotTestRegistry.names())),
                            false);
                    return 1;
                }))
                .then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    String name = StringArgumentType.getString(ctx, "name");
                    boolean ok = BotTestManager.INSTANCE.start(name, src.getServer(), src);
                    if (!ok) {
                        src.sendFailure(Component.literal(
                                "[BOTTEST] failed to start '" + name + "' (unknown or already running)"));
                        return 0;
                    }
                    return 1;
                })));
    }
}
