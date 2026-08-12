package gg.gokublack.crown.endgate;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * The season finale's two-key state (spec 6.2): a monarch proposes, an operator confirms, and
 * only then does the gate open for a bounded window.
 */
public final class RaidState {

    public enum Status {
        /** No proposal on the table. */
        NONE,
        /** The monarch has proposed a window; awaiting an operator. */
        PENDING,
        /** Confirmed and scheduled, but not yet started. */
        CONFIRMED,
        /** The gate is currently open. */
        OPEN,
        /** The window has run and closed. */
        CLOSED,
        /** An operator refused the proposal. */
        DENIED
    }

    private Status status = Status.NONE;
    private UUID proposedBy;
    private String proposedByName = "";
    private long startsAt;
    private long endsAt;
    private String denyReason = "";
    private boolean announcedOpen;

    public Status status() {
        return status;
    }

    public UUID proposedBy() {
        return proposedBy;
    }

    public String proposedByName() {
        return proposedByName;
    }

    public long startsAt() {
        return startsAt;
    }

    public long endsAt() {
        return endsAt;
    }

    public String denyReason() {
        return denyReason;
    }

    public boolean announcedOpen() {
        return announcedOpen;
    }

    public void propose(UUID monarch, String monarchName, long startsAt, long endsAt) {
        this.status = Status.PENDING;
        this.proposedBy = monarch;
        this.proposedByName = monarchName;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.denyReason = "";
        this.announcedOpen = false;
    }

    public void confirm() {
        this.status = Status.CONFIRMED;
    }

    public void deny(String reason) {
        this.status = Status.DENIED;
        this.denyReason = reason;
    }

    public void open() {
        this.status = Status.OPEN;
        this.announcedOpen = true;
    }

    public void close(long now) {
        this.status = Status.CLOSED;
        this.endsAt = Math.min(this.endsAt, now);
    }

    public void reset() {
        this.status = Status.NONE;
        this.proposedBy = null;
        this.proposedByName = "";
        this.startsAt = 0L;
        this.endsAt = 0L;
        this.denyReason = "";
        this.announcedOpen = false;
    }

    /** True while the gate should let players through. */
    public boolean gateOpen(long now) {
        return status == Status.OPEN && now < endsAt;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("status", status.name());
        if (proposedBy != null) {
            tag.putUUID("proposedBy", proposedBy);
        }
        tag.putString("proposedByName", proposedByName);
        tag.putLong("startsAt", startsAt);
        tag.putLong("endsAt", endsAt);
        tag.putString("denyReason", denyReason);
        tag.putBoolean("announcedOpen", announcedOpen);
        return tag;
    }

    public static RaidState load(CompoundTag tag) {
        RaidState r = new RaidState();
        try {
            r.status = Status.valueOf(tag.getString("status"));
        } catch (IllegalArgumentException e) {
            r.status = Status.NONE;
        }
        if (tag.hasUUID("proposedBy")) {
            r.proposedBy = tag.getUUID("proposedBy");
        }
        r.proposedByName = tag.getString("proposedByName");
        r.startsAt = tag.getLong("startsAt");
        r.endsAt = tag.getLong("endsAt");
        r.denyReason = tag.getString("denyReason");
        r.announcedOpen = tag.getBoolean("announcedOpen");
        return r;
    }
}
