package gg.gokublack.crown.election;

import java.util.Set;
import java.util.UUID;

/** Outcome of counting an election (spec 5.1, 4.4). */
public sealed interface TallyResult {

    /** A clean result: this candidate takes the throne. */
    record Winner(UUID candidate, int votes, int totalBallots, String tieBreakNote) implements TallyResult {
    }

    /** Not enough of the electorate voted. Caller extends the window or falls to interregnum. */
    record NoQuorum(int ballots, int required) implements TallyResult {
    }

    /** A tie the deterministic stages could not settle: run a runoff between exactly these. */
    record RunoffNeeded(Set<UUID> tied) implements TallyResult {
    }

    /** Nobody stood or nobody voted for an eligible candidate. */
    record NoCandidates() implements TallyResult {
    }
}
