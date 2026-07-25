package com.aicompanion.test;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.UUID;

/**
 * Test-only stand-in for "the user": a second connection-less {@link ServerPlayer} wired into the
 * tick loop exactly like the bot (dummy {@link Connection} + {@link EmbeddedChannel} +
 * {@code placeNewPlayer}). Because it is a real {@code ServerPlayer} it (a) appears in
 * {@code level.players()} so the bot's perception picks it as the user, and (b) is a valid mob
 * aggro target. Used only by T4.3 protection tests.
 */
public final class TestUser {

    private static final UUID USER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String USER_NAME = "TestUser";

    private static ServerPlayer current;

    private TestUser() {
    }

    public static ServerPlayer spawn(MinecraftServer server, ServerLevel level, BlockPos pos) {
        if (current != null && current.isAlive()) {
            // Reuse the entity, but NOT its state. Returning it untouched made trial 1 of
            // bot_escape_ride carry the user away and trials 2-3 run with a stale user sitting
            // where trial 1 left it (userMoved:0.00, twice, identically).
            current.stopRiding();
            for (net.minecraft.world.entity.Entity p : new java.util.ArrayList<>(current.getPassengers())) {
                p.stopRiding();
            }
            current.getInventory().clearContent();
            current.setInvulnerable(false);
            current.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            current.fallDistance = 0.0F;
            current.getFoodData().setFoodLevel(20);
            current.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                    .setBaseValue(20.0);
            current.setHealth(20.0F);
            current.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
            return current;
        }
        GameProfile profile = new GameProfile(USER_UUID, USER_NAME);
        ServerPlayer user = new ServerPlayer(server, level, profile);
        user.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);

        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        new EmbeddedChannel(
                new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                        ReferenceCountUtil.release(msg);
                        promise.setSuccess();
                    }
                },
                connection);
        server.getPlayerList().placeNewPlayer(connection, user);
        user.setGameMode(GameType.SURVIVAL);
        user.setHealth(user.getMaxHealth());
        user.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
        current = user;
        return user;
    }

    public static ServerPlayer current() {
        return current;
    }
}
