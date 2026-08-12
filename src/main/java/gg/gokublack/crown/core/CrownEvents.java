package gg.gokublack.crown.core;

import gg.gokublack.crown.Crown;
import gg.gokublack.crown.powers.CrownCommands;
import gg.gokublack.crown.pack.PackManager;
import gg.gokublack.crown.term.Term;
import gg.gokublack.crown.term.TermPhase;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
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

import java.util.List;

/** Wiring between NeoForge's event bus and Crown's managers. */
public final class CrownEvents {

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
                state.setMonarchBriefed(true);
                briefMonarch(player, state);
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
     * A winner installed while offline gets their briefing the moment they log in (spec 4.4):
     * a screen title, and a book listing what the crown actually lets them do.
     */
    private static void briefMonarch(ServerPlayer player, CrownState state) {
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
            if (!player.getInventory().add(book)) {
                player.drop(book, false);
            }
        } catch (Exception e) {
            // The book is a nicety; the powers work either way.
            Crown.LOGGER.warn("Could not hand the monarch their briefing book", e);
            player.sendSystemMessage(Component.literal(
                            "You are the monarch. Run /crown for what the crown lets you do.")
                    .withStyle(ChatFormatting.GOLD));
        }
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
