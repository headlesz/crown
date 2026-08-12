package gg.gokublack.crown.term;

import gg.gokublack.crown.Crown;
import gg.gokublack.crown.announce.AnnounceEvent;
import gg.gokublack.crown.announce.AnnounceType;
import gg.gokublack.crown.announce.Announcer;
import gg.gokublack.crown.core.CrownConfig;
import gg.gokublack.crown.core.CrownExporter;
import gg.gokublack.crown.core.CrownPermissions;
import gg.gokublack.crown.core.CrownState;
import gg.gokublack.crown.core.CrownTime;
import gg.gokublack.crown.core.Players;
import gg.gokublack.crown.election.Election;
import gg.gokublack.crown.election.ElectionManager;
import gg.gokublack.crown.election.Tally;
import gg.gokublack.crown.election.TallyResult;
import gg.gokublack.crown.pack.PackManager;
import gg.gokublack.crown.prestige.LedgerEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/**
 * The term state machine (spec 4). {@link #transition} is the only code path that changes who
 * holds the crown; everything else routes through it.
 */
public final class TermManager {

    private TermManager() {
    }

    // ------------------------------------------------------------------ elections

    /** Opens voting for the next term. Freezes the sitting monarch's powers if there is one. */
    public static void openElection(MinecraftServer server, CrownState state, long closesAt) {
        int forTerm = state.termIndex() + 1;
        Election election = new Election(forTerm, CrownTime.now(), closesAt);
        election.electorate().addAll(ElectionManager.electorate(server, state));
        state.setElection(election);
        state.setPhase(TermPhase.ELECTION);

        Term current = state.currentTerm();
        if (current != null) {
            current.setPowersFrozen(true);
        }
        state.setDirty();

        String candidates = String.join(", ", ElectionManager.candidates(server, state).values());
        Announcer.emit(server, AnnounceEvent.of(
                AnnounceType.ELECTION_OPENED,
                Component.literal("The vote for Term " + forTerm + " is open. ")
                        .withStyle(AnnounceType.ELECTION_OPENED.chatColor())
                        .append(Component.literal("Use /vote — closes in "
                                + CrownTime.remaining(closesAt) + ".").withStyle(ChatFormatting.WHITE)),
                "Election open — Term " + forTerm,
                "Voting closes " + CrownTime.format(closesAt) + ".\nCandidates: "
                        + (candidates.isEmpty() ? "(none yet)" : candidates)
                        + "\nBallots are cast in-game with /vote."));
        CrownExporter.export(server, state);
    }

    public static void extendElection(MinecraftServer server, CrownState state, String reason) {
        Election election = state.election();
        if (election == null) {
            return;
        }
        election.incrementExtensions();
        election.setClosesAt(CrownTime.now() + CrownTime.hours(CrownConfig.ELECTION_EXTENSION_HOURS.get()));
        state.setDirty();

        Announcer.emit(server, AnnounceEvent.of(
                AnnounceType.ELECTION_EXTENDED,
                Component.literal("Voting extended: " + reason + " New close in "
                        + CrownTime.remaining(election.closesAt()) + ".")
                        .withStyle(AnnounceType.ELECTION_EXTENDED.chatColor()),
                "Election extended",
                reason + "\nNew close: " + CrownTime.format(election.closesAt())));
        CrownExporter.export(server, state);
    }

    /**
     * Counts the ballots and acts on the outcome: install a winner, extend, run a runoff, or
     * fall into interregnum once extensions are exhausted (spec 4.2).
     */
    public static void closeElection(MinecraftServer server, CrownState state) {
        Election election = state.election();
        if (election == null) {
            return;
        }

        TallyResult result = Tally.count(
                election.ballots().values(),
                ElectionManager.eligibleCandidateIds(server, state),
                state.careerRecords(),
                election.electorate().size(),
                CrownConfig.QUORUM_FRACTION.get(),
                election.runoff(),
                election.forTermIndex());

        int maxExtensions = CrownConfig.MAX_ELECTION_EXTENSIONS.get();

        if (result instanceof TallyResult.Winner winner) {
            Announcer.emit(server, AnnounceEvent.of(
                    AnnounceType.ELECTION_CLOSED,
                    Component.literal("The vote is closed. " + winner.votes() + " of "
                            + winner.totalBallots() + " ballots carried the day.")
                            .withStyle(AnnounceType.ELECTION_CLOSED.chatColor()),
                    "Election closed",
                    "Winner takes the throne with " + winner.votes() + "/" + winner.totalBallots()
                            + " ballots."
                            + (winner.tieBreakNote().isEmpty() ? "" : "\n" + winner.tieBreakNote())));
            if (!winner.tieBreakNote().isEmpty()) {
                Crown.LOGGER.info("Election tie-break: {}", winner.tieBreakNote());
            }
            transition(server, state, TransitionCause.TERM_ELAPSED, winner.candidate());
            return;
        }

        if (result instanceof TallyResult.RunoffNeeded runoff) {
            long closesAt = CrownTime.now() + CrownTime.hours(CrownConfig.ELECTION_EXTENSION_HOURS.get());
            election.beginRunoff(runoff.tied(), closesAt);
            state.setDirty();
            StringBuilder names = new StringBuilder();
            for (UUID id : runoff.tied()) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(Players.nameOf(server, id));
            }
            Announcer.emit(server, AnnounceEvent.of(
                    AnnounceType.ELECTION_EXTENDED,
                    Component.literal("Dead heat. A runoff between " + names + " is open for "
                            + CrownTime.remaining(closesAt) + ".")
                            .withStyle(AnnounceType.ELECTION_EXTENDED.chatColor()),
                    "Runoff",
                    "Tied: " + names + "\nRunoff closes " + CrownTime.format(closesAt)));
            CrownExporter.export(server, state);
            return;
        }

        String reason = result instanceof TallyResult.NoQuorum nq
                ? "only " + nq.ballots() + " of " + nq.required() + " required ballots were cast."
                : "no eligible candidate received a vote.";

        if (election.extensions() < maxExtensions) {
            extendElection(server, state, reason);
        } else {
            enterInterregnum(server, state, TransitionCause.FAILED_ELECTION, reason);
        }
    }

    // ------------------------------------------------------------------ transitions

    /**
     * The one authoritative way to change the term (spec 4.3). Runs all six steps or none: the
     * state is snapshotted first and restored if any step throws.
     */
    public static void transition(MinecraftServer server,
                                  CrownState state,
                                  TransitionCause cause,
                                  @Nullable UUID incoming) {
        CompoundTag snapshot = state.snapshot();
        long transitionId = state.nextTransitionId();
        try {
            long now = CrownTime.now();

            // 1. Close the election.
            state.setElection(null);

            // 2/3. Archive and revoke the outgoing monarch.
            Term outgoing = state.currentTerm();
            if (outgoing != null) {
                state.pastTerms().add(outgoing);
                CrownPermissions.onMonarchRevoked(outgoing.monarch());
                Announcer.emit(server, AnnounceEvent.of(
                        AnnounceType.TERM_ENDED,
                        Component.literal("\"" + outgoing.name() + "\" has ended. "
                                + outgoing.monarchName() + " steps down.")
                                .withStyle(AnnounceType.TERM_ENDED.chatColor()),
                        "Term ended — " + outgoing.name(),
                        outgoing.monarchName() + "'s reign is over."
                                + (outgoing.decrees().isEmpty() ? ""
                                : "\nDecrees: " + String.join(" | ", outgoing.decrees()))));
                state.setCurrentTerm(null);
            }

            // 5. Per-term substate resets before the new monarch inherits anything.
            state.activeThisTerm().clear();
            state.openCommissions().clear();
            if (state.hasPack()) {
                PackManager.clearForAll(server, state);
            }

            if (incoming == null) {
                state.setPhase(TermPhase.INTERREGNUM);
            } else {
                // 4. Install the incoming monarch.
                int newIndex = state.termIndex() + 1;
                String name = Players.nameOf(server, incoming);
                long endsAt = now + CrownTime.days(CrownConfig.TERM_LENGTH_DAYS.get());
                Term term = new Term(newIndex, incoming, name, now, endsAt);
                state.setCurrentTerm(term);
                state.setTermIndex(newIndex);
                state.setPhase(TermPhase.REIGN);
                state.setMonarchLastSeen(now);
                state.setMonarchBriefed(false);
                state.markActive(incoming);

                CrownPermissions.onMonarchInstalled(incoming, endsAt);
                state.appendLedger(new LedgerEntry.ReignServed(incoming, name, newIndex, now));

                Announcer.emit(server, new AnnounceEvent(
                        AnnounceType.TERM_STARTED,
                        Component.literal("Long live " + name + " — monarch for Term " + newIndex + ".")
                                .withStyle(AnnounceType.TERM_STARTED.chatColor()),
                        Component.literal(name).withStyle(AnnounceType.TERM_STARTED.chatColor()),
                        "Term " + newIndex + " begins",
                        name + " takes the throne until " + CrownTime.format(endsAt)
                                + ".\nCause: " + cause.name().toLowerCase()));
            }

            // 6. Persist and publish.
            state.setDirty();
            CrownExporter.export(server, state);
            Crown.LOGGER.info("Term transition #{} complete ({} -> {})",
                    transitionId, cause, incoming == null ? "INTERREGNUM" : incoming);
        } catch (Exception e) {
            Crown.LOGGER.error("Term transition #{} failed; rolling back to the prior state",
                    transitionId, e);
            state.restore(snapshot);
            Announcer.chat(server, Component.literal(
                            "[Crown] A term transition failed and was rolled back. Check the server log.")
                    .withStyle(ChatFormatting.RED));
        }
    }

    public static void enterInterregnum(MinecraftServer server,
                                        CrownState state,
                                        TransitionCause cause,
                                        String reason) {
        transition(server, state, cause, null);
        Announcer.emit(server, AnnounceEvent.of(
                AnnounceType.INTERREGNUM,
                Component.literal("The throne stands empty: " + reason
                        + " A new vote opens now.").withStyle(AnnounceType.INTERREGNUM.chatColor()),
                "Interregnum",
                reason + "\nA fresh election is opening."));
        openElection(server, state, CrownTime.now()
                + CrownTime.hours(CrownConfig.ELECTION_WINDOW_HOURS.get()));
    }

    /** Starts the very first term of a campaign by opening the opening election. */
    public static void ensureCampaignStarted(MinecraftServer server, CrownState state) {
        if (state.currentTerm() == null && state.election() == null
                && state.phase() == TermPhase.INTERREGNUM && !state.activeThisTerm().isEmpty()) {
            Crown.LOGGER.info("No monarch and no election: opening the campaign's first vote");
            openElection(server, state, CrownTime.now()
                    + CrownTime.hours(CrownConfig.ELECTION_WINDOW_HOURS.get()));
        }
    }

    /** Shared by {@code /crown resign} and {@code /crown admin remove-monarch}. */
    public static void vacateThrone(MinecraftServer server,
                                    CrownState state,
                                    TransitionCause cause,
                                    String reason) {
        Term current = state.currentTerm();
        if (current == null) {
            return;
        }
        Announcer.emit(server, AnnounceEvent.of(
                AnnounceType.INTERREGNUM,
                Component.literal(current.monarchName() + " no longer holds the crown: " + reason)
                        .withStyle(AnnounceType.INTERREGNUM.chatColor()),
                "The throne is vacated",
                current.monarchName() + " — " + reason));
        enterInterregnum(server, state, cause, reason);
    }

    /** Ballot counts for {@code /vote status} and admin status dumps. */
    public static Map<UUID, String> candidateNames(MinecraftServer server, CrownState state) {
        return ElectionManager.candidates(server, state);
    }
}
