package de.crystalcobra.playerworlds;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Persisted list of player worlds so they can be re-registered after a server restart. */
public class PlayerWorldsData extends SavedData {
    private static final String NAME = PlayerWorlds.MODID + "_worlds";

    public record Entry(UUID owner, WorldType type, long seed) {}

    private final Map<UUID, Entry> worlds = new HashMap<>();

    public static PlayerWorldsData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new Factory<>(PlayerWorldsData::new, PlayerWorldsData::load), NAME);
    }

    public Map<UUID, Entry> worlds() {
        return worlds;
    }

    public Entry get(UUID owner) {
        return worlds.get(owner);
    }

    public void put(Entry entry) {
        worlds.put(entry.owner(), entry);
        setDirty();
    }

    public void remove(UUID owner) {
        worlds.remove(owner);
        setDirty();
    }

    private static PlayerWorldsData load(CompoundTag tag, HolderLookup.Provider provider) {
        PlayerWorldsData data = new PlayerWorldsData();
        for (Tag t : tag.getList("worlds", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            UUID owner = c.getUUID("owner");
            WorldType type = WorldType.byName(c.getString("type"));
            data.worlds.put(owner, new Entry(owner, type, c.getLong("seed")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (Entry e : worlds.values()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("owner", e.owner());
            c.putString("type", e.type().name());
            c.putLong("seed", e.seed());
            list.add(c);
        }
        tag.put("worlds", list);
        return tag;
    }
}
