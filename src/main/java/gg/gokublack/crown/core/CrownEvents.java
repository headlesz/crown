package gg.gokublack.crown.core;

import gg.gokublack.crown.Crown;
import gg.gokublack.crown.powers.CrownCommands;
import gg.gokublack.crown.pack.PackManager;
import gg.gokublack.crown.prestige.LedgerEntry;
import gg.gokublack.crown.term.Term;
import gg.gokublack.crown.term.TermPhase;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Wiring between NeoForge's event bus and Crown's managers. */
public final class CrownEvents {

    /**
     * How long after login (or coronation) the briefing is delivered. Giving items during the
     * login tick races the client's join screen — the same race the pack push waits out — and at
     * coronation an immediate briefing title would stomp the "Long live" announcement title.
     */
    private static final long BRIEFING_DELAY_TICKS = 60;

    /** Briefings waiting out {@link #BRIEFING_DELAY_TICKS}, mirroring PackManager's join queue. */
    private static final ConcurrentLinkedQueue<PendingBriefing> PENDING_BRIEFINGS =
            new ConcurrentLinkedQueue<>();

    private record PendingBriefing(UUID player, long dueAtTick) {
    }

    private CrownEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CrownCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        try {
            CrownScheduler.reconcileOnStart(server);
        } catch (Exception e) {
            Crown.LOGGER.error("Crown failed to reconcile deadlines on startup", e);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        try {
            CrownScheduler.onServerTick(event.getServer());
        } catch (Exception e) {
            // A scheduler fault must never take the tick loop down with it.
            Crown.LOGGER.error("Crown scheduler tick failed", e);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.server;
        CrownState state = CrownState.get(server);

        state.markActive(player.getUUID());

        if (state.isMonarch(player.getUUID())) {
            state.setMonarchLastSeen(CrownTime.now());
            if (!state.monarchBriefed()) {
                queueBriefing(server, player.getUUID());
            }
        }

        if (state.hasPack()) {
            PackManager.queueJoinPush(server, player);
        }

        sendLoginSummary(player, state);
    }

    /** The MOTD-style line: who reigns, what the term is called, what its genre is. */
    private static void sendLoginSummary(ServerPlayer player, CrownState state) {
        Term term = state.currentTerm();
        if (term == null) {
            player.sendSystemMessage(Component.literal("The throne stands empty. ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(state.phase() == TermPhase.ELECTION
                                    ? "Cast your ballot with /vote."
                                    : "A vote opens when an operator calls one.")
                            .withStyle(ChatFormatting.WHITE)));
            return;
        }
        Component line = Component.literal("\"" + term.name() + "\"").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" — " + term.monarchName() + " reigns")
                        .withStyle(ChatFormatting.YELLOW));
        if (!term.genre().isEmpty()) {
            line = line.copy().append(Component.literal(" [" + term.genre() + "]")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        player.sendSystemMessage(line);

        for (String decree : term.decrees()) {
            player.sendSystemMessage(Component.literal("  decree: ").withStyle(ChatFormatting.DARK_PURPLE)
                    .append(Component.literal(decree).withStyle(ChatFormatting.WHITE)));
        }
    }

    /**
     * Queues the monarch's briefing for delivery a few seconds from now. Called at coronation
     * for an online winner, and at login for one installed while offline (spec 4.4). Idempotent:
     * a player already in the queue is not queued twice.
     */
    public static void queueBriefing(MinecraftServer server, UUID player) {
        for (PendingBriefing pending : PENDING_BRIEFINGS) {
            if (pending.player().equals(player)) {
                return;
            }
        }
        PENDING_BRIEFINGS.add(new PendingBriefing(player, server.getTickCount() + BRIEFING_DELAY_TICKS));
    }

    /** Drains due briefings. Called once per tick by the scheduler. */
    public static void processBriefingQueue(MinecraftServer server, CrownState state) {
        if (PENDING_BRIEFINGS.isEmpty()) {
            return;
        }
        long tick = server.getTickCount();
        List<PendingBriefing> ready = new ArrayList<>();
        for (PendingBriefing pending : PENDING_BRIEFINGS) {
            if (tick >= pending.dueAtTick()) {
                ready.add(pending);
            }
        }
        for (PendingBriefing pending : ready) {
            PENDING_BRIEFINGS.remove(pending);
            // The throne may have changed hands, or another queue entry may have delivered
            // already. A player who logged out is simply dropped: the flag is still false, so
            // the login path queues them again next time.
            if (!state.isMonarch(pending.player()) || state.monarchBriefed()) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(pending.player());
            if (player != null) {
                briefMonarch(player, state);
            }
        }
    }

    /**
     * The briefing itself (spec 4.4): a screen title, and a book listing what the crown actually
     * lets them do. {@code monarchBriefed} is set here, at actual delivery — never earlier — so
     * a briefing that fails to arrive is retried on the next login rather than lost.
     */
    private static void briefMonarch(ServerPlayer player, CrownState state) {
        state.setMonarchBriefed(true);
        player.connection.send(new ClientboundSetTitleTextPacket(
                Component.literal("You are the monarch").withStyle(ChatFormatting.GOLD)));

        try {
            ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
            book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                    Filterable.passThrough("The Crown"),
                    "Crown",
                    0,
                    List.of(
                            Filterable.passThrough(Component.literal("""
                                    You hold the crown.

                                    Your power is social, not mechanical. Nothing here lets you \
                                    change the world, another player's build, or the rules of the \
                                    server. What it lets you do is declare things — and be heard.

                                    Everything you declare expires when your term ends.""")),
                            Filterable.passThrough(Component.literal("""
                                    /crown name <text>
                                    Name your term.

                                    /crown genre <text>
                                    Set the mood of the term.

                                    /crown decree <text>
                                    Publish a rule. Crown enforces nothing — the group does.""")),
                            Filterable.passThrough(Component.literal("""
                                    /crown event create <name> <when>
                                    Schedule a gathering.

                                    /crown title grant <player> <title>
                                    Name someone for what they did.

                                    /crown commission <player> <text>
                                    Ask for a build, publicly.""")),
                            Filterable.passThrough(Component.literal("""
                                    /crown pack set <url> <sha1>
                                    Dress the server for your term.

                                    /crown endraid request <when> <hours>
                                    Call the season's finale. An operator must confirm it.

                                    /crown resign
                                    Step down."""))),
                    true));
            giveBook(player, book);
            // The briefed flag lives in the world's SavedData; the book lives in playerdata.
            // Saving players now closes the window where a crash rolls back one file but not
            // the other — which would mean a monarch marked briefed holding no book.
            player.server.getPlayerList().saveAll();
        } catch (Exception e) {
            // The book is a nicety; the powers work either way.
            Crown.LOGGER.warn("Could not hand the monarch their briefing book", e);
            player.sendSystemMessage(Component.literal(
                            "You are the monarch. Run /crown for what the crown lets you do.")
                    .withStyle(ChatFormatting.GOLD));
        }
    }

    /**
     * Hands over the briefing book without ever overwriting an occupied slot: it goes into the
     * first empty inventory slot, or onto the ground in front of the player when there is none.
     * Either way the monarch is told where it went.
     */
    private static void giveBook(ServerPlayer player, ItemStack book) {
        int slot = player.getInventory().getFreeSlot();
        if (slot >= 0) {
            player.getInventory().setItem(slot, book);
            player.sendSystemMessage(Component.literal(
                            "Your briefing, \"The Crown\", has been added to your inventory.")
                    .withStyle(ChatFormatting.GOLD));
        } else {
            player.drop(book, false);
            player.sendSystemMessage(Component.literal(
                            "Your inventory is full — your briefing, \"The Crown\", is on the "
                                    + "ground in front of you.")
                    .withStyle(ChatFormatting.GOLD));
        }
    }

    // ------------------------------------------------------------------ name display

    /**
     * The sitting monarch wears a gold, bold {@code [Monarch]} prefix, and anyone Crown has
     * titled wears their latest title as a bracketed suffix — rendered natively through the
     * display name, so it shows in chat and the tab list with no permissions mod and no
     * chat-formatter mod involved.
     */
    @SubscribeEvent
    public static void onNameFormat(PlayerEvent.NameFormat event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            event.setDisplayname(decorateName(player, event.getDisplayname()));
        }
    }

    @SubscribeEvent
    public static void onTabListNameFormat(PlayerEvent.TabListNameFormat event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Component base = event.getDisplayName();
            event.setDisplayName(decorateName(player, base == null ? player.getName() : base));
        }
    }

    private static Component decorateName(ServerPlayer player, Component name) {
        CrownState state = CrownState.get(player.server);
        boolean monarch = state.isMonarch(player.getUUID());
        String title = latestTitle(state, player.getUUID());
        if (!monarch && title == null) {
            return name;
        }
        // An empty parent, so each piece keeps its own style instead of inheriting the prefix's.
        MutableComponent out = Component.empty();
        if (monarch) {
            out.append(Component.literal("[Monarch] ")
                    .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        }
        out.append(name);
        if (title != null) {
            out.append(Component.literal(" [" + title + "]").withStyle(ChatFormatting.YELLOW));
        }
        return out;
    }

    /** The most recently granted title, or {@code null}. The ledger is append-only, so last wins. */
    private static String latestTitle(CrownState state, UUID player) {
        String title = null;
        for (LedgerEntry entry : state.ledger()) {
            if (entry instanceof LedgerEntry.TitleGranted granted && granted.recipient().equals(player)) {
                title = granted.title();
            }
        }
        return title;
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        CrownState state = CrownState.get(serverPlayer.server);
        if (state.isMonarch(player.getUUID())) {
            state.setMonarchLastSeen(CrownTime.now());
        }
    }
}
