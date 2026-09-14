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

    public enum Access {
        /** Only the owner. */
        LOCKED,
        /** Owner and invited members. */
        MEMBERS,
        /** Everyone. */
        OPEN;

        static Access byName(String name) {
            for (Access a : values()) {
                if (a.name().equalsIgnoreCase(name)) {
                    return a;
                }
            }
            return MEMBERS;
        }
    }

    public static final class Entry {
        private final UUID owner;
        private final WorldType type;
        private final long seed;
        private final Set<UUID> members = new LinkedHashSet<>();
        private Access access = Access.MEMBERS;
        private String name = "";
        private long dayTime = -1;

        public Entry(UUID owner, WorldType type, long seed) {
            this.owner = owner;
            this.type = type;
            this.seed = seed;
        }

        public UUID owner() { return owner; }
        public WorldType type() { return type; }
        public long seed() { return seed; }
        public Set<UUID> members() { return members; }
        public Access access() { return access; }
        public void setAccess(Access access) { this.access = access; }
        public String name() { return name; }
        public void setName(String name) { this.name = name; }
        /** Saved day time, or -1 if the world has never been ticked. */
        public long dayTime() { return dayTime; }
        public void setDayTime(long dayTime) { this.dayTime = dayTime; }

        /** Whether {@code player} may enter this world. */
        public boolean canEnter(UUID player) {
            return owner.equals(player)
                    || access == Access.OPEN
                    || (access == Access.MEMBERS && members.contains(player));
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
            if (c.contains("access")) {
                e.access = Access.byName(c.getString("access"));
            } else if (c.contains("locked") && !c.getBoolean("locked")) {
                e.access = Access.OPEN;
            }
            e.name = c.getString("name");
            e.dayTime = c.contains("dayTime") ? c.getLong("dayTime") : -1;
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
            c.putString("access", e.access.name());
            c.putString("name", e.name);
            c.putLong("dayTime", e.dayTime);
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
