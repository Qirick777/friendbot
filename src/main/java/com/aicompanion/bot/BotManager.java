package com.aicompanion.bot;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
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
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Owns the single resident bot and its "connectionless" tick plumbing (design 3.2,
 * dummy-connection variant).
 *
 * <p>Spawn wires the bot into the vanilla player tick loop via
 * {@link net.minecraft.server.players.PlayerList#placeNewPlayer}, using a
 * channel-less {@link Connection}. Because {@code Connection.isConnected()} is
 * {@code channel != null && channel.isOpen()}, every packet the login flow tries to
 * send is a silent no-op — no network is required. {@code placeNewPlayer} still adds
 * the bot to the level's entity tick list (→ {@code tick()} every server tick) and
 * broadcasts the tab-list entry / spawn tracking so clients render it as a player.</p>
 */
public final class BotManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String BOT_NAME = "Companion";

    /**
     * Fixed identity. The UUID is chosen so the vanilla default skin renders as the
     * Alex (slim) model: {@code Math.floorMod(uuid.hashCode(), 18) == 0} → slim/alex.
     */
    public static final UUID BOT_UUID = UUID.fromString("c40b4e54-5cf9-33de-bc69-a435d5c9462d");

    private static AICompanionBot current;
    private static EmbeddedChannel botChannel;

    private BotManager() {
    }

    /**
     * Build the dummy client connection. Forge's {@code placeNewPlayer} dereferences
     * {@code connection.channel().pipeline()} (NetworkFilters.injectIfNecessary), so a
     * real channel is mandatory. We use an {@link EmbeddedChannel} whose outbound is
     * discarded — the bot needs no client packets, and discarding prevents any memory
     * growth from the packets the server would otherwise queue to it.
     */
    private static EmbeddedChannel openDummyChannel(Connection connection) {
        return new EmbeddedChannel(
                new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                        ReferenceCountUtil.release(msg); // drop; never reaches the channel buffer
                        promise.setSuccess();
                    }
                },
                connection); // adding Connection fires channelActive → sets connection.channel
    }

    @Nullable
    public static AICompanionBot current() {
        return current;
    }

    public static boolean exists() {
        return current != null && !current.isRemoved();
    }

    /** Spawn the single bot at pos. Returns the bot, or null if one already exists. */
    @Nullable
    public static AICompanionBot spawn(MinecraftServer server, ServerLevel level, BlockPos pos) {
        if (exists()) {
            LOGGER.warn("[BOT] spawn refused — bot already exists (uuid={})", current.getUUID());
            return null;
        }

        GameProfile profile = new GameProfile(BOT_UUID, BOT_NAME);
        AICompanionBot bot = new AICompanionBot(server, level, profile);
        bot.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);

        // Dummy connection with an outbound-discarding EmbeddedChannel (see openDummyChannel).
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        botChannel = openDummyChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, bot);

        current = bot;

        int idx = Math.floorMod(BOT_UUID.hashCode(), 18);
        LOGGER.info("[BOT] spawned name={} uuid={} pos=({},{},{}) skinModel={} idx={}",
                BOT_NAME, BOT_UUID, pos.getX(), pos.getY(), pos.getZ(),
                idx <= 8 ? "slim(alex)" : "wide", idx);
        return bot;
    }

    /** Remove the bot from the world. Returns true if one was removed. */
    public static boolean despawn(MinecraftServer server) {
        if (!exists()) {
            return false;
        }
        server.getPlayerList().remove(current);
        LOGGER.info("[BOT] despawned uuid={}", current.getUUID());
        current = null;
        if (botChannel != null) {
            botChannel.close();
            botChannel = null;
        }
        return true;
    }
}
