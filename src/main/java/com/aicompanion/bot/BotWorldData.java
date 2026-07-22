package com.aicompanion.bot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Persistent, world-saved record of the bot's existence (design 3.3 "존재 상태 관리").
 * The single source of truth for the summon duplicate-check, death release, and world-load restore.
 *
 * <p>Stored in the overworld's {@code DimensionDataStorage} under {@code aicompanion_bot}.</p>
 */
public class BotWorldData extends SavedData {

    private static final String NAME = "aicompanion_bot";

    private boolean botExists;
    @Nullable
    private UUID botUuid;

    public BotWorldData() {
    }

    public static BotWorldData load(CompoundTag tag) {
        BotWorldData data = new BotWorldData();
        data.botExists = tag.getBoolean("botExists");
        if (tag.hasUUID("botUUID")) {
            data.botUuid = tag.getUUID("botUUID");
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("botExists", botExists);
        if (botUuid != null) {
            tag.putUUID("botUUID", botUuid);
        }
        return tag;
    }

    /** Fetch (or create) the record from the overworld's data storage. */
    public static BotWorldData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(BotWorldData::load, BotWorldData::new, NAME);
    }

    public boolean botExists() {
        return botExists;
    }

    @Nullable
    public UUID botUuid() {
        return botUuid;
    }

    public void set(boolean exists, @Nullable UUID uuid) {
        this.botExists = exists;
        this.botUuid = uuid;
        setDirty();
    }
}
