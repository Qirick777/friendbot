package com.aicompanion.bot;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Dev summon command (T1.1 "임시 소환 수단"):
 * {@code /bot spawn} | {@code /bot despawn} | {@code /bot info}. Requires permission level 2.
 */
public final class BotCommand {

    private BotCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bot")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("spawn").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    BlockPos pos = BlockPos.containing(src.getPosition());
                    AICompanionBot bot = BotManager.spawn(src.getServer(), src.getLevel(), pos);
                    if (bot == null) {
                        src.sendFailure(Component.literal("[BOT] spawn refused — bot already exists"));
                        return 0;
                    }
                    src.sendSuccess(() -> Component.literal("[BOT] spawned at " + pos), false);
                    return 1;
                }))
                .then(Commands.literal("despawn").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    boolean ok = BotManager.despawn(src.getServer());
                    src.sendSuccess(() -> Component.literal("[BOT] despawn=" + ok), false);
                    return ok ? 1 : 0;
                }))
                .then(Commands.literal("info").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    AICompanionBot bot = BotManager.current();
                    if (bot == null) {
                        src.sendSuccess(() -> Component.literal("[BOT] no bot"), false);
                        return 0;
                    }
                    final String info = "[BOT] tickCount=" + bot.tickCount
                            + " food=" + bot.getFoodData().getFoodLevel()
                            + " health=" + bot.getHealth()
                            + " pos=" + bot.blockPosition();
                    src.sendSuccess(() -> Component.literal(info), false);
                    return 1;
                }))
                // T2.1 dev movement commands.
                .then(Commands.literal("moveto")
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                        .executes(ctx -> {
                                            CommandSourceStack src = ctx.getSource();
                                            AICompanionBot bot = BotManager.current();
                                            if (bot == null) {
                                                src.sendFailure(Component.literal("[BOT] no bot"));
                                                return 0;
                                            }
                                            double x = DoubleArgumentType.getDouble(ctx, "x");
                                            double z = DoubleArgumentType.getDouble(ctx, "z");
                                            bot.mover().moveTo(x, z);
                                            src.sendSuccess(() -> Component.literal("[BOT] moveto (" + x + "," + z + ")"), false);
                                            return 1;
                                        }))))
                .then(Commands.literal("stop").executes(ctx -> {
                    AICompanionBot bot = BotManager.current();
                    if (bot == null) {
                        ctx.getSource().sendFailure(Component.literal("[BOT] no bot"));
                        return 0;
                    }
                    bot.mover().stop();
                    ctx.getSource().sendSuccess(() -> Component.literal("[BOT] stopped"), false);
                    return 1;
                }))
                .then(Commands.literal("crouch")
                        .then(Commands.argument("on", BoolArgumentType.bool()).executes(ctx -> {
                            AICompanionBot bot = BotManager.current();
                            if (bot == null) {
                                ctx.getSource().sendFailure(Component.literal("[BOT] no bot"));
                                return 0;
                            }
                            boolean on = BoolArgumentType.getBool(ctx, "on");
                            bot.mover().setCrouch(on);
                            ctx.getSource().sendSuccess(() -> Component.literal("[BOT] crouch=" + on), false);
                            return 1;
                        }))));
    }
}
