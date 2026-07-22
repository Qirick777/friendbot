package com.aicompanion.bot;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * Dev summon command (T1.1 "임시 소환 수단"):
 * {@code /bot spawn} | {@code /bot despawn} | {@code /bot info}. Requires permission level 2.
 */
public final class BotCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

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
                }))
                // T3.1 perception dump.
                .then(Commands.literal("perception").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    AICompanionBot bot = BotManager.current();
                    if (bot == null) {
                        src.sendFailure(Component.literal("[BOT] no bot"));
                        return 0;
                    }
                    String dump = bot.perception().dump();
                    LOGGER.info("{}", dump);
                    src.sendSuccess(() -> Component.literal(dump), false);
                    return 1;
                }))
                // T3.3 melee combat.
                .then(Commands.literal("attack").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    AICompanionBot bot = BotManager.current();
                    if (bot == null) {
                        src.sendFailure(Component.literal("[BOT] no bot"));
                        return 0;
                    }
                    if (bot.perception().targets.isEmpty()) {
                        src.sendFailure(Component.literal("[BOT] no target in range"));
                        return 0;
                    }
                    var t = bot.perception().targets.get(0).entity; // nearest
                    bot.meleeCombat().setTarget(t);
                    src.sendSuccess(() -> Component.literal("[BOT] melee attacking " + t.getType()), false);
                    return 1;
                }))
                .then(Commands.literal("attackstop").executes(ctx -> {
                    AICompanionBot bot = BotManager.current();
                    if (bot == null) {
                        ctx.getSource().sendFailure(Component.literal("[BOT] no bot"));
                        return 0;
                    }
                    bot.meleeCombat().stop();
                    ctx.getSource().sendSuccess(() -> Component.literal("[BOT] attack stopped"), false);
                    return 1;
                }))
                // T3.4 ranged combat.
                .then(Commands.literal("shoot").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    AICompanionBot bot = BotManager.current();
                    if (bot == null) {
                        src.sendFailure(Component.literal("[BOT] no bot"));
                        return 0;
                    }
                    if (bot.perception().targets.isEmpty()) {
                        src.sendFailure(Component.literal("[BOT] no target in range"));
                        return 0;
                    }
                    var t = bot.perception().targets.get(0).entity; // nearest
                    bot.rangedCombat().setTarget(t);
                    src.sendSuccess(() -> Component.literal("[BOT] ranged attacking " + t.getType()), false);
                    return 1;
                }))
                .then(Commands.literal("shootstop").executes(ctx -> {
                    AICompanionBot bot = BotManager.current();
                    if (bot == null) {
                        ctx.getSource().sendFailure(Component.literal("[BOT] no bot"));
                        return 0;
                    }
                    bot.rangedCombat().stop();
                    ctx.getSource().sendSuccess(() -> Component.literal("[BOT] ranged stopped"), false);
                    return 1;
                }))
                // T4.1 survival state dump.
                .then(Commands.literal("survival").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    AICompanionBot bot = BotManager.current();
                    if (bot == null) {
                        src.sendFailure(Component.literal("[BOT] no bot"));
                        return 0;
                    }
                    float hpFrac = bot.getHealth() / bot.getMaxHealth();
                    String dump = String.format("[SURVIVAL] mode=%s hp=%.1f/%.1f (%.0f%%) critical=%b pearlTp=%b(%s)",
                            bot.survival().mode(), bot.getHealth(), bot.getMaxHealth(), hpFrac * 100,
                            bot.survival().isCritical(bot), bot.survival().pearlTeleportConfirmed(),
                            bot.survival().pearlMechanism());
                    LOGGER.info("{}", dump);
                    src.sendSuccess(() -> Component.literal(dump), false);
                    return 1;
                }))
                // T3.2 tactical-judgment dump.
                .then(Commands.literal("tactics").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    AICompanionBot bot = BotManager.current();
                    if (bot == null) {
                        src.sendFailure(Component.literal("[BOT] no bot"));
                        return 0;
                    }
                    com.aicompanion.bot.combat.CombatStats me =
                            com.aicompanion.bot.combat.CombatStats.of(bot);
                    StringBuilder sb = new StringBuilder("[TACTICS] ").append(me);
                    for (com.aicompanion.bot.perception.TargetInfo t : bot.perception().targets) {
                        sb.append("\n  - ").append(t.entity.getType())
                                .append(" -> ")
                                .append(com.aicompanion.bot.combat.CombatRules.evaluate(t, me));
                    }
                    String dump = sb.toString();
                    LOGGER.info("{}", dump);
                    src.sendSuccess(() -> Component.literal(dump), false);
                    return 1;
                })));
    }
}
