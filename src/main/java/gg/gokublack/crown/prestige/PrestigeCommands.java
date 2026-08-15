package gg.gokublack.crown.prestige;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import gg.gokublack.crown.core.CrownState;
import gg.gokublack.crown.core.CrownTime;
import gg.gokublack.crown.term.Term;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Reading the ledger (spec 10).
 *
 * <p>These commands render history. They never total anything up, never sort players against each
 * other, and never show a number that could be read as a score — a ranking here would reintroduce
 * exactly the algorithmic-contribution failure mode the design forbids.
 */
public final class PrestigeCommands {

    private PrestigeCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("laurels")
                .executes(ctx -> laurels(ctx, null))
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(ctx -> {
                            Collection<GameProfile> profiles =
                                    GameProfileArgument.getGameProfiles(ctx, "player");
                            return laurels(ctx, profiles.isEmpty() ? null
                                    : profiles.iterator().next().getId());
                        })));

        dispatcher.register(Commands.literal("halloffame")
                .executes(PrestigeCommands::hallOfFame));
    }

    private static int laurels(CommandContext<CommandSourceStack> ctx, UUID target) {
        CommandSourceStack source = ctx.getSource();
        CrownState state = CrownState.get(source.getServer());

        UUID who = target;
        if (who == null) {
            ServerPlayer self = source.getPlayer();
            if (self == null) {
                source.sendFailure(Component.literal("Name a player."));
                return 0;
            }
            who = self.getUUID();
        }
        final UUID subject = who;

        List<LedgerEntry> entries = new ArrayList<>();
        for (LedgerEntry entry : state.ledger()) {
            if (concerns(entry, subject)) {
                entries.add(entry);
            }
        }
        // Most recent first.
        entries.sort((a, b) -> Long.compare(b.at(), a.at()));

        String name = gg.gokublack.crown.core.Players.nameOf(source.getServer(), subject);
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal(name + " has no entries yet. Early days.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal("— " + name + " —").withStyle(ChatFormatting.GOLD), false);
        for (LedgerEntry entry : entries) {
            source.sendSuccess(() -> Component.literal("  " + render(entry))
                    .withStyle(ChatFormatting.WHITE), false);
        }
        return 1;
    }

    private static boolean concerns(LedgerEntry entry, UUID player) {
        return switch (entry) {
            case LedgerEntry.ReignServed r -> r.monarch().equals(player);
            case LedgerEntry.TitleGranted t -> t.recipient().equals(player);
            // An issued commission is open to anyone, so it is nobody's personal record; a
            // completion credited to "everyone" likewise lives in /halloffame, not /laurels.
            case LedgerEntry.CommissionIssued c -> false;
            case LedgerEntry.CommissionCompleted c -> player.equals(c.by());
            case LedgerEntry.AdminNote a -> false;
        };
    }

    private static String render(LedgerEntry entry) {
        return switch (entry) {
            case LedgerEntry.ReignServed r -> "reigned — term " + r.termIndex();
            case LedgerEntry.TitleGranted t -> "\"" + t.title() + "\" — granted by " + t.grantedByName()
                    + " (term " + t.termIndex() + ")";
            case LedgerEntry.CommissionIssued c -> "the crown called for: " + c.text()
                    + " (term " + c.termIndex() + ")";
            case LedgerEntry.CommissionCompleted c -> "delivered: " + c.text() + " (term " + c.termIndex() + ")";
            case LedgerEntry.AdminNote a -> "note: " + a.text();
        };
    }

    /** The museum plaque: one digest per term, oldest first. */
    private static int hallOfFame(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CrownState state = CrownState.get(source.getServer());

        List<Term> terms = new ArrayList<>(state.pastTerms());
        if (state.currentTerm() != null) {
            terms.add(state.currentTerm());
        }
        if (terms.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No terms have been served yet.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }

        for (Term term : terms) {
            source.sendSuccess(() -> Component.literal("\"" + term.name() + "\" — " + term.monarchName())
                    .withStyle(ChatFormatting.GOLD), false);
            source.sendSuccess(() -> Component.literal("    " + CrownTime.format(term.startedAt())
                            + (term.genre().isEmpty() ? "" : "  [" + term.genre() + "]"))
                    .withStyle(ChatFormatting.DARK_GRAY), false);
            for (String decree : term.decrees()) {
                source.sendSuccess(() -> Component.literal("    decree: " + decree)
                        .withStyle(ChatFormatting.LIGHT_PURPLE), false);
            }
            int completed = 0;
            for (LedgerEntry entry : state.ledger()) {
                if (entry instanceof LedgerEntry.CommissionCompleted c && c.termIndex() == term.index()) {
                    completed++;
                }
            }
            final int completedCount = completed;
            if (completedCount > 0) {
                source.sendSuccess(() -> Component.literal("    commissions completed: " + completedCount)
                        .withStyle(ChatFormatting.GRAY), false);
            }
        }
        return 1;
    }
}
