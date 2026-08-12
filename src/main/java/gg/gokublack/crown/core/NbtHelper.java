package gg.gokublack.crown.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Small NBT conveniences shared by the persistent model. */
public final class NbtHelper {

    private NbtHelper() {
    }

    public static void putUuidSet(CompoundTag tag, String key, Collection<UUID> ids) {
        ListTag list = new ListTag();
        for (UUID id : ids) {
            list.add(StringTag.valueOf(id.toString()));
        }
        tag.put(key, list);
    }

    public static Set<UUID> getUuidSet(CompoundTag tag, String key) {
        Set<UUID> out = new LinkedHashSet<>();
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            try {
                out.add(UUID.fromString(list.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // A malformed id is dropped rather than failing the whole world load.
            }
        }
        return out;
    }
}
