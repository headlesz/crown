package gg.gokublack.crown.election;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The ballots themselves, with no persistence and no Minecraft types attached, so the rules that
 * matter — one ballot per voter, recasting replaces — can be tested directly (spec 13.1).
 */
public final class BallotBox {

    private final Map<UUID, Ballot> ballots = new LinkedHashMap<>();

    /** Casts or replaces this voter's ballot. Last write wins until the election closes. */
    public void cast(UUID voter, UUID candidate, long at) {
        ballots.put(voter, new Ballot(voter, candidate, at));
    }

    public Map<UUID, Ballot> asMap() {
        return ballots;
    }

    public Collection<Ballot> all() {
        return ballots.values();
    }

    public int size() {
        return ballots.size();
    }

    public boolean hasVoted(UUID voter) {
        return ballots.containsKey(voter);
    }

    public void clear() {
        ballots.clear();
    }
}
