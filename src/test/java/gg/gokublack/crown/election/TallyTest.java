package gg.gokublack.crown.election;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 13.1: quorum boundaries, recasting, and all four tie-break stages. */
class TallyTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CARA = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID DAN = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    private static Set<UUID> eligible(UUID... ids) {
        return new LinkedHashSet<>(List.of(ids));
    }

    private static Ballot ballot(UUID voter, UUID candidate) {
        return new Ballot(voter, candidate, 0L);
    }

    // ---------------------------------------------------------------- quorum

    @Test
    void quorumRoundsUp() {
        // 4-8 players is the design's real range; these are the boundaries that matter.
        assertEquals(2, Tally.quorumRequired(4, 0.5));
        assertEquals(3, Tally.quorumRequired(6, 0.5));
        assertEquals(4, Tally.quorumRequired(8, 0.5));
        assertEquals(3, Tally.quorumRequired(5, 0.5));
    }

    @Test
    void quorumIsNeverZero() {
        assertEquals(1, Tally.quorumRequired(0, 0.5));
        assertEquals(1, Tally.quorumRequired(4, 0.0));
    }

    @Test
    void oneBallotShortOfQuorumFails() {
        List<Ballot> ballots = List.of(ballot(ALICE, BOB), ballot(BOB, CARA));
        TallyResult result = Tally.count(ballots, eligible(BOB, CARA), Map.of(), 6, 0.5, false, 2);
        TallyResult.NoQuorum noQuorum = assertInstanceOf(TallyResult.NoQuorum.class, result);
        assertEquals(2, noQuorum.ballots());
        assertEquals(3, noQuorum.required());
    }

    @Test
    void exactlyQuorumStands() {
        List<Ballot> ballots = List.of(ballot(ALICE, BOB), ballot(BOB, CARA), ballot(CARA, BOB));
        TallyResult result = Tally.count(ballots, eligible(BOB, CARA), Map.of(), 6, 0.5, false, 2);
        TallyResult.Winner winner = assertInstanceOf(TallyResult.Winner.class, result);
        assertEquals(BOB, winner.candidate());
        assertEquals(2, winner.votes());
    }

    @Test
    void ballotsForIneligibleCandidatesAreDiscarded() {
        // DAN is not standing, so his votes do not count toward quorum either.
        List<Ballot> ballots = List.of(ballot(ALICE, DAN), ballot(BOB, DAN), ballot(CARA, BOB));
        TallyResult result = Tally.count(ballots, eligible(BOB, CARA), Map.of(), 6, 0.5, false, 2);
        assertInstanceOf(TallyResult.NoQuorum.class, result);
    }

    // ---------------------------------------------------------------- recasting

    @Test
    void recastReplacesRatherThanAdds() {
        BallotBox box = new BallotBox();
        box.cast(ALICE, BOB, 1L);
        box.cast(ALICE, CARA, 2L);

        assertEquals(1, box.size(), "a voter must never hold two ballots");
        assertEquals(CARA, box.asMap().get(ALICE).candidate());
        assertEquals(2L, box.asMap().get(ALICE).castAt());
    }

    @Test
    void ballotBoxCountsOneVotePerVoter() {
        BallotBox box = new BallotBox();
        box.cast(ALICE, CARA, 1L);
        box.cast(BOB, CARA, 1L);
        box.cast(ALICE, BOB, 2L);

        assertEquals(2, box.size());
        assertTrue(box.hasVoted(ALICE));
    }

    // ---------------------------------------------------------------- tie-breaks

    @Test
    void stageAPrefersFewerCareerTerms() {
        Map<UUID, CandidateRecord> careers = new HashMap<>();
        careers.put(BOB, new CandidateRecord(BOB, 2, 3));
        careers.put(CARA, new CandidateRecord(CARA, 1, 1));

        TallyResult result = Tally.breakTie(eligible(BOB, CARA), careers, 2, 4, false, 5);
        TallyResult.Winner winner = assertInstanceOf(TallyResult.Winner.class, result);
        assertEquals(CARA, winner.candidate());
        assertTrue(winner.tieBreakNote().contains("career terms"));
    }

    @Test
    void stageBPrefersTheOlderLastTerm() {
        Map<UUID, CandidateRecord> careers = new HashMap<>();
        careers.put(BOB, new CandidateRecord(BOB, 1, 4));
        careers.put(CARA, new CandidateRecord(CARA, 1, 2));

        TallyResult result = Tally.breakTie(eligible(BOB, CARA), careers, 2, 4, false, 5);
        TallyResult.Winner winner = assertInstanceOf(TallyResult.Winner.class, result);
        assertEquals(CARA, winner.candidate());
        assertTrue(winner.tieBreakNote().contains("previous term"));
    }

    @Test
    void stageCSendsTwoNeverMonarchsToARunoff() {
        // Neither has reigned, so neither (a) nor (b) can separate them.
        TallyResult result = Tally.breakTie(eligible(BOB, CARA), Map.of(), 2, 4, false, 5);
        TallyResult.RunoffNeeded runoff = assertInstanceOf(TallyResult.RunoffNeeded.class, result);
        assertEquals(Set.of(BOB, CARA), runoff.tied());
    }

    @Test
    void stageDResolvesARunoffTieWithASeededDraw() {
        TallyResult result = Tally.breakTie(eligible(BOB, CARA), Map.of(), 2, 4, true, 7);
        TallyResult.Winner winner = assertInstanceOf(TallyResult.Winner.class, result);
        assertTrue(Set.of(BOB, CARA).contains(winner.candidate()));
        assertTrue(winner.tieBreakNote().contains("seed=7"));
    }

    @Test
    void seededDrawIsStableAcrossRuns() {
        // The seed is the term index precisely so the result cannot be re-rolled by closing the
        // election again. Same inputs must always give the same winner.
        UUID first = null;
        for (int i = 0; i < 1000; i++) {
            TallyResult result = Tally.breakTie(eligible(BOB, CARA, DAN), Map.of(), 2, 6, true, 11);
            UUID winner = assertInstanceOf(TallyResult.Winner.class, result).candidate();
            if (first == null) {
                first = winner;
            }
            assertEquals(first, winner, "seeded draw must be deterministic");
        }
    }

    @Test
    void countIsDeterministicAcrossRepeatedRuns() {
        List<Ballot> ballots = new ArrayList<>(List.of(
                ballot(ALICE, BOB), ballot(BOB, CARA), ballot(CARA, BOB), ballot(DAN, CARA)));
        Map<UUID, CandidateRecord> careers = Map.of(
                BOB, new CandidateRecord(BOB, 1, 1),
                CARA, new CandidateRecord(CARA, 1, 1));

        UUID first = null;
        for (int i = 0; i < 1000; i++) {
            TallyResult result = Tally.count(ballots, eligible(BOB, CARA), careers, 4, 0.5, true, 3);
            UUID winner = assertInstanceOf(TallyResult.Winner.class, result).candidate();
            if (first == null) {
                first = winner;
            }
            assertEquals(first, winner);
        }
    }

    @Test
    void aClearMajorityNeedsNoTieBreak() {
        List<Ballot> ballots = List.of(
                ballot(ALICE, BOB), ballot(BOB, CARA), ballot(CARA, BOB), ballot(DAN, BOB));
        TallyResult result = Tally.count(ballots, eligible(BOB, CARA), Map.of(), 4, 0.5, false, 2);
        TallyResult.Winner winner = assertInstanceOf(TallyResult.Winner.class, result);
        assertEquals(BOB, winner.candidate());
        assertEquals(3, winner.votes());
        assertTrue(winner.tieBreakNote().isEmpty());
    }
}
