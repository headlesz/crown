package gg.gokublack.crown.election;

import gg.gokublack.crown.core.CrownConfig;
import gg.gokublack.crown.core.CrownState;
import gg.gokublack.crown.core.Players;
import gg.gokublack.crown.term.Term;
import gg.gokublack.crown.term.TermPhase;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Eligibility rules and ballot casting (spec 5.1). */
public final class ElectionManager {

    /** Why a ballot was refused, or {@link #OK} when it was accepted. */
    public enum CastResult {
        OK,
        NO_ELECTION,
        NOT_ELIGIBLE_VOTER,
        NOT_ELIGIBLE_CANDIDATE,
        SELF_VOTE_DISALLOWED
    }

    private ElectionManager() {
    }

    /**
     * Who may vote: anyone who logged in during the current term, or every whitelisted player if
     * {@code allow_all_whitelisted_voters} is set.
     */
    public static Set<UUID> electorate(MinecraftServer server, CrownState state) {
        if (CrownConfig.ALLOW_ALL_WHITELISTED_VOTERS.get()) {
            return new LinkedHashSet<>(Players.roster(server, state).keySet());
        }
        return new LinkedHashSet<>(state.activeThisTerm());
    }

    /**
     * Who may stand: any roster member except the sitting monarch (the no-back-to-back rule).
     * During a runoff the field narrows to exactly the tied candidates.
     */
    public static Map<UUID, String> candidates(MinecraftServer server, CrownState state) {
        Map<UUID, String> roster = Players.roster(server, state);
        Term current = state.currentTerm();
        if (current != null) {
            roster.remove(current.monarch());
        }
        Election election = state.election();
        if (election != null && election.runoff()) {
            Map<UUID, String> narrowed = new LinkedHashMap<>();
            for (UUID id : election.runoffCandidates()) {
                narrowed.put(id, roster.getOrDefault(id, Players.nameOf(server, id)));
            }
            return narrowed;
        }
        return roster;
    }

    /** Candidate ids only, for the tally's eligibility filter. */
    public static Set<UUID> eligibleCandidateIds(MinecraftServer server, CrownState state) {
        return new LinkedHashSet<>(candidates(server, state).keySet());
    }

    public static CastResult cast(MinecraftServer server, CrownState state, UUID voter, UUID candidate) {
        Election election = state.election();
        if (election == null || state.phase() != TermPhase.ELECTION) {
            return CastResult.NO_ELECTION;
        }
        if (!election.electorate().contains(voter)) {
            return CastResult.NOT_ELIGIBLE_VOTER;
        }
        if (voter.equals(candidate) && !CrownConfig.ALLOW_SELF_VOTE.get()) {
            return CastResult.SELF_VOTE_DISALLOWED;
        }
        if (!eligibleCandidateIds(server, state).contains(candidate)) {
            return CastResult.NOT_ELIGIBLE_CANDIDATE;
        }
        election.cast(voter, candidate, System.currentTimeMillis());
        state.setDirty();
        return CastResult.OK;
    }

    public static int quorumRequired(Election election) {
        return Tally.quorumRequired(election.electorate().size(), CrownConfig.QUORUM_FRACTION.get());
    }
}
