package gg.gokublack.crown.election;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Pure counting and tie-breaking. No Minecraft types, no clock, no randomness that is not seeded
 * — so the same inputs always produce the same winner, which is what makes the result auditable
 * and non-reroll-able (spec 4.4d).
 */
public final class Tally {

    private Tally() {
    }

    /** Ballots needed for the result to stand: {@code ceil(fraction * electorate)}, minimum 1. */
    public static int quorumRequired(int electorateSize, double fraction) {
        if (electorateSize <= 0) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(fraction * electorateSize));
    }

    /**
     * Count an election.
     *
     * @param ballots        the ballots cast, one per voter
     * @param eligible       candidates who may legally win (the sitting monarch is not among them)
     * @param careers        career records for tie-breaking
     * @param electorateSize how many players were entitled to vote
     * @param quorumFraction fraction of the electorate required
     * @param isRunoff       whether this is already a runoff (stage c has been used up)
     * @param termIndex      seeds the last-resort coin flip
     */
    public static TallyResult count(Collection<Ballot> ballots,
                                    Set<UUID> eligible,
                                    Map<UUID, CandidateRecord> careers,
                                    int electorateSize,
                                    double quorumFraction,
                                    boolean isRunoff,
                                    int termIndex) {

        List<Ballot> valid = ballots.stream().filter(b -> eligible.contains(b.candidate())).toList();

        int required = quorumRequired(electorateSize, quorumFraction);
        if (valid.size() < required) {
            return new TallyResult.NoQuorum(valid.size(), required);
        }
        if (valid.isEmpty()) {
            return new TallyResult.NoCandidates();
        }

        Map<UUID, Integer> counts = new HashMap<>();
        for (Ballot b : valid) {
            counts.merge(b.candidate(), 1, Integer::sum);
        }

        int top = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        // Sorted so the leading set is itself deterministic before any tie-break runs.
        Set<UUID> leaders = counts.entrySet().stream()
                .filter(e -> e.getValue() == top)
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparing(UUID::toString))
                .collect(LinkedHashSet::new, Set::add, Set::addAll);

        if (leaders.size() == 1) {
            UUID winner = leaders.iterator().next();
            return new TallyResult.Winner(winner, top, valid.size(), "");
        }
        return breakTie(leaders, careers, top, valid.size(), isRunoff, termIndex);
    }

    /**
     * Stages (a)-(d) of spec 4.4, in order: fewest career terms, then oldest last term, then a
     * runoff between the survivors, then a seeded coin flip.
     */
    public static TallyResult breakTie(Set<UUID> tied,
                                       Map<UUID, CandidateRecord> careers,
                                       int votes,
                                       int totalBallots,
                                       boolean isRunoff,
                                       int termIndex) {

        List<CandidateRecord> pool = new ArrayList<>();
        for (UUID id : tied) {
            pool.add(careers.getOrDefault(id, new CandidateRecord(id, 0, -1)));
        }

        // (a) fewer career terms wins.
        int fewestTerms = pool.stream().mapToInt(CandidateRecord::careerTerms).min().orElse(0);
        List<CandidateRecord> stageA = pool.stream()
                .filter(c -> c.careerTerms() == fewestTerms)
                .toList();
        if (stageA.size() == 1) {
            return new TallyResult.Winner(stageA.get(0).id(), votes, totalBallots,
                    "tie broken on fewest career terms (" + fewestTerms + ")");
        }

        // (b) among equals, the one whose last term is older wins. Never-monarchs have no last
        // term, so a field of never-monarchs falls straight through to the runoff.
        if (fewestTerms > 0) {
            int oldest = stageA.stream().mapToInt(CandidateRecord::lastTermIndex).min().orElse(-1);
            List<CandidateRecord> stageB = stageA.stream()
                    .filter(c -> c.lastTermIndex() == oldest)
                    .toList();
            if (stageB.size() == 1) {
                return new TallyResult.Winner(stageB.get(0).id(), votes, totalBallots,
                        "tie broken on oldest previous term (term " + oldest + ")");
            }
            stageA = stageB;
        }

        Set<UUID> survivors = stageA.stream()
                .map(CandidateRecord::id)
                .sorted(Comparator.comparing(UUID::toString))
                .collect(LinkedHashSet::new, Set::add, Set::addAll);

        // (c) a runoff between exactly the tied candidates — but only once.
        if (!isRunoff) {
            return new TallyResult.RunoffNeeded(survivors);
        }

        // (d) seeded coin flip. The seed is the term index, so the outcome is reproducible and
        // cannot be re-rolled by closing the election again.
        List<UUID> ordered = new ArrayList<>(survivors);
        UUID picked = ordered.get(new Random(termIndex).nextInt(ordered.size()));
        return new TallyResult.Winner(picked, votes, totalBallots,
                "runoff tied; resolved by seeded draw (seed=" + termIndex + ")");
    }
}
