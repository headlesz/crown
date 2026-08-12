package gg.gokublack.crown.powers;

import net.minecraft.nbt.CompoundTag;

/**
 * A community event declared by the monarch. Purely social: Crown announces it and counts down
 * to it, and has no mechanical effect whatsoever (spec 7).
 */
public final class ScheduledEvent {
    private final String name;
    private final String description;
    private final long startsAt;
    private boolean countdownStarted;
    private boolean started;

    public ScheduledEvent(String name, String description, long startsAt) {
        this.name = name;
        this.description = description;
        this.startsAt = startsAt;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public long startsAt() {
        return startsAt;
    }

    public boolean countdownStarted() {
        return countdownStarted;
    }

    public void markCountdownStarted() {
        this.countdownStarted = true;
    }

    public boolean started() {
        return started;
    }

    public void markStarted() {
        this.started = true;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.putString("description", description);
        tag.putLong("startsAt", startsAt);
        tag.putBoolean("countdownStarted", countdownStarted);
        tag.putBoolean("started", started);
        return tag;
    }

    public static ScheduledEvent load(CompoundTag tag) {
        ScheduledEvent e = new ScheduledEvent(
                tag.getString("name"),
                tag.getString("description"),
                tag.getLong("startsAt"));
        e.countdownStarted = tag.getBoolean("countdownStarted");
        e.started = tag.getBoolean("started");
        return e;
    }
}
