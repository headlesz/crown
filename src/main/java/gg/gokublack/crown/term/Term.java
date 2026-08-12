package gg.gokublack.crown.term;

import gg.gokublack.crown.powers.ScheduledEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One reign. Every monarch power writes here, and everything written here dies at the term
 * boundary — that expiry is the mechanical guarantee behind the design's "reversible within one
 * term" rule (spec 7).
 */
public final class Term {
    private final int index;
    private final UUID monarch;
    private final String monarchName;
    private final long startedAt;
    private long endsAt;

    private String name;
    private String genre = "";
    private final List<String> decrees = new ArrayList<>();
    private final List<ScheduledEvent> events = new ArrayList<>();
    private int titlesGranted;
    private boolean powersFrozen;

    public Term(int index, UUID monarch, String monarchName, long startedAt, long endsAt) {
        this.index = index;
        this.monarch = monarch;
        this.monarchName = monarchName;
        this.startedAt = startedAt;
        this.endsAt = endsAt;
        this.name = "Term " + index;
    }

    public int index() {
        return index;
    }

    public UUID monarch() {
        return monarch;
    }

    public String monarchName() {
        return monarchName;
    }

    public long startedAt() {
        return startedAt;
    }

    public long endsAt() {
        return endsAt;
    }

    public void setEndsAt(long endsAt) {
        this.endsAt = endsAt;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String genre() {
        return genre;
    }

    public void setGenre(String genre) {
        this.genre = genre;
    }

    public List<String> decrees() {
        return decrees;
    }

    public List<ScheduledEvent> events() {
        return events;
    }

    public int titlesGranted() {
        return titlesGranted;
    }

    public void incrementTitlesGranted() {
        this.titlesGranted++;
    }

    /**
     * True once the election for the successor opens. Frozen powers mean no new decrees, events,
     * titles or commissions; everything already declared stands until the term ends (spec 4.1).
     */
    public boolean powersFrozen() {
        return powersFrozen;
    }

    public void setPowersFrozen(boolean powersFrozen) {
        this.powersFrozen = powersFrozen;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("index", index);
        tag.putUUID("monarch", monarch);
        tag.putString("monarchName", monarchName);
        tag.putLong("startedAt", startedAt);
        tag.putLong("endsAt", endsAt);
        tag.putString("name", name);
        tag.putString("genre", genre);
        tag.putInt("titlesGranted", titlesGranted);
        tag.putBoolean("powersFrozen", powersFrozen);

        ListTag decreeList = new ListTag();
        for (String d : decrees) {
            decreeList.add(StringTag.valueOf(d));
        }
        tag.put("decrees", decreeList);

        ListTag eventList = new ListTag();
        for (ScheduledEvent e : events) {
            eventList.add(e.save());
        }
        tag.put("events", eventList);
        return tag;
    }

    public static Term load(CompoundTag tag) {
        Term term = new Term(
                tag.getInt("index"),
                tag.getUUID("monarch"),
                tag.getString("monarchName"),
                tag.getLong("startedAt"),
                tag.getLong("endsAt"));
        term.name = tag.getString("name");
        term.genre = tag.getString("genre");
        term.titlesGranted = tag.getInt("titlesGranted");
        term.powersFrozen = tag.getBoolean("powersFrozen");

        ListTag decreeList = tag.getList("decrees", Tag.TAG_STRING);
        for (int i = 0; i < decreeList.size(); i++) {
            term.decrees.add(decreeList.getString(i));
        }

        ListTag eventList = tag.getList("events", Tag.TAG_COMPOUND);
        for (int i = 0; i < eventList.size(); i++) {
            term.events.add(ScheduledEvent.load(eventList.getCompound(i)));
        }
        return term;
    }
}
