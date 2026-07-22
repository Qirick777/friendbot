package com.aicompanion.test;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Clean test-environment utilities (T0.2 spec): spawn entities at given coords,
 * give/drop items, and clear an area. Used by tests inside {@code setup()}.
 */
public class TestEnv {

    private final ServerLevel level;

    public TestEnv(ServerLevel level) {
        this.level = level;
    }

    public ServerLevel level() {
        return level;
    }

    /** Spawn an entity of the given type at pos. Returns the spawned entity (or null if blocked). */
    public <T extends Entity> T spawn(EntityType<T> type, BlockPos pos) {
        return type.spawn(level, pos, MobSpawnType.COMMAND);
    }

    /** Drop a pickup-able item entity at the center of pos. */
    public ItemEntity spawnItem(BlockPos pos, ItemStack stack) {
        ItemEntity e = new ItemEntity(level,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
        e.setDefaultPickUpDelay();
        level.addFreshEntity(e);
        return e;
    }

    /** Give an item stack directly into a player's inventory. */
    public boolean giveTo(Player player, ItemStack stack) {
        return player.getInventory().add(stack);
    }

    /** Remove every non-player entity within {@code radius} of center. Returns count removed. */
    public int clearEntities(BlockPos center, double radius) {
        AABB box = new AABB(center).inflate(radius);
        List<Entity> ents = level.getEntities((Entity) null, box, e -> !(e instanceof Player));
        int n = 0;
        for (Entity e : ents) {
            e.discard();
            n++;
        }
        return n;
    }

    /** Set every block in the cube center±radius to air. */
    public void clearBlocks(BlockPos center, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    level.setBlockAndUpdate(center.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }
}
