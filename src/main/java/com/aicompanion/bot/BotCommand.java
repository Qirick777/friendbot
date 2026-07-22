package com.aicompanion.bot;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
                        })))
                // T2.2 dev look commands.
                .then(Commands.literal("look")
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(ctx -> {
                                                    CommandSourceStack src = ctx.getSource();
                                                    AICompanionBot bot = BotManager.current();
                                                    if (bot == null) {
                                                        src.sendFailure(Component.literal("[BOT] no bot"));
                                                        return 0;
                                                    }
                                                    double x = DoubleArgumentType.getDouble(ctx, "x");
                                                    double y = DoubleArgumentType.getDouble(ctx, "y");
                                                    double z = DoubleArgumentType.getDouble(ctx, "z");
                                                    bot.look().lookAt(x, y, z);
                                                    src.sendSuccess(() -> Component.literal("[BOT] look (" + x + "," + y + "," + z + ")"), false);
                                                    return 1;
                                                })))))
                .then(Commands.literal("lookclear").executes(ctx -> {
                    AICompanionBot bot = BotManager.current();
                    if (bot == null) {
                        ctx.getSource().sendFailure(Component.literal("[BOT] no bot"));
                        return 0;
                    }
                    bot.look().clear();
                    ctx.getSource().sendSuccess(() -> Component.literal("[BOT] look cleared"), false);
                    return 1;
                }))
                // T2.3 dev pathing commands.
                .then(Commands.literal("goto")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> {
                                                    CommandSourceStack src = ctx.getSource();
                                                    AICompanionBot bot = BotManager.current();
                                                    if (bot == null) {
                                                        src.sendFailure(Component.literal("[BOT] no bot"));
                                                        return 0;
                                                    }
                                                    BlockPos g = new BlockPos(
                                                            IntegerArgumentType.getInteger(ctx, "x"),
                                                            IntegerArgumentType.getInteger(ctx, "y"),
                                                            IntegerArgumentType.getInteger(ctx, "z"));
                                                    bot.planner().setGoal(g);
                                                    src.sendSuccess(() -> Component.literal("[BOT] goto " + g), false);
                                                    return 1;
                                                })))))
                .then(Commands.literal("pathstop").executes(ctx -> {
                    AICompanionBot bot = BotManager.current();
                    if (bot == null) {
                        ctx.getSource().sendFailure(Component.literal("[BOT] no bot"));
                        return 0;
                    }
                    bot.planner().stop();
                    bot.mover().stop();
                    ctx.getSource().sendSuccess(() -> Component.literal("[BOT] path stopped"), false);
                    return 1;
                })));
    }
}
