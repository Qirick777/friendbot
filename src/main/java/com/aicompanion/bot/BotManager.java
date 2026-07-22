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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
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

    /**
     * Fresh summon (egg / dev command): a new bot with full health and empty inventory,
     * even if stale playerdata from a previous (e.g. dead) bot is on disk.
     */
    @Nullable
    public static AICompanionBot spawn(MinecraftServer server, ServerLevel level, BlockPos pos) {
        return spawnInternal(server, level, pos, true);
    }

    @Nullable
    private static AICompanionBot spawnInternal(MinecraftServer server, ServerLevel level, BlockPos pos, boolean fresh) {
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
        // placeNewPlayer.load() applies any saved playerdata (position/inventory/health).
        server.getPlayerList().placeNewPlayer(connection, bot);
        bot.setGameMode(GameType.SURVIVAL);

        if (fresh) {
            // A brand-new summon must not inherit a prior (possibly dead) bot's saved state.
            bot.getInventory().clearContent();
            bot.setHealth(bot.getMaxHealth());
            bot.getFoodData().setFoodLevel(20);
            bot.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
        }

        current = bot;
        // Record existence (single source of truth) + fixed UUID.
        BotWorldData.get(server).set(true, BOT_UUID);

        int idx = Math.floorMod(BOT_UUID.hashCode(), 18);
        LOGGER.info("[BOT] spawned name={} uuid={} pos=({},{},{}) fresh={} skinModel={} idx={}",
                BOT_NAME, BOT_UUID, pos.getX(), pos.getY(), pos.getZ(), fresh,
                idx <= 8 ? "slim(alex)" : "wide", idx);
        return bot;
    }

    /**
     * Restore the bot on world load (design "복원"): if {@link BotWorldData#botExists()},
     * re-run the residency plumbing so the saved playerdata (position/inventory/equipment)
     * is reloaded via {@code placeNewPlayer}. No-op if the record says the bot doesn't exist.
     */
    public static void restore(MinecraftServer server) {
        if (exists()) {
            return;
        }
        BotWorldData data = BotWorldData.get(server);
        if (!data.botExists()) {
            LOGGER.info("[BOT] restore skipped — no bot recorded");
            return;
        }
        ServerLevel level = server.overworld();
        // fresh=false: keep the saved playerdata (position/inventory/equipment) from disk.
        AICompanionBot bot = spawnInternal(server, level, level.getSharedSpawnPos(), false);
        if (bot != null) {
            LOGGER.info("[BOT] restored uuid={} pos={} food={}",
                    bot.getUUID(), bot.blockPosition(), bot.getFoodData().getFoodLevel());
        }
    }

    /**
     * Spawn-egg entry point (design "스폰 에그(소모형)"): if no bot exists, spawn and consume
     * one egg. If a bot already exists, refuse (the single constraint). Returns true iff a bot
     * was spawned and an egg consumed.
     */
    public static boolean eggSpawn(ServerLevel level, BlockPos pos, ItemStack egg) {
        MinecraftServer server = level.getServer();
        if (BotWorldData.get(server).botExists() || exists()) {
            return false; // refused — caller shows the message
        }
        AICompanionBot bot = spawn(server, level, pos);
        if (bot == null) {
            return false;
        }
        egg.shrink(1); // consume one egg
        return true;
    }

    /**
     * Death release (design "사망"): the bot entity is gone; clear the residency reference,
     * close the dummy channel, and mark the record so a new egg is required to re-summon.
     * Inventory dropping is handled by the death hook before this runs.
     */
    public static void onDeathRelease(MinecraftServer server) {
        BotWorldData.get(server).set(false, null);
        current = null;
        if (botChannel != null) {
            botChannel.close();
            botChannel = null;
        }
        LOGGER.info("[BOT] death release — botExists=false");
    }

    /** Remove the bot from the world. Returns true if one was removed. */
    public static boolean despawn(MinecraftServer server) {
        if (!exists()) {
            return false;
        }
        server.getPlayerList().remove(current);
        LOGGER.info("[BOT] despawned uuid={}", current.getUUID());
        BotWorldData.get(server).set(false, null);
        current = null;
        if (botChannel != null) {
            botChannel.close();
            botChannel = null;
        }
        return true;
    }
}
