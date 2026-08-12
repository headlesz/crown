package gg.gokublack.crown.endgate;

import gg.gokublack.crown.announce.AnnounceEvent;
import gg.gokublack.crown.announce.AnnounceType;
import gg.gokublack.crown.announce.Announcer;
import gg.gokublack.crown.core.CrownConfig;
import gg.gokublack.crown.core.CrownExporter;
import gg.gokublack.crown.core.CrownState;
import gg.gokublack.crown.core.CrownTime;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

/**
 * The two-key finale protocol (spec 6.2): the monarch proposes a window, an operator confirms it,
 * and Crown opens the gate for exactly that long.
 */
public final class RaidManager {

    private static ServerBossEvent bossBar;

    private RaidManager() {
    }

    /** @return an error message, or {@code null} if the proposal was accepted */
    public static String request(MinecraftServer server, CrownState state, ServerPlayer monarch,
                                 long startsAt, int durationHours) {
        if (state.raidsUsedThisCampaign() >= CrownConfig.MAX_RAIDS_PER_SEASON.get()) {
            return "the season's raid allowance is already spent. The End opens once per campaign.";
        }
        RaidState.Status status = state.raid().status();
        if (status == RaidState.Status.PENDING) {
            return "a raid proposal is already awaiting an operator.";
        }
        if (status == RaidState.Status.CONFIRMED || status == RaidState.Status.OPEN) {
            return "a raid window is already scheduled.";
        }
        if (startsAt <= CrownTime.now()) {
            return "that start time is in the past.";
        }

        int clamped = Math.min(durationHours, CrownConfig.RAID_WINDOW_MAX_HOURS.get());
        long endsAt = startsAt + CrownTime.hours(clamped);
        state.raid().propose(monarch.getUUID(), monarch.getGameProfile().getName(), startsAt, endsAt);
        state.setDirty();

        Announcer.emit(server, AnnounceEvent.of(
                AnnounceType.RAID_REQUESTED,
                Component.literal(monarch.getGameProfile().getName()
                        + " has called for the season's finale: the End would open "
                        + CrownTime.format(startsAt) + " for " + clamped + "h. Awaiting an operator.")
                        .withStyle(AnnounceType.RAID_REQUESTED.chatColor()),
                "End raid proposed",
                "Proposed by " + monarch.getGameProfile().getName()
                        + "\nStart: " + CrownTime.format(startsAt)
                        + "\nDuration: " + clamped + "h"
                        + (clamped < durationHours ? " (clamped from " + durationHours + "h)" : "")
                        + "\nAn operator must confirm with /crown admin endraid confirm."));
        CrownExporter.export(server, state);
        return null;
    }

    public static String confirm(MinecraftServer server, CrownState state) {
        if (state.raid().status() != RaidState.Status.PENDING) {
            return "there is no pending raid proposal to confirm.";
        }
        state.raid().confirm();
        state.setDirty();
        Announcer.emit(server, AnnounceEvent.of(
                AnnounceType.RAID_CONFIRMED,
                Component.literal("The finale is confirmed. The End opens "
                        + CrownTime.format(state.raid().startsAt()) + ".")
                        .withStyle(AnnounceType.RAID_CONFIRMED.chatColor()),
                "End raid confirmed",
                "Window: " + CrownTime.format(state.raid().startsAt())
                        + " to " + CrownTime.format(state.raid().endsAt())));
        CrownExporter.export(server, state);
        return null;
    }

    public static String deny(MinecraftServer server, CrownState state, String reason) {
        if (state.raid().status() != RaidState.Status.PENDING) {
            return "there is no pending raid proposal to deny.";
        }
        state.raid().deny(reason);
        state.setDirty();
        Announcer.emit(server, AnnounceEvent.of(
                AnnounceType.RAID_REQUESTED,
                Component.literal("The finale proposal was declined: " + reason)
                        .withStyle(ChatFormatting.GRAY),
                "End raid denied",
                reason));
        return null;
    }

    /** Ticked once a second by the scheduler. */
    public static void tick(MinecraftServer server, CrownState state) {
        RaidState raid = state.raid();
        long now = CrownTime.now();

        if (raid.status() == RaidState.Status.CONFIRMED && now >= raid.startsAt()) {
            raid.open();
            state.incrementRaidsUsed();
            state.setDirty();
            Announcer.emit(server, new AnnounceEvent(
                    AnnounceType.RAID_OPENED,
                    Component.literal("The End is open. Go.")
                            .withStyle(AnnounceType.RAID_OPENED.chatColor()),
                    Component.literal("THE END IS OPEN")
                            .withStyle(AnnounceType.RAID_OPENED.chatColor()),
                    "The End is open",
                    "The gate closes " + CrownTime.format(raid.endsAt()) + "."));
            CrownExporter.export(server, state);
        }

        if (raid.status() == RaidState.Status.OPEN) {
            if (now >= raid.endsAt()) {
                closeWindow(server, state);
            } else {
                updateBossBar(server, raid, now);
            }
        } else {
            clearBossBar();
        }
    }

    public static void closeWindow(MinecraftServer server, CrownState state) {
        RaidState raid = state.raid();
        raid.close(CrownTime.now());
        state.setDirty();
        clearBossBar();
        Announcer.emit(server, new AnnounceEvent(
                AnnounceType.RAID_CLOSED,
                Component.literal("The End is sealed again. Anyone still inside can walk out "
                        + "through the return portal — nobody is being moved.")
                        .withStyle(AnnounceType.RAID_CLOSED.chatColor()),
                Component.literal("THE END IS SEALED")
                        .withStyle(AnnounceType.RAID_CLOSED.chatColor()),
                "The End is sealed",
                "The finale window has closed. Entry is blocked; exit is not."));
        CrownExporter.export(server, state);
    }

    private static void updateBossBar(MinecraftServer server, RaidState raid, long now) {
        if (bossBar == null) {
            bossBar = new ServerBossEvent(
                    Component.literal("The End is open"),
                    BossEvent.BossBarColor.PURPLE,
                    BossEvent.BossBarOverlay.NOTCHED_10);
        }
        long total = Math.max(1L, raid.endsAt() - raid.startsAt());
        float progress = Math.max(0.0F, Math.min(1.0F, (raid.endsAt() - now) / (float) total));
        bossBar.setProgress(progress);
        bossBar.setName(Component.literal("The End closes in " + CrownTime.remaining(raid.endsAt())));

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            bossBar.addPlayer(player);
        }
    }

    private static void clearBossBar() {
        if (bossBar != null) {
            bossBar.removeAllPlayers();
            bossBar.setVisible(false);
            bossBar = null;
        }
    }
}
