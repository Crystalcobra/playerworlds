package de.crystalcobra.playerworlds;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.timers.TimerCallbacks;
import net.minecraft.world.level.timers.TimerQueue;
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

    private static final int SPAWN_RADIUS = 2;
    private static final int SPAWN_CHUNK_COUNT = (2 * SPAWN_RADIUS + 1) * (2 * SPAWN_RADIUS + 1);
    private static final TicketType<ChunkPos> SPAWN_TICKET =
            TicketType.create(PlayerWorlds.MODID + "_spawn", Comparator.comparingLong(ChunkPos::toLong), 20 * 60 * 5);
    private static final List<PendingTeleport> PENDING = new ArrayList<>();

    private static final class PendingTeleport {
        private final UUID player;
        private final ResourceKey<Level> level;
        private int ticks;

        PendingTeleport(UUID player, ResourceKey<Level> level, int ticks) {
            this.player = player;
            this.level = level;
            this.ticks = ticks;
        }

        UUID player() { return player; }
        ResourceKey<Level> level() { return level; }
    }

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

    /** Owner UUID if {@code level} is a player world, otherwise null. */
    @Nullable
    public static UUID ownerOf(ResourceKey<Level> level) {
        if (!level.location().getNamespace().equals(PlayerWorlds.MODID)) {
            return null;
        }
        try {
            return UUID.fromString(level.location().getPath());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean canEnter(MinecraftServer server, ResourceKey<Level> level, UUID player) {
        UUID owner = ownerOf(level);
        if (owner == null) {
            return true;
        }
        PlayerWorldsData.Entry entry = PlayerWorldsData.get(server).get(owner);
        return entry == null || entry.canEnter(player);
    }

    /** Sends every player who is no longer allowed in the world back to the overworld spawn. */
    public static void evictUnauthorized(MinecraftServer server, UUID owner) {
        ServerLevel level = getWorld(server, owner);
        PlayerWorldsData.Entry entry = PlayerWorldsData.get(server).get(owner);
        if (level == null || entry == null) {
            return;
        }
        for (ServerPlayer player : List.copyOf(level.players())) {
            if (!entry.canEnter(player.getUUID())) {
                toOverworldSpawn(server, player);
                player.sendSystemMessage(Component.literal("Du wurdest aus dieser Welt entfernt.").withStyle(ChatFormatting.RED));
            }
        }
        PENDING.removeIf(p -> p.level().equals(level.dimension()) && !entry.canEnter(p.player()));
    }

    public static void toOverworldSpawn(MinecraftServer server, ServerPlayer player) {
        ServerLevel overworld = server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                player.getYRot(), player.getXRot());
    }

    /** Creates a brand-new world for {@code owner}; caller must ensure none exists yet. */
    public static ServerLevel createWorld(MinecraftServer server, UUID owner, WorldType type, String name) {
        long seed = server.overworld().getRandom().nextLong();
        PlayerWorldsData.Entry entry = new PlayerWorldsData.Entry(owner, type, seed);
        entry.setName(name);
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
        long initialDayTime = entry.dayTime() < 0 ? overworld.getDayTime() : entry.dayTime();
        PlayerWorldLevelData levelData = new PlayerWorldLevelData(server, initialDayTime);
        long seed = entry.seed();

        ServerLevel level = new ServerLevel(server, server.executor, server.storageSource, levelData, key, stem,
                NO_PROGRESS, false, BiomeManager.obfuscateSeed(seed), List.of(), true, null) {
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
            for (ServerPlayer player : List.copyOf(level.players())) {
                toOverworldSpawn(server, player);
            }
            PENDING.removeIf(p -> p.level().equals(key));
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
        Path expectedParent = server.storageSource.getDimensionPath(Level.OVERWORLD)
                .resolve("dimensions").resolve(PlayerWorlds.MODID).toAbsolutePath().normalize();
        if (!dir.toAbsolutePath().normalize().startsWith(expectedParent)) {
            LOGGER.error("Refusing to delete {} - not inside {}", dir, expectedParent);
            return;
        }
        try {
            FileUtils.deleteDirectory(dir.toFile());
        } catch (IOException e) {
            LOGGER.warn("Failed to delete world folder {}", dir, e);
        }
        LOGGER.info("Deleted player world {}", key.location());
    }

    /**
     * Queues a teleport to the world origin. The spawn chunks are generated asynchronously first so the
     * server thread never blocks on world generation; the actual teleport happens in {@link #tick}.
     */
    public static void teleportToWorld(ServerPlayer player, ServerLevel level) {
        level.getChunkSource().addRegionTicket(SPAWN_TICKET, ChunkPos.ZERO, SPAWN_RADIUS + 1, ChunkPos.ZERO);
        PENDING.removeIf(p -> p.player().equals(player.getUUID()));
        PENDING.add(new PendingTeleport(player.getUUID(), level.dimension(), 0));
        player.sendSystemMessage(Component.literal("Welt wird geladen, bitte warten...").withStyle(ChatFormatting.YELLOW));
    }

    /** Level data that shares everything with the overworld except its own day/night clock. */
    private static final class PlayerWorldLevelData extends DerivedLevelData {
        private final TimerQueue<MinecraftServer> scheduledEvents = new TimerQueue<>(TimerCallbacks.SERVER_CALLBACKS);
        private long gameTime;
        private long dayTime;

        PlayerWorldLevelData(MinecraftServer server, long dayTime) {
            super(server.getWorldData(), server.getWorldData().overworldData());
            this.gameTime = server.overworld().getGameTime();
            this.dayTime = dayTime;
        }

        @Override public long getGameTime() { return gameTime; }
        @Override public void setGameTime(long gameTime) { this.gameTime = gameTime; }
        @Override public long getDayTime() { return dayTime; }
        @Override public void setDayTime(long dayTime) { this.dayTime = dayTime; }
        @Override public TimerQueue<MinecraftServer> getScheduledEvents() { return scheduledEvents; }
    }

    private static final int SAVE_TIME_INTERVAL = 20 * 30;

    private static void saveDayTimes(MinecraftServer server) {
        if (server.getTickCount() % SAVE_TIME_INTERVAL != 0) {
            return;
        }
        PlayerWorldsData data = PlayerWorldsData.get(server);
        for (PlayerWorldsData.Entry entry : data.worlds().values()) {
            ServerLevel level = getWorld(server, entry.owner());
            if (level != null) {
                entry.setDayTime(level.getDayTime());
            }
        }
        data.setDirty();
    }

    public static void tick(MinecraftServer server) {
        saveDayTimes(server);
        if (PENDING.isEmpty()) {
            return;
        }
        Iterator<PendingTeleport> it = PENDING.iterator();
        while (it.hasNext()) {
            PendingTeleport pending = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(pending.player());
            ServerLevel level = server.getLevel(pending.level());
            if (player == null || level == null) {
                it.remove();
                continue;
            }
            int loaded = countLoadedSpawnChunks(level);
            if (loaded < SPAWN_CHUNK_COUNT) {
                pending.ticks++;
                if (pending.ticks % 100 == 0) {
                    player.sendSystemMessage(Component.literal("Welt wird generiert... " + loaded + "/" + SPAWN_CHUNK_COUNT + " Chunks")
                            .withStyle(ChatFormatting.GRAY));
                }
                continue;
            }
            it.remove();
            UUID owner = ownerOf(level.dimension());
            PlayerWorldsData.Entry entry = owner == null ? null : PlayerWorldsData.get(server).get(owner);
            BlockPos pos = findSafeSpawn(level, entry == null ? null : entry.type());
            player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), player.getXRot());
            if (entry != null) {
                player.sendSystemMessage(Component.literal("Willkommen in \"" + WorldCommands.displayName(server, entry) + "\"!")
                        .withStyle(ChatFormatting.GREEN));
            }
        }
    }

    private static int countLoadedSpawnChunks(ServerLevel level) {
        int loaded = 0;
        for (int x = -SPAWN_RADIUS; x <= SPAWN_RADIUS; x++) {
            for (int z = -SPAWN_RADIUS; z <= SPAWN_RADIUS; z++) {
                if (level.getChunkSource().getChunkNow(x, z) != null) {
                    loaded++;
                }
            }
        }
        return loaded;
    }

    private static final int VOID_PLATFORM_Y = 64;
    private static final int VOID_PLATFORM_RADIUS = 2;

    private static BlockPos findSafeSpawn(ServerLevel level, @Nullable WorldType type) {
        if (type == WorldType.VOID) {
            BlockPos center = new BlockPos(0, VOID_PLATFORM_Y, 0);
            if (level.getBlockState(center).isAir()) {
                for (int x = -VOID_PLATFORM_RADIUS; x <= VOID_PLATFORM_RADIUS; x++) {
                    for (int z = -VOID_PLATFORM_RADIUS; z <= VOID_PLATFORM_RADIUS; z++) {
                        level.setBlockAndUpdate(center.offset(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState());
                    }
                }
            }
            return center.above();
        }
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
            case NORMAL -> {
                ChunkGenerator overworld = overworldStem(server).generator();
                if (overworld instanceof NoiseBasedChunkGenerator noise) {
                    yield new NoiseBasedChunkGenerator(noise.getBiomeSource(), noise.generatorSettings());
                }
                yield overworld;
            }
            case VANILLA -> {
                var presets = server.registryAccess().registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
                var settings = server.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS);
                BiomeSource biomes = MultiNoiseBiomeSource.createFromList(
                        presets.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD).parameters());
                yield new NoiseBasedChunkGenerator(biomes, settings.getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD));
            }
            case FLAT -> new FlatLevelSource(FlatLevelGeneratorSettings.getDefault(
                    server.registryAccess().lookupOrThrow(Registries.BIOME),
                    server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET),
                    server.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE)));
            case VOID -> {
                Holder<Biome> plains = server.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
                yield new FlatLevelSource(new FlatLevelGeneratorSettings(Optional.empty(), plains, List.of()));
            }
        };
    }
}
