package de.crystalcobra.playerworlds;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Persisted list of player worlds so they can be re-registered after a server restart. */
public class PlayerWorldsData extends SavedData {
    private static final String NAME = PlayerWorlds.MODID + "_worlds";

    public static final class Entry {
        private final UUID owner;
        private final WorldType type;
        private final long seed;
        private final Set<UUID> members = new LinkedHashSet<>();
        private boolean locked = true;
        private String name = "";

        public Entry(UUID owner, WorldType type, long seed) {
            this.owner = owner;
            this.type = type;
            this.seed = seed;
        }

        public UUID owner() { return owner; }
        public WorldType type() { return type; }
        public long seed() { return seed; }
        public Set<UUID> members() { return members; }
        public boolean locked() { return locked; }
        public void setLocked(boolean locked) { this.locked = locked; }
        public String name() { return name; }
        public void setName(String name) { this.name = name; }

        /** Whether {@code player} may enter this world. */
        public boolean canEnter(UUID player) {
            return !locked || owner.equals(player) || members.contains(player);
        }
    }

    private final Map<UUID, Entry> worlds = new HashMap<>();

    public static PlayerWorldsData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new Factory<>(PlayerWorldsData::new, PlayerWorldsData::load), NAME);
    }

    public Map<UUID, Entry> worlds() {
        return worlds;
    }

    @Nullable
    public Entry get(UUID owner) {
        return worlds.get(owner);
    }

    public void put(Entry entry) {
        worlds.put(entry.owner(), entry);
        setDirty();
    }

    /** Finds a world by its (case-insensitive) name. */
    @Nullable
    public Entry byName(String name) {
        for (Entry e : worlds.values()) {
            if (e.name.equalsIgnoreCase(name)) {
                return e;
            }
        }
        return null;
    }

    public void remove(UUID owner) {
        worlds.remove(owner);
        setDirty();
    }

    private static PlayerWorldsData load(CompoundTag tag, HolderLookup.Provider provider) {
        PlayerWorldsData data = new PlayerWorldsData();
        for (Tag t : tag.getList("worlds", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            Entry e = new Entry(c.getUUID("owner"), WorldType.byName(c.getString("type")), c.getLong("seed"));
            e.locked = !c.contains("locked") || c.getBoolean("locked");
            e.name = c.getString("name");
            for (Tag m : c.getList("members", Tag.TAG_INT_ARRAY)) {
                e.members.add(NbtUtils.loadUUID(m));
            }
            data.worlds.put(e.owner(), e);
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
            c.putBoolean("locked", e.locked);
            c.putString("name", e.name);
            ListTag members = new ListTag();
            for (UUID m : e.members) {
                members.add(NbtUtils.createUUID(m));
            }
            c.put("members", members);
            list.add(c);
        }
        tag.put("worlds", list);
        return tag;
    }
}
