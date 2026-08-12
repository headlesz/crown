package gg.gokublack.crown.core;

import gg.gokublack.crown.Crown;
import gg.gokublack.crown.announce.AnnounceEvent;
import gg.gokublack.crown.announce.AnnounceType;
import gg.gokublack.crown.announce.Announcer;
import gg.gokublack.crown.election.Election;
import gg.gokublack.crown.endgate.RaidManager;
import gg.gokublack.crown.pack.PackManager;
import gg.gokublack.crown.powers.ScheduledEvent;
import gg.gokublack.crown.term.Term;
import gg.gokublack.crown.term.TermManager;
import gg.gokublack.crown.term.TermPhase;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

/**
 * One coarse scheduler driven off the server tick (spec 3.2). Deadlines are checked at most once
 * a second, and every deadline is wall-clock, because terms are measured in real days and this
 * server restarts often.
 */
public final class CrownScheduler {

    /** Bound on reconciliation passes, so a pathological clock can never spin the loop forever. */
    private static final int MAX_RECONCILE_PASSES = 8;

    private static ServerBossEvent eventBar;

    private CrownScheduler() {
    }

    public static void onServerTick(MinecraftServer server) {
        CrownState state = CrownState.get(server);

        // The join-push queue is tick-granular, so it runs every tick.
        PackManager.processQueue(server, state);

        if (server.getTickCount() % 20 != 0) {
            return;
        }
        checkDeadlines(server, state);
        RaidManager.tick(server, state);
        updateEventBossBar(server, state);
    }

    /**
     * Fires anything whose deadline passed while the server was down, in the order the spec
     * defines: term end, then election open, then election close.
     */
    public static void reconcileOnStart(MinecraftServer server) {
        CrownState state = CrownState.get(server);
        long now = CrownTime.now();

        Election election = state.election();
        if (election != null && now >= election.closesAt() && election.ballots().isEmpty()) {
            // Nobody could have voted — the whole window elapsed with the server down. Reopening
            // beats tallying an election that was never actually held (spec 4.4).
            Crown.LOGGER.info("Election window elapsed while the server was offline; reopening");
            TermManager.extendElection(server, state,
                    "the server was offline for the whole voting window.");
        }

        for (int pass = 0; pass < MAX_RECONCILE_PASSES; pass++) {
            if (!checkDeadlines(server, state)) {
                break;
            }
        }
        RaidManager.tick(server, state);

        if (TermManager.awaitingCampaignStart(state)) {
            Crown.LOGGER.info("Crown is idle: no campaign has started yet. "
                    + "Run '/crown admin election open' to call the first vote.");
        }
    }

    /** @return true if this pass changed anything, so the caller knows to look again */
    private static boolean checkDeadlines(MinecraftServer server, CrownState state) {
        long now = CrownTime.now();
        Term current = state.currentTerm();

        if (state.phase() == TermPhase.REIGN && current != null) {
            long electionOpensAt = current.endsAt()
                    - CrownTime.hours(CrownConfig.ELECTION_WINDOW_HOURS.get());
            if (now >= electionOpensAt && state.election() == null) {
                TermManager.openElection(server, state, current.endsAt());
                return true;
            }
            checkMonarchPresence(server, state, current, now);
        }

        if (state.phase() == TermPhase.ELECTION) {
            Election election = state.election();
            if (election != null && now >= election.closesAt()) {
                TermManager.closeElection(server, state);
                return true;
            }
        }

        // Note: an empty throne never opens a vote by itself. A campaign is started deliberately
        // with /crown admin election open. The interregnum that follows a resignation, a removal
        // or a failed election is different — TermManager.enterInterregnum opens that vote itself,
        // because there the group has already committed to the cycle.

        if (current != null) {
            checkScheduledEvents(server, state, current, now);
        }
        return false;
    }

    /**
     * Warns when the monarch has been away too long. Deliberately never removes them: that call
     * is a human judgement, not a timer's (spec 4.4).
     */
    private static void checkMonarchPresence(MinecraftServer server, CrownState state, Term term, long now) {
        if (state.monarchAfkWarned() || state.monarchLastSeen() <= 0) {
            return;
        }
        long threshold = CrownTime.days(CrownConfig.MONARCH_AFK_DAYS.get());
        if (now - state.monarchLastSeen() < threshold) {
            return;
        }
        state.setMonarchAfkWarned(true);
        Announcer.chat(server, Component.literal("[Crown] " + term.monarchName()
                        + " has not been seen for " + CrownConfig.MONARCH_AFK_DAYS.get()
                        + " days. Operators may call an early election if the group wants one.")
                .withStyle(ChatFormatting.YELLOW));
    }

    private static void checkScheduledEvents(MinecraftServer server, CrownState state, Term term, long now) {
        long lead = CrownTime.minutes(CrownConfig.EVENT_COUNTDOWN_MINUTES.get());
        for (ScheduledEvent event : term.events()) {
            if (event.started()) {
                continue;
            }
            if (!event.countdownStarted() && now >= event.startsAt() - lead) {
                event.markCountdownStarted();
                state.setDirty();
                Announcer.chat(server, Component.literal("[Crown] \"" + event.name() + "\" starts in "
                                + CrownTime.remaining(event.startsAt()) + ".")
                        .withStyle(ChatFormatting.GREEN));
            }
            if (now >= event.startsAt()) {
                event.markStarted();
                state.setDirty();
                Announcer.emit(server, new AnnounceEvent(
                        AnnounceType.EVENT_STARTING,
                        Component.literal("\"" + event.name() + "\" begins now."
                                        + (event.description().isEmpty() ? "" : " " + event.description()))
                                .withStyle(AnnounceType.EVENT_STARTING.chatColor()),
                        Component.literal(event.name()).withStyle(AnnounceType.EVENT_STARTING.chatColor()),
                        "Event starting: " + event.name(),
                        event.description()));
            }
        }
    }

    /** A countdown bar for the next event inside its lead window. */
    private static void updateEventBossBar(MinecraftServer server, CrownState state) {
        Term term = state.currentTerm();
        long now = CrownTime.now();
        long lead = CrownTime.minutes(CrownConfig.EVENT_COUNTDOWN_MINUTES.get());

        ScheduledEvent next = null;
        if (term != null) {
            for (ScheduledEvent event : term.events()) {
                if (event.started() || now < event.startsAt() - lead || now >= event.startsAt()) {
                    continue;
                }
                if (next == null || event.startsAt() < next.startsAt()) {
                    next = event;
                }
            }
        }

        if (next == null) {
            if (eventBar != null) {
                eventBar.removeAllPlayers();
                eventBar.setVisible(false);
                eventBar = null;
            }
            return;
        }

        if (eventBar == null) {
            eventBar = new ServerBossEvent(Component.literal(next.name()),
                    BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
        }
        float progress = Math.max(0.0F, Math.min(1.0F, (next.startsAt() - now) / (float) lead));
        eventBar.setProgress(progress);
        eventBar.setName(Component.literal(next.name() + " in " + CrownTime.remaining(next.startsAt())));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            eventBar.addPlayer(player);
        }
    }
}
