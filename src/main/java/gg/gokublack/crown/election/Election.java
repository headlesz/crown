package gg.gokublack.crown.election;

import gg.gokublack.crown.core.NbtHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** An open (or just-closed) ballot. One per succession. */
public final class Election {
    private final int forTermIndex;
    private final long openedAt;
    private long closesAt;
    private int extensions;
    private boolean runoff;

    /** Snapshot of who may vote, taken when the election opened. */
    private final Set<UUID> electorate = new LinkedHashSet<>();
    /** Empty outside a runoff; otherwise exactly the tied candidates. */
    private final Set<UUID> runoffCandidates = new LinkedHashSet<>();
    private final BallotBox ballotBox = new BallotBox();

    public Election(int forTermIndex, long openedAt, long closesAt) {
        this.forTermIndex = forTermIndex;
        this.openedAt = openedAt;
        this.closesAt = closesAt;
    }

    public int forTermIndex() {
        return forTermIndex;
    }

    public long openedAt() {
        return openedAt;
    }

    public long closesAt() {
        return closesAt;
    }

    public void setClosesAt(long closesAt) {
        this.closesAt = closesAt;
    }

    public int extensions() {
        return extensions;
    }

    public void incrementExtensions() {
        this.extensions++;
    }

    public boolean runoff() {
        return runoff;
    }

    public void beginRunoff(Set<UUID> candidates, long newCloseTime) {
        this.runoff = true;
        this.runoffCandidates.clear();
        this.runoffCandidates.addAll(candidates);
        // A runoff is a fresh vote between the tied candidates, so prior ballots are discarded.
        this.ballotBox.clear();
        this.closesAt = newCloseTime;
    }

    public Set<UUID> electorate() {
        return electorate;
    }

    public Set<UUID> runoffCandidates() {
        return runoffCandidates;
    }

    public Map<UUID, Ballot> ballots() {
        return ballotBox.asMap();
    }

    public BallotBox ballotBox() {
        return ballotBox;
    }

    /** Cast or replace a ballot. Last write wins until the election closes (spec 5.1). */
    public void cast(UUID voter, UUID candidate, long now) {
        ballotBox.cast(voter, candidate, now);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("forTermIndex", forTermIndex);
        tag.putLong("openedAt", openedAt);
        tag.putLong("closesAt", closesAt);
        tag.putInt("extensions", extensions);
        tag.putBoolean("runoff", runoff);
        NbtHelper.putUuidSet(tag, "electorate", electorate);
        NbtHelper.putUuidSet(tag, "runoffCandidates", runoffCandidates);

        ListTag ballotList = new ListTag();
        for (Ballot b : ballotBox.all()) {
            ballotList.add(b.save());
        }
        tag.put("ballots", ballotList);
        return tag;
    }

    public static Election load(CompoundTag tag) {
        Election e = new Election(
                tag.getInt("forTermIndex"),
                tag.getLong("openedAt"),
                tag.getLong("closesAt"));
        e.extensions = tag.getInt("extensions");
        e.runoff = tag.getBoolean("runoff");
        e.electorate.addAll(NbtHelper.getUuidSet(tag, "electorate"));
        e.runoffCandidates.addAll(NbtHelper.getUuidSet(tag, "runoffCandidates"));

        ListTag ballotList = tag.getList("ballots", Tag.TAG_COMPOUND);
        for (int i = 0; i < ballotList.size(); i++) {
            Ballot b = Ballot.load(ballotList.getCompound(i));
            e.ballotBox.cast(b.voter(), b.candidate(), b.castAt());
        }
        return e;
    }
}
