package gg.gokublack.crown.election;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * A single secret ballot. Recasting replaces the previous ballot (last write wins) until the
 * election closes. Voter identity never leaves the server: announcements publish counts only,
 * and the JSON export hashes the voter (spec 5.2).
 */
public record Ballot(UUID voter, UUID candidate, long castAt) {

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("voter", voter);
        tag.putUUID("candidate", candidate);
        tag.putLong("castAt", castAt);
        return tag;
    }

    public static Ballot load(CompoundTag tag) {
        return new Ballot(tag.getUUID("voter"), tag.getUUID("candidate"), tag.getLong("castAt"));
    }
}
