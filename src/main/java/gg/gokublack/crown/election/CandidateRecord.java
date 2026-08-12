package gg.gokublack.crown.election;

import java.util.UUID;

/**
 * The career facts the tie-break needs about a candidate. Kept free of Minecraft types so the
 * tie-break can be unit-tested without bootstrapping the game (spec 13.1).
 *
 * @param id           the candidate
 * @param careerTerms  how many terms they have served across the campaign
 * @param lastTermIndex index of their most recent term, or {@code -1} if they have never reigned
 */
public record CandidateRecord(UUID id, int careerTerms, int lastTermIndex) {

    public boolean neverReigned() {
        return careerTerms == 0;
    }
}
