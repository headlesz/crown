package gg.gokublack.crown.prestige;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * Append-only institutional memory (spec 10). Crown never derives a score from these entries and
 * never ranks players by them: the ledger is a museum plaque, not a leaderboard.
 *
 * <p>Display names are stored alongside UUIDs so history stays readable after a rename or after a
 * player leaves the server for good.
 */
public sealed interface LedgerEntry {

    /** When the entry was appended, epoch millis. */
    long at();

    /** Term this entry belongs to, or {@code -1} for entries made outside a reign. */
    int termIndex();

    String type();

    void write(CompoundTag tag);

    record ReignServed(UUID monarch, String monarchName, int termIndex, long at) implements LedgerEntry {
        @Override
        public String type() {
            return "reign_served";
        }

        @Override
        public void write(CompoundTag tag) {
            tag.putUUID("monarch", monarch);
            tag.putString("monarchName", monarchName);
        }
    }

    record TitleGranted(UUID recipient, String recipientName, String title, UUID grantedBy,
                        String grantedByName, int termIndex, long at) implements LedgerEntry {
        @Override
        public String type() {
            return "title_granted";
        }

        @Override
        public void write(CompoundTag tag) {
            tag.putUUID("recipient", recipient);
            tag.putString("recipientName", recipientName);
            tag.putString("title", title);
            tag.putUUID("grantedBy", grantedBy);
            tag.putString("grantedByName", grantedByName);
        }
    }

    record CommissionIssued(UUID builder, String builderName, String text, int termIndex, long at)
            implements LedgerEntry {
        @Override
        public String type() {
            return "commission_issued";
        }

        @Override
        public void write(CompoundTag tag) {
            tag.putUUID("builder", builder);
            tag.putString("builderName", builderName);
            tag.putString("text", text);
        }
    }

    record CommissionCompleted(UUID builder, String builderName, String text, int termIndex, long at)
            implements LedgerEntry {
        @Override
        public String type() {
            return "commission_completed";
        }

        @Override
        public void write(CompoundTag tag) {
            tag.putUUID("builder", builder);
            tag.putString("builderName", builderName);
            tag.putString("text", text);
        }
    }

    /** A correction. History is never edited; corrections are appended (spec 7.1). */
    record AdminNote(String text, UUID author, String authorName, int termIndex, long at)
            implements LedgerEntry {
        @Override
        public String type() {
            return "admin_note";
        }

        @Override
        public void write(CompoundTag tag) {
            tag.putString("text", text);
            tag.putUUID("author", author);
            tag.putString("authorName", authorName);
        }
    }

    static CompoundTag save(LedgerEntry entry) {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", entry.type());
        tag.putLong("at", entry.at());
        tag.putInt("termIndex", entry.termIndex());
        entry.write(tag);
        return tag;
    }

    static LedgerEntry load(CompoundTag tag) {
        long at = tag.getLong("at");
        int termIndex = tag.getInt("termIndex");
        return switch (tag.getString("type")) {
            case "reign_served" -> new ReignServed(
                    tag.getUUID("monarch"), tag.getString("monarchName"), termIndex, at);
            case "title_granted" -> new TitleGranted(
                    tag.getUUID("recipient"), tag.getString("recipientName"), tag.getString("title"),
                    tag.getUUID("grantedBy"), tag.getString("grantedByName"), termIndex, at);
            case "commission_issued" -> new CommissionIssued(
                    tag.getUUID("builder"), tag.getString("builderName"), tag.getString("text"),
                    termIndex, at);
            case "commission_completed" -> new CommissionCompleted(
                    tag.getUUID("builder"), tag.getString("builderName"), tag.getString("text"),
                    termIndex, at);
            default -> new AdminNote(
                    tag.getString("text"), tag.getUUID("author"), tag.getString("authorName"),
                    termIndex, at);
        };
    }
}
