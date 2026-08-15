package gg.gokublack.crown.core;

import gg.gokublack.crown.Crown;
import gg.gokublack.crown.election.CandidateRecord;
import gg.gokublack.crown.election.Election;
import gg.gokublack.crown.endgate.RaidState;
import gg.gokublack.crown.prestige.LedgerEntry;
import gg.gokublack.crown.term.Term;
import gg.gokublack.crown.term.TermPhase;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * All Crown state, persisted as a single {@link SavedData} attachment on the overworld
 * (spec 3.1).
 *
 * <p>Serialisation is hand-written NBT rather than Codec-to-NBT. The shapes here (a sealed ledger
 * hierarchy, nested per-term event lists, maps keyed by UUID) are cheap to write by hand and
 * expensive to express as Codecs, and the {@link #dataVersion} field plus {@link #migrate} give
 * the same explicit migration story the spec asks for.
 */
public final class CrownState extends SavedData {
    public static final String FILE_ID = "crown_state";
    public static final int CURRENT_DATA_VERSION = 2;

    private int dataVersion = CURRENT_DATA_VERSION;
    private TermPhase phase = TermPhase.INTERREGNUM;
    private int termIndex;

    @Nullable
    private Term currentTerm;
    private final List<Term> pastTerms = new ArrayList<>();
    @Nullable
    private Election election;
    private final List<LedgerEntry> ledger = new ArrayList<>();
    private RaidState raid = new RaidState();
    private int raidsUsedThisCampaign;

    private String packUrl = "";
    private String packSha1 = "";

    /** Everyone who logged in during the current term: the electorate (spec 5.1). */
    private final Set<UUID> activeThisTerm = new LinkedHashSet<>();
    /**
     * Open commissions in the current term, in the order they were issued. A commission is a
     * bounty: it names no builder, and anyone may complete it. Players address them by their
     * 1-based position, as shown by {@code /crown}.
     */
    private final List<String> openCommissions = new ArrayList<>();

    /**
     * Monotonically increasing. Guards against a backwards clock jump re-firing a transition
     * that has already happened (spec 4.4).
     */
    private long lastTransitionId;
    private long monarchLastSeen;
    private boolean monarchAfkWarned;
    /** False until the sitting monarch has actually been handed their briefing (spec 4.4). */
    private boolean monarchBriefed;

    public CrownState() {
    }

    public static SavedData.Factory<CrownState> factory() {
        return new SavedData.Factory<>(CrownState::new, CrownState::load, null);
    }

    public static CrownState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), FILE_ID);
    }

    // ---------------------------------------------------------------- accessors

    public TermPhase phase() {
        return phase;
    }

    public void setPhase(TermPhase phase) {
        this.phase = phase;
        setDirty();
    }

    public int termIndex() {
        return termIndex;
    }

    public void setTermIndex(int termIndex) {
        this.termIndex = termIndex;
        setDirty();
    }

    @Nullable
    public Term currentTerm() {
        return currentTerm;
    }

    public void setCurrentTerm(@Nullable Term term) {
        this.currentTerm = term;
        setDirty();
    }

    public List<Term> pastTerms() {
        return pastTerms;
    }

    @Nullable
    public Election election() {
        return election;
    }

    public void setElection(@Nullable Election election) {
        this.election = election;
        setDirty();
    }

    public List<LedgerEntry> ledger() {
        return ledger;
    }

    public void appendLedger(LedgerEntry entry) {
        ledger.add(entry);
        setDirty();
    }

    public RaidState raid() {
        return raid;
    }

    public int raidsUsedThisCampaign() {
        return raidsUsedThisCampaign;
    }

    public void incrementRaidsUsed() {
        this.raidsUsedThisCampaign++;
        setDirty();
    }

    public String packUrl() {
        return packUrl;
    }

    public String packSha1() {
        return packSha1;
    }

    public void setPack(String url, String sha1) {
        this.packUrl = url;
        this.packSha1 = sha1;
        setDirty();
    }

    public boolean hasPack() {
        return !packUrl.isEmpty();
    }

    public Set<UUID> activeThisTerm() {
        return activeThisTerm;
    }

    public void markActive(UUID player) {
        if (activeThisTerm.add(player)) {
            setDirty();
        }
    }

    public List<String> openCommissions() {
        return openCommissions;
    }

    public long lastTransitionId() {
        return lastTransitionId;
    }

    public long nextTransitionId() {
        this.lastTransitionId++;
        setDirty();
        return lastTransitionId;
    }

    public long monarchLastSeen() {
        return monarchLastSeen;
    }

    public void setMonarchLastSeen(long monarchLastSeen) {
        this.monarchLastSeen = monarchLastSeen;
        this.monarchAfkWarned = false;
        setDirty();
    }

    public boolean monarchAfkWarned() {
        return monarchAfkWarned;
    }

    public boolean monarchBriefed() {
        return monarchBriefed;
    }

    public void setMonarchBriefed(boolean briefed) {
        this.monarchBriefed = briefed;
        setDirty();
    }

    public void setMonarchAfkWarned(boolean warned) {
        this.monarchAfkWarned = warned;
        setDirty();
    }

    public boolean isMonarch(UUID player) {
        return currentTerm != null && currentTerm.monarch().equals(player);
    }

    /** Career records across the whole campaign, used by the tie-break (spec 4.4). */
    public Map<UUID, CandidateRecord> careerRecords() {
        Map<UUID, int[]> acc = new HashMap<>();
        List<Term> all = new ArrayList<>(pastTerms);
        if (currentTerm != null) {
            all.add(currentTerm);
        }
        for (Term t : all) {
            int[] rec = acc.computeIfAbsent(t.monarch(), k -> new int[]{0, -1});
            rec[0]++;
            rec[1] = Math.max(rec[1], t.index());
        }
        Map<UUID, CandidateRecord> out = new HashMap<>();
        acc.forEach((id, rec) -> out.put(id, new CandidateRecord(id, rec[0], rec[1])));
        return out;
    }

    // ---------------------------------------------------------------- persistence

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("dataVersion", CURRENT_DATA_VERSION);
        tag.putString("phase", phase.name());
        tag.putInt("termIndex", termIndex);
        tag.putLong("lastTransitionId", lastTransitionId);
        tag.putLong("monarchLastSeen", monarchLastSeen);
        tag.putBoolean("monarchAfkWarned", monarchAfkWarned);
        tag.putBoolean("monarchBriefed", monarchBriefed);
        tag.putInt("raidsUsedThisCampaign", raidsUsedThisCampaign);
        tag.putString("packUrl", packUrl);
        tag.putString("packSha1", packSha1);
        tag.put("raid", raid.save());

        if (currentTerm != null) {
            tag.put("currentTerm", currentTerm.save());
        }
        if (election != null) {
            tag.put("election", election.save());
        }

        ListTag past = new ListTag();
        for (Term t : pastTerms) {
            past.add(t.save());
        }
        tag.put("pastTerms", past);

        ListTag ledgerList = new ListTag();
        for (LedgerEntry e : ledger) {
            ledgerList.add(LedgerEntry.save(e));
        }
        tag.put("ledger", ledgerList);

        NbtHelper.putUuidSet(tag, "activeThisTerm", activeThisTerm);

        ListTag commissions = new ListTag();
        for (String text : openCommissions) {
            commissions.add(StringTag.valueOf(text));
        }
        tag.put("openCommissionList", commissions);
        return tag;
    }

    public static CrownState load(CompoundTag tag, HolderLookup.Provider registries) {
        CrownState s = new CrownState();
        s.readFrom(tag);
        return s;
    }

    /**
     * Point-in-time copy of the whole state, used to roll back a transition that fails partway
     * through (spec 4.3).
     */
    public CompoundTag snapshot() {
        return save(new CompoundTag(), null);
    }

    /** Restores a {@link #snapshot()}, discarding everything written since it was taken. */
    public void restore(CompoundTag tag) {
        currentTerm = null;
        election = null;
        pastTerms.clear();
        ledger.clear();
        activeThisTerm.clear();
        openCommissions.clear();
        raid = new RaidState();
        readFrom(tag);
        setDirty();
    }

    private void readFrom(CompoundTag tag) {
        CrownState s = this;
        s.dataVersion = tag.contains("dataVersion") ? tag.getInt("dataVersion") : 1;
        migrate(tag, s.dataVersion);

        s.phase = TermPhase.byName(tag.getString("phase"));
        s.termIndex = tag.getInt("termIndex");
        s.lastTransitionId = tag.getLong("lastTransitionId");
        s.monarchLastSeen = tag.getLong("monarchLastSeen");
        s.monarchAfkWarned = tag.getBoolean("monarchAfkWarned");
        s.monarchBriefed = tag.getBoolean("monarchBriefed");
        s.raidsUsedThisCampaign = tag.getInt("raidsUsedThisCampaign");
        s.packUrl = tag.getString("packUrl");
        s.packSha1 = tag.getString("packSha1");
        if (tag.contains("raid")) {
            s.raid = RaidState.load(tag.getCompound("raid"));
        }
        if (tag.contains("currentTerm")) {
            s.currentTerm = Term.load(tag.getCompound("currentTerm"));
        }
        if (tag.contains("election")) {
            s.election = Election.load(tag.getCompound("election"));
        }

        ListTag past = tag.getList("pastTerms", Tag.TAG_COMPOUND);
        for (int i = 0; i < past.size(); i++) {
            s.pastTerms.add(Term.load(past.getCompound(i)));
        }

        ListTag ledgerList = tag.getList("ledger", Tag.TAG_COMPOUND);
        for (int i = 0; i < ledgerList.size(); i++) {
            s.ledger.add(LedgerEntry.load(ledgerList.getCompound(i)));
        }

        s.activeThisTerm.addAll(NbtHelper.getUuidSet(tag, "activeThisTerm"));

        ListTag commissions = tag.getList("openCommissionList", Tag.TAG_STRING);
        for (int i = 0; i < commissions.size(); i++) {
            s.openCommissions.add(commissions.getString(i));
        }
        s.dataVersion = CURRENT_DATA_VERSION;
    }

    /**
     * Explicit, in-place schema migrations. Each step upgrades the tag from version {@code n} to
     * {@code n + 1}; add a case here whenever the on-disk shape changes.
     */
    private static void migrate(CompoundTag tag, int fromVersion) {
        if (fromVersion > CURRENT_DATA_VERSION) {
            Crown.LOGGER.warn(
                    "crown_state was written by a newer Crown (dataVersion {} > {}); loading anyway",
                    fromVersion, CURRENT_DATA_VERSION);
            return;
        }
        int version = fromVersion;
        while (version < CURRENT_DATA_VERSION) {
            switch (version) {
                case 1 -> migrate1to2(tag);
                default -> {
                }
            }
            version++;
        }
    }

    /**
     * v1 stored open commissions as builder-keyed compounds; v2 commissions are open to anyone,
     * so only the briefs survive.
     */
    private static void migrate1to2(CompoundTag tag) {
        ListTag old = tag.getList("openCommissions", Tag.TAG_COMPOUND);
        ListTag texts = new ListTag();
        for (int i = 0; i < old.size(); i++) {
            texts.add(StringTag.valueOf(old.getCompound(i).getString("text")));
        }
        tag.put("openCommissionList", texts);
        tag.remove("openCommissions");
    }
}
