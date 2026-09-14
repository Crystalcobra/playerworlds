package de.crystalcobra.playerworlds;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Creates, loads and deletes per-player dimensions at runtime. */
public final class WorldManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ChunkProgressListener NO_PROGRESS = new ChunkProgressListener() {
        @Override public void updateSpawnPos(ChunkPos pos) {}
        @Override public void onStatusChange(ChunkPos pos, @Nullable ChunkStatus status) {}
        @Override public void start() {}
        @Override public void stop() {}
    };

    private WorldManager() {}

    public static ResourceKey<Level> keyFor(UUID owner) {
        return ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(PlayerWorlds.MODID, owner.toString()));
    }

    public static boolean hasWorld(MinecraftServer server, UUID owner) {
        return PlayerWorldsData.get(server).get(owner) != null;
    }

    @Nullable
    public static ServerLevel getWorld(MinecraftServer server, UUID owner) {
        return server.getLevel(keyFor(owner));
    }

    /** Creates a brand-new world for {@code owner}; caller must ensure none exists yet. */
    public static ServerLevel createWorld(MinecraftServer server, UUID owner, WorldType type) {
        long seed = server.overworld().getRandom().nextLong();
        PlayerWorldsData.Entry entry = new PlayerWorldsData.Entry(owner, type, seed);
        PlayerWorldsData.get(server).put(entry);
        return loadWorld(server, entry);
    }

    /** Re-registers every persisted player world after a server (re)start. */
    public static void restoreAll(MinecraftServer server) {
        for (PlayerWorldsData.Entry entry : PlayerWorldsData.get(server).worlds().values()) {
            if (server.getLevel(keyFor(entry.owner())) == null) {
                loadWorld(server, entry);
            }
        }
    }

    private static ServerLevel loadWorld(MinecraftServer server, PlayerWorldsData.Entry entry) {
        ResourceKey<Level> key = keyFor(entry.owner());
        ServerLevel overworld = server.overworld();
        LevelStem stem = new LevelStem(overworldStem(server).type(), generatorFor(server, entry.type()));
        DerivedLevelData levelData = new DerivedLevelData(server.getWorldData(), server.getWorldData().overworldData());
        long seed = entry.seed();

        ServerLevel level = new ServerLevel(server, server.executor, server.storageSource, levelData, key, stem,
                NO_PROGRESS, false, BiomeManager.obfuscateSeed(seed), List.of(), false, null) {
            @Override
            public long getSeed() {
                return seed;
            }
        };

        overworld.getWorldBorder().addListener(new BorderChangeListener.DelegateBorderChangeListener(level.getWorldBorder()));
        server.forgeGetWorldMap().put(key, level);
        server.markWorldsDirty();
        NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
        LOGGER.info("Loaded player world {}", key.location());
        return level;
    }

    /** Unloads the world, evacuates players to the overworld spawn and deletes it from disk. */
    public static void deleteWorld(MinecraftServer server, UUID owner) {
        ResourceKey<Level> key = keyFor(owner);
        ServerLevel level = server.getLevel(key);
        if (level != null) {
            ServerLevel overworld = server.overworld();
            for (ServerPlayer player : List.copyOf(level.players())) {
                BlockPos spawn = overworld.getSharedSpawnPos();
                player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                        player.getYRot(), player.getXRot());
            }
            try {
                level.save(null, false, true);
                level.close();
            } catch (IOException e) {
                LOGGER.warn("Failed to close player world {}", key.location(), e);
            }
            server.forgeGetWorldMap().remove(key);
            server.markWorldsDirty();
            NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));
        }

        PlayerWorldsData.get(server).remove(owner);

        Path dir = server.storageSource.getDimensionPath(key);
        try {
            FileUtils.deleteDirectory(dir.toFile());
        } catch (IOException e) {
            LOGGER.warn("Failed to delete world folder {}", dir, e);
        }
        LOGGER.info("Deleted player world {}", key.location());
    }

    /** Teleports the player to a safe spot near the world origin. */
    public static void teleportToWorld(ServerPlayer player, ServerLevel level) {
        BlockPos pos = findSafeSpawn(level);
        player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), player.getXRot());
    }

    private static BlockPos findSafeSpawn(ServerLevel level) {
        level.getChunk(0, 0);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
        if (y <= level.getMinBuildHeight()) {
            y = level.getSeaLevel();
        }
        return new BlockPos(0, y, 0);
    }

    private static LevelStem overworldStem(MinecraftServer server) {
        Registry<LevelStem> stems = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        return stems.getOrThrow(LevelStem.OVERWORLD);
    }

    private static ChunkGenerator generatorFor(MinecraftServer server, WorldType type) {
        return switch (type) {
            case NORMAL -> overworldStem(server).generator();
            case FLAT -> new FlatLevelSource(FlatLevelGeneratorSettings.getDefault(
                    server.registryAccess().lookupOrThrow(Registries.BIOME),
                    server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET),
                    server.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE)));
        };
    }
}
